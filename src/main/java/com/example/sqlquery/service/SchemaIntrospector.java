package com.example.sqlquery.service;

import com.example.sqlquery.dialect.SqlDialect;
import com.example.sqlquery.entity.DbSource;
import com.example.sqlquery.infra.ConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把数据源的表结构读成一段可以塞进提示词的文本。
 *
 * <h2>为什么必须给它戴上三个上限</h2>
 *
 * <p>这段文本是要<b>发给大模型</b>的，而模型的上下文是有价的（而且是要花钱的）。
 * 一个真实的业务库可能有几百张表、每张几十列 —— 全量塞进去的结果是：
 * 提示词几万 token、单次调用成本飙升、而且模型在超长上下文里<b>更容易找错表</b>。
 *
 * <p>所以有三个上限：表数、每张表的列数、总字符数。截断了就在末尾说明已截断 ——
 * <b>不能悄悄截断</b>：那样模型会因为看不到某张表而编一个表名出来，
 * 而用户完全不知道"是系统没把表给它看"。
 *
 * <h2>⚠️ 这是一个真实的数据出境问题，必须说清楚</h2>
 *
 * <p>把表名和列名发给外部模型服务，本身就是一次数据外流。
 * 这里的取舍是：
 * <ul>
 *   <li><b>只发元数据</b>（表名、列名、类型），<b>不发任何一行数据</b>；
 *       不发表注释（注释里常常写着业务含义甚至口令线索）、不发索引、不发外键；</li>
 *   <li><b>默认关闭</b>（见 {@link com.example.sqlquery.config.LlmProperties}），
 *       由部署方显式打开 —— 也就是说"发出去"这件事必须有人签字；</li>
 *   <li>真正合规的做法是<b>私有化部署模型</b>，把 base-url 指向内网。
 *       接口按 OpenAI 兼容写就是为了让这一步只是改配置。</li>
 * </ul>
 *
 * <h2>缓存与它的边界</h2>
 *
 * <p>表结构不会每分钟变一次，但每次提问都去读一遍 JDBC 元数据是浪费 ——
 * 元数据查询在某些驱动上并不便宜。所以这里做了一层 <b>JVM 内的 TTL 缓存</b>。
 *
 * <p>⚠️ 已知边界：多实例部署时每个实例各存一份，改了表结构最多会有
 * {@link #CACHE_TTL_MILLIS} 的延迟才全部刷新。
 * 对"生成 SQL 的提示词"这个用途来说这是可接受的 ——
 * 它不像连接串那样改了必须立刻生效（那边用的是 Redis 广播失效）。
 * 这里没有复用那套机制，是因为<b>为一个只影响提示词质量的缓存引入跨实例广播不划算</b>。
 */
@Slf4j
@Component
public class SchemaIntrospector {

    /** 最多描述多少张表。 */
    static final int MAX_TABLES = 60;

    /** 每张表最多描述多少列。 */
    static final int MAX_COLUMNS_PER_TABLE = 40;

    /** 整段文本的字符上限。 */
    static final int MAX_CHARS = 4000;

    /** 缓存有效期。 */
    static final long CACHE_TTL_MILLIS = 5 * 60 * 1000L;

    private final ConnectionManager connectionManager;
    private final ConcurrentHashMap<Long, CacheEntry> cache = new ConcurrentHashMap<>();

    public SchemaIntrospector(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    private record CacheEntry(String text, long expiresAt) {
    }

    /**
     * 取该数据源的表结构描述。失败时返回一句说明而不是抛异常。
     *
     * <p>为什么不让它抛：这一步是"给模型加点上下文"，
     * 失败了顶多是生成的 SQL 质量差一点，<b>不该让整个请求失败</b>。
     * 但如果真失败了，必须让用户看到"这次没有表结构可参考"，
     * 所以返回的是一句话，而不是空字符串。
     */
    public String describe(DbSource dbSource) {
        CacheEntry cached = cache.get(dbSource.getId());
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) {
            return cached.text();
        }

        String text;
        try (Connection connection = connectionManager.getConnection(dbSource)) {
            text = buildSchemaText(connection, SqlDialect.fromType(dbSource.getType()));
        } catch (Exception e) {
            log.warn("读取表结构失败，本次生成将不带表结构。dataSourceId={}", dbSource.getId(), e);
            return "（读取表结构失败：" + brief(e.getMessage()) + "，请只根据用户描述推断）";
        }

        cache.put(dbSource.getId(), new CacheEntry(text, System.currentTimeMillis() + CACHE_TTL_MILLIS));
        return text;
    }

    /** 数据源被改动（连接信息变了、库换了）时清掉缓存。 */
    public void evict(Long dataSourceId) {
        cache.remove(dataSourceId);
    }

    /**
     * 从活连接的 JDBC 元数据拼出表结构文本。
     *
     * <p><b>刻意不按 catalog / schema 过滤，而是拉全量再过滤掉系统库。</b>
     * 原因是这两件事在不同驱动上语义不一致：MySQL 的 catalog 就是库名、schema 为 null；
     * PostgreSQL 的 schema 是 {@code public}，catalog 是库名，而且还有
     * {@code pg_catalog} / {@code information_schema} 混在同一个 namespace 里。
     * 写死一套过滤条件，换一个方言就会静默地"一张表都找不到"。
     *
     * <p>包级可见是为了让单元测试能直接喂一个 mock 的 Connection。
     */
    String buildSchemaText(Connection connection, SqlDialect dialect) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        StringBuilder out = new StringBuilder();
        int tableCount = 0;
        boolean truncated = false;

        try (ResultSet tables = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String schema = tables.getString("TABLE_SCHEM");
                String tableName = tables.getString("TABLE_NAME");

                if (tableName == null || tableName.isBlank() || isSystemSchema(dialect, schema)) {
                    continue;
                }
                if (tableCount >= MAX_TABLES || out.length() >= MAX_CHARS) {
                    truncated = true;
                    break;
                }

                String line = describeTable(metaData, schema, tableName);
                if (out.length() + line.length() > MAX_CHARS) {
                    truncated = true;
                    break;
                }

                out.append(line).append('\n');
                tableCount++;
            }
        }

        if (tableCount == 0) {
            return "（没有读到任何用户表）";
        }
        if (truncated) {
            // ⚠️ 绝对不能悄悄截断：模型看不到某张表时不会说"我不知道"，
            //    它会编一个看起来很像的表名出来
            out.append("（表结构已截断，只列出了前 ").append(tableCount)
                    .append(" 张表。如果需求涉及的表不在上面，请先在提示里给出表名）\n");
        }
        return out.toString();
    }

    private String describeTable(DatabaseMetaData metaData, String schema, String tableName) throws SQLException {
        StringBuilder line = new StringBuilder("表 ").append(tableName).append('(');
        int columnCount = 0;

        try (ResultSet columns = metaData.getColumns(null, schema, tableName, "%")) {
            while (columns.next()) {
                if (columnCount >= MAX_COLUMNS_PER_TABLE) {
                    line.append(", …");
                    break;
                }
                if (columnCount > 0) {
                    line.append(", ");
                }
                line.append(columns.getString("COLUMN_NAME"))
                        .append(' ')
                        .append(columns.getString("TYPE_NAME"));
                columnCount++;
            }
        }

        return line.append(')').toString();
    }

    /**
     * 是不是系统库。
     *
     * <p>名单取自方言（{@link SqlDialect#systemSchemas()}）而不是写死在这里 ——
     * 这里写死一份、校验那边写死另一份，两份迟早会不一致，
     * 而不一致的表现是"某张系统表被当成了业务表发给了大模型"。
     */
    private boolean isSystemSchema(SqlDialect dialect, String schema) {
        if (schema == null) {
            return false;
        }
        return dialect.systemSchemas().contains(schema.toLowerCase(Locale.ROOT));
    }

    private String brief(String message) {
        if (message == null) {
            return "未知原因";
        }
        return message.length() > 100 ? message.substring(0, 100) + "…" : message;
    }
}
