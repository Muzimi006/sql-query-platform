package com.example.sqlquery.sql;

import com.example.sqlquery.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 方言枚举本身。
 *
 * <p>它守的是"加一个新方言时该补的东西有没有补齐"。这类遗漏的特点是不会报错 ——
 * 只会表现为某个数据库上"检查静默失效"或者"连接串拼错"。
 */
class SqlDialectTest {

    @Test
    @DisplayName("★ 每个方言的六件事都必须填齐")
    void everyDialectMustBeFullyConfigured() {
        for (SqlDialect dialect : SqlDialect.values()) {
            assertTrue(dialect.displayName() != null && !dialect.displayName().isBlank(),
                    dialect + " 缺少显示名");
            assertTrue(dialect.driverClassName().startsWith("org.") || dialect.driverClassName().startsWith("com."),
                    dialect + " 的驱动类名不像一个真实的驱动：" + dialect.driverClassName());
            assertTrue(dialect.defaultPort() > 0 && dialect.defaultPort() < 65536,
                    dialect + " 的默认端口不合法");
            assertFalse(dialect.dangerousFunctions().isEmpty(),
                    dialect + " 的危险函数名单是空的 —— 那等于这个方言上完全没有函数检查");
            assertFalse(dialect.systemSchemas().isEmpty(),
                    dialect + " 的系统库名单是空的 —— 那等于这个方言上完全没有系统库检查");
        }
    }

    @Test
    @DisplayName("连接串前缀与驱动必须成对正确")
    void driverAndUrlPrefixMustMatch() {
        assertEquals("com.mysql.cj.jdbc.Driver", SqlDialect.MYSQL.driverClassName());
        assertEquals("org.postgresql.Driver", SqlDialect.POSTGRESQL.driverClassName());

        assertEquals("jdbc:mysql://localhost:3306/db",
                SqlDialect.MYSQL.jdbcUrl("localhost", 3306, "db"));
        assertEquals("jdbc:postgresql://localhost:5432/db",
                SqlDialect.POSTGRESQL.jdbcUrl("localhost", 5432, "db"));
    }

    @Test
    @DisplayName("类型解析：大小写不敏感，支持常见别名")
    void typeResolutionShouldBeLenient() {
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromType("MySQL"));
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromType("mysql"));
        assertEquals(SqlDialect.MYSQL, SqlDialect.fromType("  MYSQL  "));

        assertEquals(SqlDialect.POSTGRESQL, SqlDialect.fromType("PostgreSQL"));
        assertEquals(SqlDialect.POSTGRESQL, SqlDialect.fromType("postgres"));
        assertEquals(SqlDialect.POSTGRESQL, SqlDialect.fromType("pg"));
    }

    @Test
    @DisplayName("★ 认不出来的类型必须报错，报错里要列出支持哪些")
    void unknownTypeShouldFailWithHelpfulMessage() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> SqlDialect.fromType("Oracle"));

        // 报错要能让人自己纠正，而不是只说"不支持"
        assertTrue(e.getMessage().contains("Oracle"), "报错里应带上用户填的值：" + e.getMessage());
        assertTrue(e.getMessage().contains("MySQL"), "报错里应列出支持的方言：" + e.getMessage());
        assertTrue(e.getMessage().contains("PostgreSQL"), "报错里应列出支持的方言：" + e.getMessage());

        assertThrows(BusinessException.class, () -> SqlDialect.fromType(null));
        assertThrows(BusinessException.class, () -> SqlDialect.fromType("   "));
    }

    @Test
    @DisplayName("★ 两个方言的危险函数名单不能有交集")
    void dangerousFunctionSetsMustBeDisjoint() {
        Set<String> mysql = SqlDialect.MYSQL.dangerousFunctions();
        Set<String> pg = SqlDialect.POSTGRESQL.dangerousFunctions();

        for (String name : mysql) {
            assertFalse(pg.contains(name),
                    "「" + name + "」同时出现在两个方言的名单里 —— 说明有人在照抄，"
                            + "而没有去查这个数据库里对应的函数到底叫什么");
        }
    }

    @Test
    @DisplayName("下拉框选项由枚举生成，前端不用维护第二份清单")
    void optionsShouldCoverAllDialects() {
        List<SqlDialect.Option> options = SqlDialect.options();

        assertEquals(SqlDialect.values().length, options.size());
        assertEquals("MYSQL", options.get(0).value());
        assertEquals("MySQL", options.get(0).label());
        assertEquals(3306, options.get(0).defaultPort());
        assertEquals("POSTGRESQL", options.get(1).value());
        assertEquals(5432, options.get(1).defaultPort());
    }

    @Test
    @DisplayName("$$ 引用只对 PostgreSQL 开启")
    void dollarQuotingIsPostgresOnly() {
        assertTrue(SqlDialect.POSTGRESQL.supportsDollarQuoting());
        assertFalse(SqlDialect.MYSQL.supportsDollarQuoting(),
                "MySQL 里 $ 是合法的标识符字符，把 $...$ 当字面量会导致漏检");
    }
}
