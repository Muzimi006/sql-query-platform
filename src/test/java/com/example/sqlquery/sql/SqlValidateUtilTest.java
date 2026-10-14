package com.example.sqlquery.sql;

import com.example.sqlquery.config.SqlPermissionProperties;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.service.SqlPermissionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.example.sqlquery.entity.User;

/**
 * 覆盖 {@link SqlValidateUtil#validate} —— <b>生产代码唯一的 SQL 校验入口</b>。
 *
 * <p>这里曾经测的是另一个只做类型判断、不检查危险 SQL 的方法（已删除）。
 * 测试必须对准生产实际调用的入口，否则会给出虚假的安全感。
 *
 * <p>本类里的每一组"能绕过"的用例，都是<b>在真实 MySQL 上确认过确实能执行</b>的写法，
 * 不是凭空想出来的字符串把戏：
 * <ul>
 *   <li>{@code select sleep/**&#47;(1)} —— 实测执行耗时 1057ms，
 *       而旧的 {@code contains("sleep(")} 检查匹配不到；</li>
 *   <li>{@code select count(*) from `mysql`.`user`} —— 实测能查到用户表，
 *       而旧的 {@code contains("mysql.")} 检查匹配不到；</li>
 *   <li>{@code select /*!50000 sleep(1)*&#47; as x} —— 实测执行耗时 1096ms，
 *       说明 MySQL 把注释里的内容当真正的 SQL 执行了。</li>
 * </ul>
 */
class SqlValidateUtilTest {

    private static final Set<SqlType> SELECT_ONLY = Set.of(SqlType.SELECT);

    /** 本类绝大多数用例验的是 MySQL 下的行为；PostgreSQL 的差异在 DialectSpecific 里单独覆盖。 */
    private static final SqlDialect MYSQL = SqlDialect.MYSQL;

    private static void assertRejected(String sql) {
        assertThrows(BusinessException.class,
                () -> SqlValidateUtil.validate(sql, SELECT_ONLY, MYSQL), "应该被拒绝，但放行了：" + sql);
    }

    private static void assertAllowed(String sql) {
        assertDoesNotThrow(() -> SqlValidateUtil.validate(sql, SELECT_ONLY, MYSQL), "应该放行，但被拒了：" + sql);
    }

    /**
     * 断言"被拒绝"，并且<b>是被某一条具体规则拒绝的</b>。
     *
     * <p>只断言"抛了异常"是不够的：一条 SQL 可能因为语法错误被拒，
     * 而我们要守的那条规则其实根本没生效 —— 测试会绿，防线却是空的。
     * 断言消息片段才能证明"拦住它的是这条规则"。
     */
    private static void assertRejectedBy(String sql, String expectedMessageFragment) {
        BusinessException e = assertThrows(BusinessException.class,
                () -> SqlValidateUtil.validate(sql, SELECT_ONLY, MYSQL), "应该被拒绝，但放行了：" + sql);
        assertTrue(e.getMessage().contains(expectedMessageFragment),
                "拒绝原因不对。期望包含「" + expectedMessageFragment + "」，实际是「" + e.getMessage() + "」");
    }

    // ==================================================================
    // 语句类型白名单
    // ==================================================================

    @Nested
    @DisplayName("语句类型白名单")
    class TypeWhitelist {

        @Test
        void shouldAllowSelect() {
            assertAllowed("select * from user");
        }

        @Test
        void shouldRejectDelete() {
            assertRejected("delete from user");
        }

        @Test
        void shouldRejectDrop() {
            assertRejected("drop table user");
        }

        @Test
        void shouldRejectUpdateAndInsert() {
            assertRejected("update user set name = 'x'");
            assertRejected("insert into user(id) values(1)");
        }

        @Test
        void shouldRejectMultiStatement() {
            assertRejected("select 1; drop table user");
        }

        @Test
        void nullOrEmptyWhitelistShouldRejectEverything() {
            // 白名单缺失时必须全拒，不能因为配置漏了就放行
            assertThrows(BusinessException.class,
                    () -> SqlValidateUtil.validate("select * from user", null, MYSQL));
        }

        private final SqlPermissionService permissions =
                new SqlPermissionService(new SqlPermissionProperties());

        /**
         * ⚠️ 这条用例固定的是<b>改过之后</b>的行为。
         *
         * <p>旧实现是"未知角色兜底成 SELECT"：数据库里角色被写错一个字母，
         * 用户会<b>静默地</b>拿到读权限，没有任何地方报错。
         * 现在改成 fail-closed（空集合 = 什么都不允许），与
         * "白名单缺失时全拒"保持同一个原则：权限的默认值猜错方向，代价是不对称的。
         */
        @Test
        void unknownRoleShouldBeRejectedEntirely() {
            assertTrue(permissions.getAllowedTypes("UNKNOWN").isEmpty(),
                    "未知角色必须是空权限集合");
            assertThrows(BusinessException.class,
                    () -> SqlValidateUtil.validate("select * from user",
                            permissions.getAllowedTypes("UNKNOWN"), MYSQL));
        }

        /**
         * 旧实现在这里会抛 NPE：{@code Map.of(...).getOrDefault(null, ...)}
         * 对不可变 Map 传 null 键是不允许的，NPE 会被全局处理器翻译成「系统异常」——
         * 一个权限问题伪装成了服务端故障。
         */
        @Test
        void nullRoleShouldNotThrowNpe() {
            assertDoesNotThrow(() -> permissions.getAllowedTypes(null));
            assertTrue(permissions.getAllowedTypes(null).isEmpty());
            assertTrue(permissions.getAllowedTypes("   ").isEmpty());
        }

        @Test
        @DisplayName("角色大小写不敏感，且 USER 与 ADMIN 的权限必须真的不同")
        void rolesShouldBeCaseInsensitiveAndActuallyDiffer() {
            assertTrue(permissions.getAllowedTypes("user").contains(SqlType.SELECT));

            Set<SqlType> admin = permissions.getAllowedTypes("admin");
            assertTrue(admin.contains(SqlType.SELECT));
            // 这条是本次修复的核心：以前 USER 和 ADMIN 配的是同一套权限，
            // "按角色区分"这一层等于没落地
            assertNotEquals(permissions.getAllowedTypes("USER"), admin,
                    "USER 与 ADMIN 的权限集合不能再相同");
            assertTrue(admin.contains(SqlType.UPDATE));
        }

        @Test
        void blankSqlShouldBeRejected() {
            assertRejected("");
            assertRejected("   ");
            assertThrows(BusinessException.class, () -> SqlValidateUtil.validate(null, SELECT_ONLY, MYSQL));
        }
    }

    // ==================================================================
    // 危险函数：这些写法旧实现全都漏了
    // ==================================================================

    @Nested
    @DisplayName("危险函数（旧实现漏掉的写法）")
    class DangerousFunctions {

        @ParameterizedTest(name = "危险函数应被拒绝：{0}")
        @ValueSource(strings = {
                "select sleep(1)",
                // 函数名和括号之间有空格 —— 旧实现找的是字符串 "sleep("，匹配不到
                "select SLeeP (1)",
                // 注释把函数名和括号拆开 —— 实测能在 MySQL 上执行
                "select sleep/**/(1)",
                "select benchmark(1000000, md5('x'))",
                "select load_file('/etc/passwd')",
                "select get_lock('x', 1)",
                "select sys_exec('id')"
        })
        void shouldRejectDangerousFunction(String sql) {
            assertRejectedBy(sql, "危险函数");
        }

        @Test
        @DisplayName("嵌套在别的函数里也要能发现")
        void shouldFindFunctionNestedInAnotherFunction() {
            // 只检查"顶层函数名"的实现会在这里放行
            assertRejected("select coalesce(sleep(1), 0) from dual");
            assertRejected("select ifnull((select sleep(1)), 0)");
        }

        @Test
        @DisplayName("藏在子查询 / CTE / UNION / EXISTS / IN 里也要能发现")
        void shouldFindFunctionInsideSubqueries() {
            assertRejected("select * from (select sleep(1)) x");
            assertRejected("with c as (select sleep(1) as a) select * from c");
            assertRejected("select 1 from t union select sleep(1)");
            assertRejected("select 1 where exists (select 1 from t where benchmark(1,1) = 0)");
            assertRejected("select * from t where a in (select load_file('/x'))");
        }

        @Test
        @DisplayName("普通函数不能误杀")
        void shouldAllowOrdinaryFunctions() {
            assertAllowed("select count(*), max(id), min(id), sum(id) from user");
            assertAllowed("select coalesce(nickname, username), ifnull(id, 0) from user");
            assertAllowed("select date_format(created_at, '%Y-%m-%d') from user");
        }
    }

    // ==================================================================
    // 系统库
    // ==================================================================

    @Nested
    @DisplayName("系统库")
    class SystemSchema {

        @ParameterizedTest(name = "系统库应被拒绝：{0}")
        @ValueSource(strings = {
                "select * from information_schema.tables",
                "select * from mysql.user",
                "select * from performance_schema.threads",
                "select * from sys.x",
                // 反引号把库名和点隔开 —— 旧实现找的是字符串 "mysql."，匹配不到
                "select count(*) from `mysql`.`user`",
                // 双引号引用
                "select * from \"mysql\".\"user\"",
                // 出现在 JOIN 里
                "select a.id from t1 a join mysql.user u on 1=1",
                // 出现在子查询里
                "select * from t where a in (select id from information_schema.columns)"
        })
        void shouldRejectSystemSchema(String sql) {
            assertRejectedBy(sql, "不允许访问系统库");
        }

        @Test
        @DisplayName("大小写不影响判断")
        void schemaCheckShouldBeCaseInsensitive() {
            assertRejected("select * from MySQL.User");
            assertRejected("select * from INFORMATION_SCHEMA.TABLES");
        }

        @Test
        @DisplayName("自己的库里的同名表不能被误杀")
        void shouldAllowUserTablesWithSimilarNames() {
            // 只有"作为库名前缀出现"才算，表名里含这些词不算
            assertAllowed("select * from mysql_config");
            assertAllowed("select * from sys_log");
            assertAllowed("select * from performance_report");
            assertAllowed("select * from sql_query_platform.user");
        }
    }

    // ==================================================================
    // 可执行注释 —— 纯 AST 检查会漏掉，必须回到字符串
    // ==================================================================

    @Nested
    @DisplayName("MySQL 可执行注释")
    class ExecutableComment {

        @Test
        @DisplayName("注释里的内容会被 MySQL 真正执行，必须由「可执行注释」这条规则拒绝")
        void shouldRejectExecutableComment() {
            // 这几条都能被解析器**正常解析**，而且 AST 里只剩一个干净的 SELECT：
            //   select 1 /*!50000 into outfile '/tmp/x'*/   ==> [SELECT 1;]
            //   select 1 /*!50000 sleep(1)*/                ==> [SELECT 1;]
            //   select /*!32302 1/0, */ 1                   ==> [SELECT 1;]
            //   select 1 /*!50000 union select 1*/          ==> [SELECT 1;]
            // 也就是说纯 AST 检查对它们完全无能为力 —— 这正是必须保留字符串检查的原因。
            // 断言消息，是为了证明拦住它们的是这条规则，而不是碰巧的语法错误。
            assertRejectedBy("select 1 /*!50000 sleep(1)*/", "可执行注释");
            assertRejectedBy("select 1 /*!50000 into outfile '/tmp/x'*/", "可执行注释");
            assertRejectedBy("select /*!32302 1/0, */ 1", "可执行注释");
            assertRejectedBy("select 1 /*!50000 union select 1*/", "可执行注释");
            assertRejectedBy("select /*! STRAIGHT_JOIN */ 1 from t", "可执行注释");
        }

        @Test
        @DisplayName("解析不了的写法由语法错误兜底，方向同样是拒绝")
        void unparseableExecutableCommentIsStillRejected() {
            // 这一条 JSqlParser 解析不了（注释内容会被拼进来，拼出来不是合法 SQL），
            // 走的是"语法错误"分支。两条路都通向拒绝，只是提示不同。
            assertRejectedBy("select /*!50000 sleep(1)*/ as x", "语法错误");
        }

        @Test
        @DisplayName("MySQL 优化器提示 /*+ ... */ 不是可执行注释，不应被误杀")
        void optimizerHintsShouldNotBeTreatedAsExecutableComment() {
            assertAllowed("select /*+ MAX_EXECUTION_TIME(1000) */ id from user");
        }

        @Test
        @DisplayName("普通注释不影响判断")
        void ordinaryCommentsShouldBeFine() {
            assertAllowed("select 1 /* 这只是一段普通注释 */");
            assertAllowed("select 1 -- 普通行注释");
            assertAllowed("select /* sleep(1) */ 1");
        }

        @Test
        @DisplayName("字符串字面量里出现 /*! 不能被误杀")
        void markerInsideStringLiteralShouldBeAllowed() {
            assertAllowed("select '/*!' as marker");
            assertAllowed("select id from t where note = '/*! not a comment */'");
        }
    }

    // ==================================================================
    // INTO OUTFILE / DUMPFILE
    // ==================================================================

    @Nested
    @DisplayName("写服务器文件")
    class IntoOutFile {

        @Test
        void shouldRejectIntoOutFileAndDumpFile() {
            assertRejected("select * from user into outfile '/tmp/x'");
            assertRejected("select * from user into dumpfile '/tmp/x'");
        }

        @Test
        @DisplayName("字面量与注释里的关键词不能被误杀")
        void shouldNotFalsePositiveOnLiteralsOrComments() {
            // 解析器重新序列化会丢掉注释，所以注释里提到关键词不算
            assertAllowed("select 1 -- into outfile");
            assertAllowed("select 1 /* into outfile */");
            // 字面量会被屏蔽掉
            assertAllowed("select 'into outfile' as note");
            assertAllowed("select id from t where note = 'into dumpfile is blocked'");
        }
    }

    // ==================================================================
    // 字符串字面量屏蔽器本身
    // ==================================================================

    @Nested
    @DisplayName("stripStringLiterals")
    class StripStringLiterals {

        @Test
        void shouldRemoveSingleQuotedContent() {
            String stripped = SqlValidateUtil.stripStringLiterals("select 'abc' from t", MYSQL);
            assertFalse(stripped.contains("abc"));
            assertTrue(stripped.contains("select"));
            assertTrue(stripped.contains("from t"));
        }

        @Test
        void shouldRemoveDoubleQuotedContent() {
            assertFalse(SqlValidateUtil.stripStringLiterals("select \"abc\" from t", MYSQL).contains("abc"));
        }

        @Test
        @DisplayName("SQL 标准的 '' 转义：两个引号表示一个字面量引号，字符串没有结束")
        void shouldHandleDoubledQuoteEscape() {
            // 'it''s' 是一个完整的字符串字面量，后面的 from t 不能被吞进去
            String stripped = SqlValidateUtil.stripStringLiterals("select 'it''s' from t", MYSQL);
            assertFalse(stripped.contains("it"));
            assertTrue(stripped.contains("from t"), "转义处理错误会把后面的代码一起吞掉，实际：" + stripped);
        }

        @Test
        @DisplayName("MySQL 的 \\' 反斜杠转义同样要认")
        void shouldHandleBackslashEscape() {
            String stripped = SqlValidateUtil.stripStringLiterals("select 'a\\'b' from t", MYSQL);
            assertTrue(stripped.contains("from t"), "反斜杠转义处理错误，实际：" + stripped);
        }

        @Test
        @DisplayName("未闭合的引号：吃到结尾，不能把后面的内容当成代码")
        void unterminatedQuoteShouldSwallowToEnd() {
            String stripped = SqlValidateUtil.stripStringLiterals("select 'abc", MYSQL);
            assertFalse(stripped.contains("abc"));
        }

        @Test
        void emptyInputShouldBeHandled() {
            assertEquals("", SqlValidateUtil.stripStringLiterals("", MYSQL));
        }
    }

    // ==================================================================
    // 回归：旧实现的两个方向的问题都要被覆盖
    // ==================================================================

    @Nested
    @DisplayName("旧实现的问题不再复现")
    class RegressionsAgainstOldImplementation {

        @Test
        @DisplayName("误杀：字符串字面量里含 sleep( 的合法查询必须放行")
        void shouldNoLongerRejectLiteralContainingSleep() {
            // 旧实现做 contains("sleep(")，这句完全合法的查询会被拒绝
            assertAllowed("select 'sleep(1)' as note");
            assertAllowed("select id from t where note like '%benchmark(%'");
        }

        @Test
        @DisplayName("绕过：注释拆分的危险函数不能再溜过去")
        void shouldNoLongerAllowCommentSplitBypass() {
            assertRejected("select sleep/**/(1)");
            assertRejected("select SLEEP/**/ (1)");
        }

        @Test
        @DisplayName("绕过：引用符拆分的系统库不能再溜过去")
        void shouldNoLongerAllowQuotedSchemaBypass() {
            assertRejected("select count(*) from `mysql`.`user`");
            assertRejected("select count(*) from \"mysql\".\"user\"");
        }
    }

    // ==================================================================
    // 方言差异 —— 这一组是本次多方言改造新增的
    // ==================================================================

    @Nested
    @DisplayName("方言差异：MySQL 与 PostgreSQL 的危险名单完全不同")
    class DialectSpecific {

        private static final SqlDialect PG = SqlDialect.POSTGRESQL;

        private void assertPgRejectedBy(String sql, String fragment) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> SqlValidateUtil.validate(sql, SELECT_ONLY, PG), "应该被拒绝，但放行了：" + sql);
            assertTrue(e.getMessage().contains(fragment),
                    "拒绝原因不对。期望含「" + fragment + "」，实际「" + e.getMessage() + "」");
        }

        private void assertPgAllowed(String sql) {
            assertDoesNotThrow(() -> SqlValidateUtil.validate(sql, SELECT_ONLY, PG),
                    "应该放行，但被拒了：" + sql);
        }

        @Test
        @DisplayName("PG 的危险函数是另一套名字")
        void postgresqlDangerousFunctionsAreDifferent() {
            assertPgRejectedBy("select pg_sleep(1)", "危险函数");
            assertPgRejectedBy("select pg_read_file('/etc/passwd')", "危险函数");
            assertPgRejectedBy("select dblink_exec('dbname=x', 'delete from t')", "危险函数");
            assertPgRejectedBy("select pg_terminate_backend(123)", "危险函数");
        }

        @Test
        @DisplayName("★ MySQL 的名单拿来检查 PG 的 SQL 等于没检查")
        void mysqlFunctionNamesMustNotBeUsedForPostgres() {
            // sleep() 是 MySQL 的函数名。在 PG 里它根本不存在，
            // 所以「PG 方言下不拦 sleep」不是漏检，而是不该拦 ——
            // 真正该拦的 pg_sleep 已经在上一条里覆盖了。
            //
            // 这条用例的价值是<b>反向锁定</b>：如果哪天有人把两个方言的名单合并成一份，
            // 它会失败，提醒"你正在用一套名单应付两种数据库"。
            assertEquals(SqlDialect.MYSQL.dangerousFunctions().contains("sleep"), true);
            assertEquals(SqlDialect.POSTGRESQL.dangerousFunctions().contains("sleep"), false);
            assertTrue(java.util.Collections.disjoint(
                            SqlDialect.MYSQL.dangerousFunctions(),
                            SqlDialect.POSTGRESQL.dangerousFunctions()),
                    "两个方言的危险函数名单不应该有交集 —— 有交集说明有人在照抄");
        }

        @Test
        @DisplayName("PG 的系统库是 pg_catalog / information_schema")
        void postgresqlSystemSchemas() {
            assertPgRejectedBy("select * from pg_catalog.pg_class", "系统库");
            assertPgRejectedBy("select * from information_schema.tables", "系统库");
        }

        @Test
        @DisplayName("★ PG 的 pg_catalog 默认在 search_path 里，不带前缀也能访问")
        void postgresqlBareTableNamesMustAlsoBeChecked() {
            // 这是 PG 和 MySQL 的一个重要差异：MySQL 的系统库必须带库名前缀，
            // 而 PG 的 pg_catalog 默认就在 search_path 里 ——
            // 只检查"库名前缀"的实现会把下面这些<b>整个漏掉</b>。
            assertPgRejectedBy("select * from pg_shadow", "系统表");
            assertPgRejectedBy("select * from pg_authid", "系统表");
            assertPgRejectedBy("select usename, passwd from pg_shadow", "系统表");
        }

        @Test
        @DisplayName("PG 下无害的 pg_catalog 表不能被误拦")
        void harmlessPostgresCatalogTablesShouldPass() {
            // 只列真正敏感的那几张，不去穷举整个 pg_catalog ——
            // 那会把 pg_type 这种无害的表也拦掉，属于"为了安全把功能搞坏"
            assertPgAllowed("select * from pg_type");
            assertPgAllowed("select * from pg_class");
        }

        @Test
        @DisplayName("PG 专有语法要能正常解析并通过校验")
        void postgresqlSyntaxShouldBeAccepted() {
            assertPgAllowed("select id::text from t");
            assertPgAllowed("select * from t where name ilike '%a%'");
            assertPgAllowed("select * from generate_series(1, 10)");
            assertPgAllowed("select * from t limit 10 offset 5");
            assertPgAllowed("select jsonb_build_object('a', 1)");
            assertPgAllowed("select * from t for update skip locked");
        }

        @Test
        @DisplayName("PG 的 $$...$$ 字面量要被正确屏蔽，不能误杀")
        void postgresqlDollarQuotingShouldBeStripped() {
            // $$...$$ 里出现 /*! 或 into outfile 时不该被当成危险 SQL
            assertPgAllowed("select $$/*!*/$$ as note");
            assertPgAllowed("select $$into outfile$$ as note");

            // $tag$...$tag$ 形式：JSqlParser 4.9 解析不了它（会先以语法错误拦下），
            // 所以这里不跑完整的 validate，而是直接对<b>字面量屏蔽器</b>下断言 ——
            // 屏蔽器的正确性不该依赖解析器恰好看不懂某个语法。
            assertFalse(SqlValidateUtil.stripStringLiterals("select x $tag$/*!*/$tag$", PG).contains("/*!"),
                    "带标签的美元引用没被屏蔽");
        }

        @Test
        @DisplayName("★ MySQL 下不能把 $...$ 当字符串 —— $ 是合法的标识符字符")
        void mysqlMustNotTreatDollarAsQuote() {
            // 如果在 MySQL 下也认 $$ 引用，下面的 SQL 会被从头吃到尾，
            // 后面的危险操作就被"屏蔽"掉了 —— 那是一个漏检，比误杀严重得多
            assertRejected("select col$name from t where 1=1 /*!*/");
            assertFalse(SqlDialect.MYSQL.supportsDollarQuoting());
            assertTrue(SqlDialect.POSTGRESQL.supportsDollarQuoting());
        }

        @Test
        @DisplayName("PG 的参数占位符 $1 不能被当成引号开始")
        void postgresqlParameterPlaceholderShouldNotSwallowTheRest() {
            // $1 是 PG 的参数占位符。把它当引号开始会一路吃到结尾，
            // 后面的 /*! 就查不出来了 —— 那是一个漏检，比误杀严重得多。
            String stripped = SqlValidateUtil.stripStringLiterals("select $1 /*!*/ 1", PG);
            assertTrue(stripped.contains("/*!"),
                    "$1 被当成了引号开始，把后面的内容吞掉了：" + stripped);
        }
    }
}
