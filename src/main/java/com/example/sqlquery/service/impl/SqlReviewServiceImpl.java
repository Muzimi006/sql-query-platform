package com.example.sqlquery.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.example.sqlquery.common.PageConstants;
import com.example.sqlquery.common.ReviewStatus;
import com.example.sqlquery.common.SqlOrigin;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.config.RabbitConfig;
import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.dto.SqlReviewSubmitDTO;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.entity.SqlReviewRequest;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.exception.SqlTypeNotAllowedException;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.mapper.SqlReviewRequestMapper;
import com.example.sqlquery.service.DbSourceService;
import com.example.sqlquery.service.SqlImpactAnalyzer;
import com.example.sqlquery.service.SqlPermissionService;
import com.example.sqlquery.service.SqlReviewService;
import com.example.sqlquery.util.SqlType;
import com.example.sqlquery.util.SqlValidateUtil;
import com.example.sqlquery.vo.SqlReviewRequestVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * SQL 审核工单的实现。
 *
 * <h2>这条链路上四个"必须做对"的点</h2>
 *
 * <ol>
 *   <li><b>能申请 ≠ 能执行</b>：提交用的白名单是 reviewable-types，
 *       不是角色白名单。见 {@link #doSubmit}。</li>
 *   <li><b>审批有竞态</b>：两个管理员同时点通过，只能有一个生效。
 *       用<b>条件更新</b>而不是"先查状态再判断"。见 {@link #approve}。</li>
 *   <li><b>执行的是工单里存的 SQL</b>：绝不接受前端再传一次，
 *       否则会出现"审批人看 A、执行 B"。这个约束在 DTO 结构上就固定死了。</li>
 *   <li><b>批准已落库、发消息失败</b>时不把错误抛给审批人。见 {@link #enqueueForExecution}。</li>
 * </ol>
 */
@Slf4j
@Service
public class SqlReviewServiceImpl implements SqlReviewService {

    private final SqlReviewRequestMapper reviewMapper;
    private final DbSourceService dbSourceService;
    private final SqlPermissionService sqlPermissionService;
    private final SqlImpactAnalyzer impactAnalyzer;
    private final RabbitTemplate rabbitTemplate;
    private final SqlPlatformMetrics metrics;

    public SqlReviewServiceImpl(SqlReviewRequestMapper reviewMapper,
                                DbSourceService dbSourceService,
                                SqlPermissionService sqlPermissionService,
                                SqlImpactAnalyzer impactAnalyzer,
                                RabbitTemplate rabbitTemplate,
                                SqlPlatformMetrics metrics) {
        this.reviewMapper = reviewMapper;
        this.dbSourceService = dbSourceService;
        this.sqlPermissionService = sqlPermissionService;
        this.impactAnalyzer = impactAnalyzer;
        this.rabbitTemplate = rabbitTemplate;
        this.metrics = metrics;
    }

    /**
     * 是否允许审批自己提交的工单。默认 {@code false}。
     *
     * <p>这条链路存在的理由就是「由另一个人看过之后决定」，
     * 而它偏偏是全平台<b>唯一一条执行人工写语句</b>的路径。
     * 允许自批的话，对 ADMIN 来说工单就退化成一次自我确认。
     *
     * <p>但不写死成规矩：只有一名管理员的小团队确实需要放开。
     * 做成配置，默认关；开关本身是显式的，比"谁都不知道能不能自批"要好。
     */
    @Value("${review.allow-self-approve:false}")
    private boolean allowSelfApprove;

    @Override
    public SqlReviewRequestVO submit(Long userId, SqlReviewSubmitDTO dto) {
        return doSubmit(userId, dto, SqlOrigin.HUMAN, null);
    }

    @Override
    public SqlReviewRequestVO submitGenerated(Long userId, SqlReviewSubmitDTO dto, String prompt) {
        return doSubmit(userId, dto, SqlOrigin.AI, dto.getGenerationPrompt() == null ? prompt : dto.getGenerationPrompt());
    }

    /**
     * 提交工单的公共实现。
     *
     * <p><b>刻意不加 {@code @Transactional}。</b>
     * 中间那次 EXPLAIN 要连的是<b>业务数据源</b>，不是本平台自己的库；
     * 把它包进事务里，只会让一个平台库的连接被白白占着，
     * 等一条可能十几秒的 EXPLAIN 跑完 —— 而这次事务里根本没有任何平台库的写操作。
     * <b>「事务里不要放外部调用」这条规则，在这里的具体样子就是"不要包 EXPLAIN"。</b>
     */
    private SqlReviewRequestVO doSubmit(Long userId, SqlReviewSubmitDTO dto,
                                        String origin, String prompt) {
        // 与查询/导出同一个入口：归属校验 + 启用状态校验。
        // 停用的数据源不能提交工单 —— 否则会出现"数据源已停用，工单却排队等执行"
        DbSource dbSource = dbSourceService.getEnabledByIdAndUserId(userId, dto.getDataSourceId());
        SqlDialect dialect = SqlDialect.fromType(dbSource.getType());

        // ① 校验。白名单是"可申请的类型"，不是"可执行的类型" —— 见方法上方 SqlReviewService 的说明。
        //    注意：放宽的只是类型。危险函数、系统库、可执行注释、INTO OUTFILE
        //    这四道防线一道都没少。
        SqlValidateUtil.ValidationResult validation;
        try {
            validation = SqlValidateUtil.validate(dto.getSql(),
                    sqlPermissionService.getReviewableTypes(), dialect);
        } catch (SqlTypeNotAllowedException e) {
            // 类型不在受理范围。这里必须换一句话说 ——
            // 原样转发"没有权限执行该类型SQL"会让提交 SELECT 的用户一头雾水：
            // 他明明有查询权限，系统却说他没权限。
            throw new BusinessException(explainNotReviewable(e.getActualType()));
        }

        // ② 影响面。VALIDATION 已经顺带解析出了表名，这里不需要再解析一遍。
        //    预估失败不会让提交失败，只会在 note 里写清楚"这次没有预估"。
        SqlImpactAnalyzer.ImpactAnalysis impact =
                impactAnalyzer.analyze(dbSource, dialect, dto.getSql(), validation.tables());

        SqlReviewRequest request = new SqlReviewRequest();
        request.setUserId(userId);
        request.setDataSourceId(dbSource.getId());
        request.setSqlText(dto.getSql());
        request.setSqlType(validation.sqlType().name());
        request.setStatus(ReviewStatus.PENDING);
        request.setInvolvedTables(String.join(",", impact.tables()));
        request.setEstimatedRows(impact.estimatedRows());
        request.setEstimateNote(impact.note());
        request.setGeneratedBy(origin);
        request.setGenerationPrompt(prompt);
        request.setRequestToken(blankToNull(dto.getRequestToken()));

        try {
            reviewMapper.insert(request);
        } catch (DuplicateKeyException e) {
            // 幂等键 (user_id, request_token) 上的唯一索引挡住了重复提交。
            // 这种情况要**返回已经存在的那条**，而不是报错：
            // 用户双击提交、或超时重试时，他期望的是"提交成功"，不是"你重复了"。
            SqlReviewRequest existing = findByToken(userId, request.getRequestToken());
            if (existing != null && isSameSubmission(existing, request)) {
                log.info("重复提交命中幂等键，返回已存在的工单。userId={}, token={}, reviewId={}",
                        userId, request.getRequestToken(), existing.getId());
                return toVO(existing);
            }
            if (existing != null) {
                // ⚠️ 幂等键命中了，但内容不一样 —— 这**不是**重复提交。
                //
                // 前端如果把 token 做成"一个会话一个"、或者干脆写死成常量，
                // 用户第二次提交一条完全不同的 SQL 就会落到这里：
                // 若直接返回旧工单，界面显示"提交成功"，
                // 而那条新 SQL 从来没入库、也永远不会被执行。
                // 这与"审批人看到 A、执行的是 B"是同一类问题，
                // 只是从幂等键这里又漏了出来 —— 而 AI 入口也带 requestToken。
                log.warn("幂等键命中但提交内容不同，拒绝复用。userId={}, token={}, existingId={}",
                        userId, request.getRequestToken(), existing.getId());
                throw new BusinessException("这个 requestToken 已经用于另一次提交了。"
                        + "幂等键代表「同一次提交的重试」，换一条 SQL 请换一个 token");
            }
            // 唯一索引撞了、却又查不到那条记录 —— 说明撞的不是幂等键，
            // 原样抛出，不要把它伪装成"重复提交"
            throw e;
        }

        return toVO(request);
    }

    @Override
    public IPage<SqlReviewRequestVO> pageMine(Long userId, long current, long size) {
        Page<SqlReviewRequest> page = new Page<>(current, clampSize(size));
        IPage<SqlReviewRequest> result = reviewMapper.selectPage(page,
                new LambdaQueryWrapper<SqlReviewRequest>()
                        .eq(SqlReviewRequest::getUserId, userId)
                        .orderByDesc(SqlReviewRequest::getId));
        return result.convert(this::toVO);
    }

    @Override
    public IPage<SqlReviewRequestVO> pagePending(long current, long size) {
        requireAdmin();
        Page<SqlReviewRequest> page = new Page<>(current, clampSize(size));
        IPage<SqlReviewRequest> result = reviewMapper.selectPage(page,
                new LambdaQueryWrapper<SqlReviewRequest>()
                        .eq(SqlReviewRequest::getStatus, ReviewStatus.PENDING)
                        // 先提交的先审：审批队列必须是稳定的顺序，
                        // 按 id 升序而不是创建时间 —— 同一秒内的多条工单，
                        // 用时间排序的次序是不确定的，翻页可能重复或漏掉
                        .orderByAsc(SqlReviewRequest::getId));
        return result.convert(this::toVO);
    }

    @Override
    public SqlReviewRequestVO detail(Long userId, Long id) {
        SqlReviewRequest request = reviewMapper.selectById(id);
        if (request == null) {
            throw new BusinessException("工单不存在");
        }

        // ⚠️ 越权访问别人的工单，回答的是「工单不存在」，不是「无权查看」。
        //
        // 理由：后者等于确认了"这个 id 上确实有一条工单"，
        // 拿一串 id 去试就能数出全部工单量；而"不存在"什么都不泄漏。
        // 这也是绝大多数系统的做法 —— 只要用户对"看不见的东西"和"不存在的东西"
        // 得到完全一样的回答，枚举就没有收益。
        if (!request.getUserId().equals(userId) && !UserContext.isAdmin()) {
            log.warn("用户 {} 试图查看不属于自己的工单 {}，按不存在处理", userId, id);
            throw new BusinessException("工单不存在");
        }

        return toVO(request);
    }

    @Override
    public void approve(Long reviewerId, Long id, String comment) {
        requireAdmin();
        SqlReviewRequest request = load(id);

        // ---- 四眼原则：不能审批自己提交的工单 ----
        //
        // 提交侧刻意不看角色（谁都能申请），这是对的 ——
        // 但它同时意味着"任何人都能给自己造一条待批工单"，
        // 于是整个流程的兜底就只剩审批这一步。这一步必须真的由另一个人做。
        if (request.getUserId().equals(reviewerId) && !allowSelfApprove) {
            throw new BusinessException("不能审批自己提交的工单，请由另一位管理员处理");
        }

        int affected = reviewMapper.update(null, new LambdaUpdateWrapper<SqlReviewRequest>()
                .eq(SqlReviewRequest::getId, id)
                // ⚠️ 这一行是并发安全的关键：把"必须是 PENDING"写进 WHERE，
                //    而不是先 select 出来判断。两个管理员同时点通过时，
                //    数据库只会让其中一条 UPDATE 命中一行，另一条 affected=0。
                //
                //    用"先查再判断再更新"的话，两边都可能读到 PENDING，
                //    然后先后写入 —— 两次审批都"成功"，评论和审批人被后写的覆盖。
                .eq(SqlReviewRequest::getStatus, ReviewStatus.PENDING)
                .set(SqlReviewRequest::getStatus, ReviewStatus.APPROVED)
                .set(SqlReviewRequest::getReviewerId, reviewerId)
                .set(SqlReviewRequest::getReviewComment, blankToNull(comment))
                .set(SqlReviewRequest::getReviewedAt, LocalDateTime.now())
                // 显式写 updated_at：补偿任务按它判卡死，而它必须和判定用的
                // LocalDateTime.now() 来自同一个时钟。见 SqlReviewConsumer#tryClaim
                .set(SqlReviewRequest::getUpdatedAt, LocalDateTime.now()));

        if (affected != 1) {
            throw new BusinessException("工单已被处理过，请刷新后查看最新状态");
        }

        // 上面这条 UPDATE 在自动提交下已经落库，之后才发消息 ——
        // 顺序不能反：先发消息再落库的话，消费者可能在状态还是 PENDING 时就来认领，
        // 认领失败（条件更新要求 PENDING→EXECUTING，其实这条是通的）……
        // 真正的风险是：消息发出去了，落库却失败了，于是"执行了一次没有任何记录的写操作"。
        // **先落库、后发消息**，最坏情况只是"批准了但没执行"，这是可以补偿的。
        enqueueForExecution(id);
    }

    @Override
    public void reject(Long reviewerId, Long id, String comment) {
        requireAdmin();

        // 驳回必须写原因。这不是流程上的形式主义：
        // 没有原因，申请人不知道要改什么，只能原样再交一遍，
        // 审批人再驳一遍 —— 工单系统会退化成一次次的互相消耗。
        if (comment == null || comment.isBlank()) {
            throw new BusinessException("驳回必须写明原因，否则申请人不知道需要修改什么");
        }

        requireExists(id);

        // 驳回**不**限制"自己驳回自己"：那本质上就是「撤回」，
        // 没有任何风险，禁止它只会让人被自己卡住 —— 一条提错了的工单再也撤不掉。
        int affected = reviewMapper.update(null, new LambdaUpdateWrapper<SqlReviewRequest>()
                .eq(SqlReviewRequest::getId, id)
                .eq(SqlReviewRequest::getStatus, ReviewStatus.PENDING)
                .set(SqlReviewRequest::getStatus, ReviewStatus.REJECTED)
                .set(SqlReviewRequest::getReviewerId, reviewerId)
                .set(SqlReviewRequest::getReviewComment, comment.trim())
                .set(SqlReviewRequest::getReviewedAt, LocalDateTime.now())
                .set(SqlReviewRequest::getUpdatedAt, LocalDateTime.now()));

        if (affected != 1) {
            throw new BusinessException("工单已被处理过，请刷新后查看最新状态");
        }

        // 驳回不发消息、不执行任何 SQL —— 这正是"驳回"和"批准"的全部区别
        log.info("工单被驳回。reviewId={}, reviewerId={}", id, reviewerId);
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /**
     * 把审批通过的消息投出去。
     *
     * <p>⚠️ <b>这里故意不让异常往外抛</b>，这是个需要解释的决定。
     *
     * <p>调用它之前，那次 UPDATE 已经提交了 —— 也就是说审批<b>已经生效</b>。
     * 如果此时因为 Broker 连不上而抛异常，审批人会看到一个错误提示，
     * 于是他认为"审批没成功"，然后再点一次 —— 这次会撞上
     * "工单已被处理过"，读起来完全像是个 bug。
     * <b>用一个假的失败去描述一次真的成功，比什么都不说更糟。</b>
     *
     * <p>真实情况是：工单停在 {@link ReviewStatus#APPROVED}，
     * 这是<b>准确</b>的状态描述（批了，还没执行）。它不会消失 ——
     * {@code SqlReviewRecoveryJob} 会把停留超过阈值的 APPROVED 重新投递。
     * 所以正确的做法是记一条 error 日志、让审批接口正常返回，
     * 由补偿任务兜底。
     *
     * <p>✅ 原来这里写的是「📌 已知的改进点：这条 error 日志只有人看得见，应该配一个计数器」。
     * 现在配上了：{@code mq_publish_failed{link=review}}。
     * **持续大于 0 就是 Broker 出问题了** —— 这句话本身就是告警规则。
     *
     * <p>带上 {@link CorrelationData}：开着 publisher confirm 时，
     * Broker 的确认/否定通过它回调回来（见 {@code RabbitConfirmConfig}）。
     */
    private void enqueueForExecution(Long reviewId) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.REVIEW_QUEUE, reviewId,
                    new CorrelationData("review-" + reviewId));
        } catch (Exception e) {
            // 捕 Exception 而不是 AmqpException：这条路径的全部语义就是
            // 「绝不把异常抛给审批人」。写成具体子类的话，
            // 将来一旦抛的是别的类型，这个承诺就静默失效了。
            // 先记指标再打日志：指标是给告警用的，日志是给人排查用的，两者都不能少。
            // 这件事"看起来一切正常"（接口返回 200、工单老老实实停在 APPROVED），
            // 所以只有一个持续大于 0 的计数器能把它暴露出来。
            metrics.recordPublishFailure(SqlPlatformMetrics.LINK_REVIEW);
            log.error("工单已批准但投递执行消息失败，工单将停在 APPROVED，"
                    + "由 SqlReviewRecoveryJob 补偿重投。reviewId={}", reviewId, e);
        }
    }

    /** 类型不在受理范围时，说一句对用户真正有用的话。 */
    private String explainNotReviewable(SqlType type) {
        if (type == SqlType.SELECT) {
            // ⚠️ 这一条是「这是查询所以不需要审批」，**不是**「你没权限」。
            //    说反了会把用户引到一个完全错误的方向上（去申请权限，而权限早就有）
            return "这是查询语句，不需要走审核工单，直接在查询页执行即可";
        }
        if (type == SqlType.DDL) {
            return "结构变更（DDL）不在工单受理范围内。工单是给「改数据」用的 —— "
                    + "放行一条 DROP/TRUNCATE 的风险，不是「多一个人看一眼」就能兜住的。"
                    + "这类操作请走 DBA 流程";
        }
        return "该类型的语句不在工单受理范围内：" + type;
    }

    private void requireAdmin() {
        if (!UserContext.isAdmin()) {
            throw new BusinessException("只有管理员可以审批 SQL 工单");
        }
    }

    private void requireExists(Long id) {
        if (reviewMapper.selectById(id) == null) {
            throw new BusinessException("工单不存在");
        }
    }

    private SqlReviewRequest findByToken(Long userId, String token) {
        if (token == null) {
            return null;
        }
        return reviewMapper.selectOne(new LambdaQueryWrapper<SqlReviewRequest>()
                .eq(SqlReviewRequest::getUserId, userId)
                .eq(SqlReviewRequest::getRequestToken, token));
    }

    /** 幂等键命中时，还要确认是**同一次提交**，而不是被复用的 token。 */
    private boolean isSameSubmission(SqlReviewRequest existing, SqlReviewRequest current) {
        return Objects.equals(existing.getSqlText(), current.getSqlText())
                && Objects.equals(existing.getDataSourceId(), current.getDataSourceId());
    }

    /** 读出工单，不存在就报错。比 requireExists 多返回实体，省掉一次重复查询。 */
    private SqlReviewRequest load(Long id) {
        SqlReviewRequest request = reviewMapper.selectById(id);
        if (request == null) {
            throw new BusinessException("工单不存在");
        }
        return request;
    }

    /** 分页大小兜底：上限与其它分页接口保持一致，见 {@link PageConstants}。 */
    private long clampSize(long size) {
        if (size <= 0) {
            return 10;
        }
        return Math.min(size, PageConstants.MAX_PAGE_SIZE);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private SqlReviewRequestVO toVO(SqlReviewRequest request) {
        SqlReviewRequestVO vo = new SqlReviewRequestVO();
        vo.setId(request.getId());
        vo.setDataSourceId(request.getDataSourceId());
        vo.setSqlText(request.getSqlText());
        vo.setSqlType(request.getSqlType());
        vo.setStatus(request.getStatus());
        vo.setInvolvedTables(splitTables(request.getInvolvedTables()));
        vo.setEstimatedRows(request.getEstimatedRows());
        vo.setEstimateNote(request.getEstimateNote());
        vo.setGeneratedBy(request.getGeneratedBy());
        vo.setGenerationPrompt(request.getGenerationPrompt());
        vo.setReviewerId(request.getReviewerId());
        vo.setReviewComment(request.getReviewComment());
        vo.setReviewedAt(request.getReviewedAt());
        vo.setAffectedRows(request.getAffectedRows());
        vo.setErrorMessage(request.getErrorMessage());
        vo.setExecutedAt(request.getExecutedAt());
        vo.setCreatedAt(request.getCreatedAt());
        vo.setUpdatedAt(request.getUpdatedAt());
        return vo;
    }

    /** 库里存的是逗号分隔的字符串，出口还原成数组 —— 存储格式是内部细节。 */
    private List<String> splitTables(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return Arrays.stream(joined.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
