package com.example.sqlquery.service;

import com.example.sqlquery.dialect.SqlDialect;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反向 SQL 的生成逻辑 —— 纯逻辑，不碰数据库。
 *
 * <p>要验的核心问题是：<b>前镜像能不能被翻译成一条「人看得懂、也能真的跑回去」的语句。</b>
 * 生成对了、但运行时跑不通，等于没做；所以这里验「长什么样」，
 * 而「跑回去数据真的还原了」由 {@code SqlUndoRecorderIntegrationTest} 用真实 MySQL 验。
 * <b>两层都要有。</b>
 */
class SqlUndoRecorderTest {

    private final SqlUndoRecorder recorder = new SqlUndoRecorder(null, new ObjectMapper());

    // ------------------------------------------------------------------
    // 能留存的形态
    // ------------------------------------------------------------------

    @Test
    @DisplayName("单表 UPDATE 可以留存，表名与 WHERE 都被解析出来")
    void singleTableUpdateShouldBeParsable() {
        SqlUndoRecorder.ParsedWrite parsed =
                recorder.parseWritable("update t_user set nickname = 'x' where id = 1");

        assertNotNull(parsed);
        assertEquals("t_user", parsed.tableName());
        assertEquals("id = 1", parsed.whereSql());
        assertFalse(parsed.delete());
    }

    @Test
    @DisplayName("单表 DELETE 也可以留存")
    void singleTableDeleteShouldBeParsable() {
        SqlUndoRecorder.ParsedWrite parsed =
                recorder.parseWritable("delete from t_user where id = 2");

        assertNotNull(parsed);
        assertTrue(parsed.delete());
    }

    @Test
    @DisplayName("⭐ 没有 WHERE 的全表 UPDATE 也能解析 —— 靠超上限那条路拦住，而不是靠解析")
    void updateWithoutWhereShouldStillParse() {
        // 设计取舍：这里**不**特判"没有 WHERE 就拒绝"。
        // 因为"全表更新三万行"和"更新三行但没写 where"在语法上没法区分，
        // 而后者是完全正当的。真正该拦的是**行数**，那条判断在取像之后做，
        // 而且它给出的理由（超过留存上限）比"不允许没有 where"更准确。
        SqlUndoRecorder.ParsedWrite parsed = recorder.parseWritable("update t_user set nickname = 'x'");

        assertNotNull(parsed);
        assertNull(parsed.whereSql());
    }

    @Test
    @DisplayName("不是单表 UPDATE/DELETE 的形态一律不留存")
    void otherShapesShouldNotBeParsed() {
        assertNull(recorder.parseWritable("insert into t_user (id) values (1)"), "INSERT 没有前镜像");
        assertNull(recorder.parseWritable("select * from t_user"), "查询不归这条链路管");
        assertNull(recorder.parseWritable("alter table t_user add column c int"), "DDL 无法生成反向 SQL");
        assertNull(recorder.parseWritable("delete t1 from t1 join t2 on t1.id = t2.id"),
                "多表 DELETE 解析不出单张表，不能留存");
    }

    @Test
    @DisplayName("⭐ 表别名必须留着 —— WHERE 引用的是别名，丢掉它取像会直接报错")
    void tableAliasShouldBePreserved() {
        SqlUndoRecorder.ParsedWrite parsed =
                recorder.parseWritable("update t_user u set u.nickname = 'x' where u.id = 1");

        assertNotNull(parsed);
        assertEquals("u", parsed.alias());
        // 取像的 FROM 片段必须带上别名，否则数据库会报 unknown column 'u.id'
        assertEquals("`t_user` u", parsed.fromClause(SqlDialect.MYSQL));
    }

    @Test
    @DisplayName("⭐ 多表 DELETE 的两种写法都要认出来（踩过一次坑）")
    void bothMultiTableDeleteFormsShouldBeRejected() {
        // 坑：DELETE t1 FROM t1 JOIN t2 的 getTable() **不是 null**（它返回 t1），
        // 所以"table == null"这个判据根本拦不住它 —— 会被当成单表 DELETE，
        // 然后生成一条语义错误的反向 SQL。
        assertNull(recorder.parseWritable("delete t1 from t1 join t2 on t1.id = t2.id"));
        // 另一种写法落在 getTables() 上，只查 join 会漏
        assertNull(recorder.parseWritable("delete t1, t2 from t1 join t2 on t1.id = t2.id"));
    }

    @Test
    @DisplayName("表名不是可安全拼接的形状时，宁可留存不下来")
    void unsafeTableNameShouldBeRejected() {
        // 表名来自 AST，最终会被拼进 SQL 字符串 —— 形状校验是这个功能的最后一道闸
        assertNull(recorder.parseWritable("update `weird table` set a = 1 where id = 1"));
    }

    // ------------------------------------------------------------------
    // 反向 SQL
    // ------------------------------------------------------------------

    @Test
    @DisplayName("⭐ UPDATE：只还原被改动的列，主键进 WHERE")
    void updateInverseShouldOnlyRestoreChangedColumns() {
        Map<String, Object> before = row("id", 1L, "nickname", "old", "age", 20L);
        Map<String, Object> after = row("id", 1L, "nickname", "new", "age", 20L);

        String inverse = recorder.buildInverseSql(parsedUpdate(), SqlDialect.MYSQL,
                List.of("id"), List.of(before), List.of(after));

        // age 没变，就不该出现在反向语句里 —— 反向 SQL 是要给人核对的，
        // 混进没变的列只会让"到底改了什么"变得难读
        assertTrue(inverse.contains("UPDATE `t_user` SET `nickname` = 'old' WHERE `id` = 1"),
                "实际：" + inverse);
        assertFalse(inverse.contains("age"), "没变动的列不该出现：" + inverse);
    }

    @Test
    @DisplayName("UPDATE 之后值没变的行不生成反向语句")
    void unchangedRowShouldProduceNothing() {
        Map<String, Object> row = row("id", 1L, "nickname", "same");

        String inverse = recorder.buildInverseSql(parsedUpdate(), SqlDialect.MYSQL,
                List.of("id"), List.of(row), List.of(row));

        assertEquals("", inverse);
    }

    @Test
    @DisplayName("⭐ DELETE：反向语句是 INSERT，且带上全部列")
    void deleteInverseShouldBeInsert() {
        Map<String, Object> before = row("id", 5L, "nickname", "gone");

        String inverse = recorder.buildInverseSql(parsedDelete(), SqlDialect.MYSQL,
                List.of("id"), List.of(before), List.of());

        assertTrue(inverse.contains("INSERT INTO `t_user`"), "实际：" + inverse);
        assertTrue(inverse.contains("`id`"), "实际：" + inverse);
        assertTrue(inverse.contains("5"), "实际：" + inverse);
        assertTrue(inverse.contains("'gone'"), "实际：" + inverse);
    }

    @Test
    @DisplayName("复合主键：两列都要进 WHERE，用 AND 连起来")
    void compositePrimaryKeyShouldBeFullyQualified() {
        Map<String, Object> before = row("tenant", 7L, "id", 1L, "v", "a");
        Map<String, Object> after = row("tenant", 7L, "id", 1L, "v", "b");

        String inverse = recorder.buildInverseSql(parsedUpdate(), SqlDialect.MYSQL,
                List.of("tenant", "id"), List.of(before), List.of(after));

        assertTrue(inverse.contains("WHERE `tenant` = 7 AND `id` = 1"), "实际：" + inverse);
    }

    @Test
    @DisplayName("一行都没改到：反向 SQL 是空串，调用方会记成「无需回滚」")
    void noRowsShouldProduceEmptyInverse() {
        assertEquals("", recorder.buildInverseSql(parsedUpdate(), SqlDialect.MYSQL,
                List.of("id"), List.of(), List.of()));
    }

    @Test
    @DisplayName("PostgreSQL 用双引号引用标识符，不是反引号")
    void postgresShouldUseDoubleQuotes() {
        Map<String, Object> before = row("id", 1L, "v", "old");
        Map<String, Object> after = row("id", 1L, "v", "new");

        String inverse = recorder.buildInverseSql(parsedUpdate(), SqlDialect.POSTGRESQL,
                List.of("id"), List.of(before), List.of(after));

        assertTrue(inverse.contains("UPDATE \"t_user\" SET \"v\" = 'old' WHERE \"id\" = 1"),
                "实际：" + inverse);
    }

    // ------------------------------------------------------------------
    // 字面量与不可表示的值
    // ------------------------------------------------------------------

    @Test
    @DisplayName("NULL / 数字 / 字符串 / 单引号转义")
    void literalFormatting() {
        assertEquals("NULL", SqlUndoRecorder.literal(null));
        assertEquals("42", SqlUndoRecorder.literal(42));
        assertEquals("42.5", SqlUndoRecorder.literal(42.5));
        assertEquals("true", SqlUndoRecorder.literal(true));
        assertEquals("'abc'", SqlUndoRecorder.literal("abc"));
        // ⭐ 单引号必须转义成两个 —— 少了它，一个 O'Brien 就能把反向 SQL 拆断
        assertEquals("'O''Brien'", SqlUndoRecorder.literal("O'Brien"));
    }

    @Test
    @DisplayName("⭐⭐ 反斜杠也必须转义 —— 只转义单引号会让回滚把数据改错")
    void literalMustEscapeBackslashToo() {
        // 实测过 MySQL 的语义（这不是推断）：
        //     SELECT HEX('a\b')  → 6108   （长度 2：a + 退格 0x08）
        //     SELECT HEX('a\\b') → 615C62 （长度 3：a \ b）
        // 也就是说，反向 SQL 里原样写一个反斜杠，回滚之后的值**是错的**，
        // 而且终端里退格会覆盖掉前一个字符，人工核对时看不出来。
        assertEquals("'a\\\\b'", SqlUndoRecorder.literal("a\\b"),
                "值里的反斜杠必须转义成两个，否则 MySQL 会把它当转义字符解释");

        // Windows 路径是最常见的受害者
        assertEquals("'C:\\\\temp\\\\new'", SqlUndoRecorder.literal("C:\\temp\\new"));

        // 反斜杠与单引号同时出现时，两个都要转义（顺序：先反斜杠再单引号）
        assertEquals("'O''Brien\\\\x'", SqlUndoRecorder.literal("O'Brien\\x"));
    }

    @Test
    @DisplayName("⭐ 二进制列：判定为不可留存，而且绝不生成一条「看起来对」的语句")
    void binaryColumnsShouldBeRejected() {
        assertTrue(SqlUndoRecorder.hasUnsupportedValue(List.of(row("id", 1L, "blob", new byte[]{1, 2}))));
        assertFalse(SqlUndoRecorder.hasUnsupportedValue(List.of(row("id", 1L, "name", "ok"))));

        // 兜底：真走到这里也必须抛，而不是把 byte[].toString() 拼进 SQL
        // （那会变成 '[B@1a2b3c' —— 一条语法上跑得通、语义上完全错误的语句）
        assertThrows(IllegalStateException.class, () -> SqlUndoRecorder.literal(new byte[]{1}));
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static SqlUndoRecorder.ParsedWrite parsedUpdate() {
        return new SqlUndoRecorder.ParsedWrite("t_user", "t_user", null, "id = 1", false);
    }

    private static SqlUndoRecorder.ParsedWrite parsedDelete() {
        return new SqlUndoRecorder.ParsedWrite("t_user", "t_user", null, "id = 5", true);
    }

    /** 用 LinkedHashMap 构造行 —— 列顺序稳定，断言才好写。 */
    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }
}
