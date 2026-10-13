package com.example.sqlquery.service;

import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.util.ConnectionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * 计算一条写语句的<b>影响面</b> —— 供审批人判断"这一下去得去不得"。
 *
 * <h2>为什么审批人需要这个</h2>
 *
 * <p>审批人看到的如果只是一段裸 SQL，他得自己读懂、自己判断影响范围。
 * 而现实里审批人往往是主管，<b>不是天天写 SQL 的人</b>。
 * 把"动哪几张表""大概扫多少行"直接摆出来，他才有办法做决定。
 *
 * <p>这一点在 AI 生成 SQL 之后变得更要紧：**人看不懂机器写的东西**，
 * 所以影响面不再是"锦上添花"，而是审批能成立的前提。
 *
 * <h2>⚠️ 这个数字必须被诚实地标注</h2>
 *
 * <p>EXPLAIN 给的是<b>预估扫描行数</b>，<b>不是影响行数</b>。
 * 两者的差距可能非常大：一条 {@code UPDATE ... WHERE status='待支付'}
 * 可能扫描 10 万行，但只改到 3 行。
 *
 * <p>所以字段叫 {@code estimatedRows} 而不是 {@code affectedRows}，
 * 而且必须带一条 note 说明这个数字的性质。
 * <b>把扫描行数当成"会改多少行"展示给审批人，是在制造错误的决策依据</b> ——
 * 那比不给数字更糟。
 *
 * <h2>它是"建议"而不是"拦截"</h2>
 *
 * <p>EXPLAIN 失败（语法、权限、方言不支持）时<b>不让整个流程失败</b>，
 * 而是把原因写进 note。理由是：预估失败不该拦住一次正当的工单，
 * 但必须让审批人知道"这次没有预估可参考"。
 */
@Slf4j
@Component
public class SqlImpactAnalyzer {

    /** EXPLAIN 本身的超时（秒）—— 它也可能很慢，尤其是统计信息缺失的表。 */
    private static final int EXPLAIN_TIMEOUT_SECONDS = 10;

    private final ConnectionManager connectionManager;
    private final ObjectMapper objectMapper;

    public SqlImpactAnalyzer(ConnectionManager connectionManager, ObjectMapper objectMapper) {
        this.connectionManager = connectionManager;
        this.objectMapper = objectMapper;
    }

    /**
     * 影响面分析结果。
     *
     * @param tables        涉及的表（来自 AST，不依赖数据库）
     * @param estimatedRows EXPLAIN 预估扫描行数；估不出来时为 {@code null}
     * @param note          对 {@code estimatedRows} 的说明（为什么是这个数、或者为什么没有）
     */
    public record ImpactAnalysis(List<String> tables, Long estimatedRows, String note) {
    }

    public ImpactAnalysis analyze(DbSource dbSource, SqlDialect dialect, String sql, List<String> tables) {
        try (Connection connection = connectionManager.getConnection(dbSource);
             Statement statement = connection.createStatement()) {

            statement.setQueryTimeout(EXPLAIN_TIMEOUT_SECONDS);

            try (ResultSet rs = statement.executeQuery(dialect.explainSql(sql))) {
                if (!rs.next()) {
                    return new ImpactAnalysis(tables, null, "EXPLAIN 没有返回结果，无法预估");
                }

                JsonNode root = objectMapper.readTree(rs.getString(1));
                Long rows = maxLongField(root, dialect.explainRowsField());
                boolean fullScan = containsTextValue(root,
                        dialect.explainFullScanField(), dialect.explainFullScanValue());

                return new ImpactAnalysis(tables, rows, buildNote(rows, fullScan));
            }
        } catch (Exception e) {
            // 预估失败不让整个提交流程失败 —— 但必须让审批人知道"这次没有预估"
            log.warn("EXPLAIN 预估失败，工单将不带预估行数。dataSourceId={}", dbSource.getId(), e);
            return new ImpactAnalysis(tables, null, "预估失败：" + brief(e.getMessage()));
        }
    }

    /**
     * 组织给审批人看的那句话。
     *
     * <p>⚠️ <b>措辞必须精确。</b>不能写成"将影响 N 行" ——
     * EXPLAIN 给的是<b>扫描</b>行数，一条 {@code UPDATE ... WHERE status='待支付'}
     * 可能扫描 10 万行、只改 3 行。把扫描行数说成影响行数，
     * 是在给审批人<b>制造错误的决策依据</b>，比不给数字更糟。
     */
    private String buildNote(Long rows, boolean fullScan) {
        if (rows == null) {
            return "该执行计划里没有可用的行数预估";
        }
        String base = "预估扫描约 " + rows + " 行（这是 EXPLAIN 的扫描行数，不是最终会改动的行数）";
        return fullScan ? base + "；⚠️ 包含全表扫描" : base;
    }

    /**
     * 递归找出 JSON 里某个字段的<b>最大值</b>。
     *
     * <p>为什么要递归 + 取最大：EXPLAIN 的输出是一棵树 ——
     * 有 join 时是 {@code nested_loop} 数组、有子查询时还有嵌套的 {@code query_block}。
     * 行数不在固定位置，要整棵树找。
     *
     * <p>取最大值而不是求和，是因为这个数字的用途是<b>风险提示</b>：
     * 审批人关心的是"最坏那张表要扫多少行"，不是"总共扫多少行"。
     */
    private static Long maxLongField(JsonNode node, String fieldName) {
        if (node == null) {
            return null;
        }

        Long max = null;

        if (node.isObject()) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isNumber()) {
                max = value.asLong();
            }
            // ⚠️ 原来用的是 node.fields() —— 它在 Jackson 里已标注 @Deprecated，替代品是
            //    properties()（返回 Set<Entry>，2.15+ 才有，本项目 Jackson 2.22 实证存在）。
            //    换成它之后连显式 Iterator 都不需要了，循环更直白。
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                Long child = maxLongField(entry.getValue(), fieldName);
                if (child != null && (max == null || child > max)) {
                    max = child;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                Long childMax = maxLongField(child, fieldName);
                if (childMax != null && (max == null || childMax > max)) {
                    max = childMax;
                }
            }
        }

        return max;
    }

    /** 递归判断 JSON 里是否存在"某个字段等于某个值" —— 用来识别全表扫描。 */
    private static boolean containsTextValue(JsonNode node, String fieldName, String expected) {
        if (node == null) {
            return false;
        }

        if (node.isObject()) {
            JsonNode value = node.get(fieldName);
            if (value != null && value.isTextual() && expected.equalsIgnoreCase(value.asText())) {
                return true;
            }
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                if (containsTextValue(entry.getValue(), fieldName, expected)) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsTextValue(child, fieldName, expected)) {
                    return true;
                }
            }
        }

        return false;
    }

    private String brief(String message) {
        if (message == null) {
            return "未知原因";
        }
        return message.length() > 120 ? message.substring(0, 120) + "…" : message;
    }
}
