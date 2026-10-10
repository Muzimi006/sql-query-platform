package com.example.sqlquery.service;

import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.dto.Nl2SqlRequestDTO;
import com.example.sqlquery.dto.SqlReviewSubmitDTO;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.exception.SqlTypeNotAllowedException;
import com.example.sqlquery.service.llm.LlmClient;
import com.example.sqlquery.util.SqlType;
import com.example.sqlquery.util.SqlValidateUtil;
import com.example.sqlquery.vo.SqlReviewRequestVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 自然语言 → SQL → <b>审核工单</b>。
 *
 * <h2>为什么落点是工单，而不是"生成一条 SQL 直接给你"</h2>
 *
 * <p>这是本项目里"工单"和"NL2SQL"两件事的结合点，也是它相对一个
 * 普通 NL2SQL 演示最有价值的地方：
 *
 * <ul>
 *   <li>提需求的人（景区运营、票务）<b>看不懂 SQL</b>。
 *       直接把一条 {@code UPDATE} 交给他去执行，等于把风险交给了他；</li>
 *   <li>所以模型生成的 SQL <b>先变成一张待审工单</b>，
 *       工单里同时躺着三样东西：<b>用户的原话</b>、
 *       <b>模型生成的 SQL</b>、<b>影响面预估（动哪几张表、扫多少行）</b>。</li>
 * </ul>
 *
 * <p>审批人看到的不再是"一段不知从哪来的 SQL"，而是
 * 「有人想要这个 → 机器理解成了这样 → 大概会动这些数据」。
 * <b>审核在这里才真正成立</b>：因为审核的对象从"一段代码"变成了"一个意图"。
 *
 * <h2>⚠️ 安全上最重要的一句话：AI 没有任何特权</h2>
 *
 * <p>模型生成的 SQL 和用户在页面上手敲的 SQL，走的是<b>完全同一条路径</b>：
 * 同一套四道防线、同一个工单状态机、同一个执行消费者。
 * 唯一的区别是 {@code generated_by} 这一列记成 {@code AI}，
 * 以及 {@code generation_prompt} 里多存一句原话。
 *
 * <p>刻意<b>没有</b>做"AI 生成的所以检查松一点"——
 * 一旦开了这样一条路径，它立刻会成为整个系统里最薄弱的一环，
 * 而且是最容易被忽略的一环（因为它在"帮助用户"的名义下）。
 *
 * <p>同样刻意的：<b>不自动执行</b>。哪怕模型给出的是一条看起来完全正确的 UPDATE。
 */
@Slf4j
@Service
public class NaturalLanguageSqlService {

    /**
     * 让模型在"信息不够"时用这个前缀回答，而不是硬编一个条件。
     *
     * <p>这个约定解决的是一个很实在的风险：
     * 用户说「把库存调一下」，模型如果硬要生成 SQL，最可能的产物是一条
     * <b>没有 WHERE 的 UPDATE</b> —— 而它甚至看起来是"完成了任务"。
     *
     * <p>有了这个哨兵，模型可以说"我不知道要给哪一天的库存"，
     * 用户拿到一句明确的追问，而不是一条把整张表改掉的 SQL。
     *
     * <p>⚠️ 但它<b>不是安全边界</b>：模型完全可以无视这个约定。
     * 真正兜底的是四道防线 + 审批。哨兵只是让"正常情况下的体验"更好。
     */
    static final String NEED_MORE_INFO = "NEED_MORE_INFO";

    private final LlmClient llmClient;
    private final DbSourceService dbSourceService;
    private final SchemaIntrospector schemaIntrospector;
    private final SqlPermissionService sqlPermissionService;
    private final SqlReviewService sqlReviewService;

    public NaturalLanguageSqlService(LlmClient llmClient,
                                     DbSourceService dbSourceService,
                                     SchemaIntrospector schemaIntrospector,
                                     SqlPermissionService sqlPermissionService,
                                     SqlReviewService sqlReviewService) {
        this.llmClient = llmClient;
        this.dbSourceService = dbSourceService;
        this.schemaIntrospector = schemaIntrospector;
        this.sqlPermissionService = sqlPermissionService;
        this.sqlReviewService = sqlReviewService;
    }

    public boolean available() {
        return llmClient.available();
    }

    /**
     * 生成 SQL 并直接落成一张待审工单。
     *
     * <p><b>刻意只提供这一个入口，没有单独的"只生成不提交"接口。</b>
     * 分成两个接口的话，用户会先看到一条 SQL、再点提交，
     * 而如果提交时重新生成一次，两次的输出可能不同（模型不是确定性的）——
     * 于是"用户以为提交的是 A，实际入库的是 B"。
     * 这个坑和"审批人看到 A、执行的是 B"是同一类问题，
     * 所以这里从<b>接口形状</b>上就堵死了：你看到的那条 SQL，就躺在返回的工单里。
     */
    public SqlReviewRequestVO generateTicket(Long userId, Nl2SqlRequestDTO dto) {
        DbSource dbSource = dbSourceService.getEnabledByIdAndUserId(userId, dto.getDataSourceId());
        SqlDialect dialect = SqlDialect.fromType(dbSource.getType());

        String schema = schemaIntrospector.describe(dbSource);
        String generated = normalize(llmClient.generateSql(buildSystemPrompt(dialect, schema), dto.getPrompt()));

        log.info("自然语言生成 SQL 完成。dataSourceId={}, prompt={}, generated={}",
                dbSource.getId(), dto.getPrompt(), generated);

        requireModelAskedForMoreInfo(generated);
        validateBeforeSubmit(generated, dialect, dto.getPrompt());

        SqlReviewSubmitDTO submit = new SqlReviewSubmitDTO();
        submit.setDataSourceId(dto.getDataSourceId());
        submit.setSql(generated);
        submit.setRequestToken(dto.getRequestToken());
        submit.setGenerationPrompt(dto.getPrompt());

        // 走的是和手写工单**完全相同**的提交路径，只是来源标成 AI
        return sqlReviewService.submitGenerated(userId, submit, dto.getPrompt());
    }

    /** 模型主动说"信息不够"时，把它的追问原样转达给用户。 */
    private void requireModelAskedForMoreInfo(String generated) {
        if (!generated.toUpperCase(Locale.ROOT).startsWith(NEED_MORE_INFO)) {
            return;
        }
        String reason = generated.substring(NEED_MORE_INFO.length())
                .replaceFirst("^[\\s:：]+", "")
                .trim();
        if (reason.isEmpty()) {
            reason = "缺少必要的信息";
        }
        log.info("模型拒绝生成 SQL，要求补充信息：{}", reason);
        throw new BusinessException("模型认为信息不够，无法生成 SQL：" + reason
                + "。请补充后再试（例如加上具体日期、景区名称或票种）");
    }

    /**
     * 提交之前先自己校验一遍。
     *
     * <p>{@code submitGenerated} 内部还会再校验一次（那才是权威的一遍），
     * 这里提前做的唯一目的是<b>把话说得更有用</b>：
     * 用户打了一句中文，拿到一个失败提示，
     * 他最需要知道的是两件事 —— 「模型到底写了什么」和「我该怎么改」。
     * 而底层的校验消息是给"看 SQL 的人"看的，缺了这两样。
     */
    private void validateBeforeSubmit(String generated, SqlDialect dialect, String prompt) {
        try {
            SqlValidateUtil.validate(generated, sqlPermissionService.getReviewableTypes(), dialect);
        } catch (SqlTypeNotAllowedException e) {
            if (e.getActualType() == SqlType.SELECT) {
                // 用户问的是"查一下有几张票"，模型给了一条查询语句。
                // 查询**本来就**不需要审批，所以这不是拒绝，而是"走错入口了" ——
                // 把生成的 SQL 原样给他，让他去查询页跑
                throw new BusinessException("这是一句查询需求，不需要审核。"
                        + "模型生成的查询语句请复制到查询页执行：" + generated);
            }
            throw new BusinessException("模型生成的语句类型（" + e.getActualType()
                    + "）不在工单受理范围内。工单只受理数据变更（INSERT / UPDATE / DELETE），"
                    + "结构变更请走 DBA 流程");
        } catch (BusinessException e) {
            // 危险函数 / 系统库 / 可执行注释 / 语法错误 —— 四道防线把模型的输出也拦下来了。
            // ⚠️ 这是**预期行为**，是这个设计要的效果，不是故障
            log.warn("模型生成的 SQL 未通过安全校验。prompt={}, generated={}, reason={}",
                    prompt, generated, e.getMessage());
            throw new BusinessException("模型生成的 SQL 没有通过安全校验（" + e.getMessage()
                    + "）。可以换一种说法再试，或改用手写 SQL 提交工单");
        }
    }

    /**
     * 组织给模型的系统提示。
     *
     * <p>表结构、方言、禁用函数全部来自<b>代码里的同源数据</b>
     * （{@link SqlDialect}），不是在这里再抄一份 ——
     * 抄一份的结果是"提示词里说可以用，校验时又拦下来"，
     * 或者反过来："提示词里禁用了 pg_sleep，而实际拦的是 MySQL 的 sleep"。
     *
     * <p>包级可见，供单元测试断言提示词里确实带了方言和表结构。
     */
    String buildSystemPrompt(SqlDialect dialect, String schema) {
        return "你是一个把中文业务需求翻译成 " + dialect.displayName() + " SQL 的助手。\n\n"
                + "硬性规则：\n"
                + "1. 只输出一条 SQL 语句。不要输出任何解释，不要用 markdown 代码块。\n"
                + "2. 只生成数据变更语句（INSERT / UPDATE / DELETE）。"
                + "不要生成 DDL（CREATE / ALTER / DROP / TRUNCATE），也不要生成 SELECT。\n"
                + "3. UPDATE / DELETE 必须带 WHERE 条件。"
                + "如果无法从需求里确定筛选条件，不要猜，直接回复 "
                + NEED_MORE_INFO + ": 并说明缺少什么信息。\n"
                + "4. 不要使用这些函数：" + String.join(", ", dialect.dangerousFunctions()) + "。\n"
                + "5. 不要访问这些系统库：" + String.join(", ", dialect.systemSchemas()) + "。\n"
                + "6. 只能使用下面列出的表和列，不要编造表名或列名。\n\n"
                + "数据库表结构：\n" + schema;
    }

    /**
     * 规整模型输出：去掉首尾空白和<b>尾部分号</b>。
     *
     * <p>去掉分号不是洁癖：{@code SqlValidateUtil} 的第一步就是"必须恰好一条语句"，
     * 而 {@code update t set x = 1;;}（两个分号、中间一个空语句）
     * 在 JSqlParser 眼里是<b>两条</b>语句，会被直接拒绝。
     * 用户完全没做错什么，只是模型习惯性多加了一个分号。
     */
    static String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
