package com.example.sqlquery.util;

import com.example.sqlquery.config.SqlPermissionConfig;
import com.example.sqlquery.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 覆盖 {@link SqlValidateUtil#validate} —— <b>生产代码唯一的 SQL 校验入口</b>。
 *
 * <p>这里曾经测的是另一个只做类型判断、不检查危险 SQL 的方法（已删除）。
 * 测试必须对准生产实际调用的入口，否则会给出虚假的安全感。
 */
class SqlValidateUtilTest {

    private static final Set<SqlType> SELECT_ONLY = Set.of(SqlType.SELECT);

    @Test
    void shouldAllowSelect() {
        assertDoesNotThrow(() -> SqlValidateUtil.validate("select * from user", SELECT_ONLY));
    }

    @Test
    void shouldRejectDelete() {
        assertThrows(BusinessException.class,
                () -> SqlValidateUtil.validate("delete from user", SELECT_ONLY));
    }

    @Test
    void shouldRejectDrop() {
        assertThrows(BusinessException.class,
                () -> SqlValidateUtil.validate("drop table user", SELECT_ONLY));
    }

    @Test
    void shouldRejectUpdateAndInsert() {
        assertThrows(BusinessException.class,
                () -> SqlValidateUtil.validate("update user set name = 'x'", SELECT_ONLY));
        assertThrows(BusinessException.class,
                () -> SqlValidateUtil.validate("insert into user(id) values(1)", SELECT_ONLY));
    }

    @Test
    void shouldRejectMultiStatement() {
        assertThrows(BusinessException.class,
                () -> SqlValidateUtil.validate("select 1; drop table user", SELECT_ONLY));
    }

    @Test
    void shouldRejectDangerousStructure() {
        assertThrows(BusinessException.class,
                () -> SqlValidateUtil.validate("select sleep(1)", SELECT_ONLY));
    }

    @Test
    void shouldRejectSystemSchema() {
        assertThrows(BusinessException.class,
                () -> SqlValidateUtil.validate("select * from information_schema.tables", SELECT_ONLY));
    }

    @Test
    void nullOrEmptyWhitelistShouldRejectEverything() {
        // 白名单缺失时必须全拒，不能因为配置漏了就放行
        assertThrows(BusinessException.class,
                () -> SqlValidateUtil.validate("select * from user", null));
    }

    @Test
    void unknownRoleShouldFallBackToSelectOnly() {
        // 未知角色走兜底分支，结果同样是「只允许 SELECT」
        assertThrows(BusinessException.class,
                () -> SqlValidateUtil.validate("delete from user", SqlPermissionConfig.getByRole("UNKNOWN")));
        assertDoesNotThrow(() ->
                SqlValidateUtil.validate("select * from user", SqlPermissionConfig.getByRole("UNKNOWN")));
    }
}
