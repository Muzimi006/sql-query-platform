package com.example.sqlquery.service;

import com.example.sqlquery.common.UserRole;
import com.example.sqlquery.config.SqlPermissionProperties;
import com.example.sqlquery.util.SqlType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「角色 → 允许的语句类型」这条链路。
 *
 * <p>它守的是两个曾经真实存在的缺陷：
 * <ol>
 *   <li><b>USER 和 ADMIN 配的是同一套权限</b> —— 配置里写了两个角色，
 *       但"按角色区分"这一层实际上没落地，是个摆设；</li>
 *   <li><b>未知角色兜底成 SELECT</b> —— 角色被写错一个字母时用户静默获得读权限；
 *       而传 {@code null} 时更糟：不可变 {@code Map.of(...)} 不接受 null 键，直接 NPE，
 *       最后被全局处理器翻译成「系统异常」，权限问题伪装成了服务端故障。</li>
 * </ol>
 */
class SqlPermissionServiceTest {

    private final SqlPermissionProperties properties = new SqlPermissionProperties();
    private final SqlPermissionService service = new SqlPermissionService(properties);

    @Test
    @DisplayName("USER 只能 SELECT")
    void userShouldOnlySelect() {
        assertEquals(Set.of(SqlType.SELECT), service.getAllowedTypes(UserRole.USER));
    }

    @Test
    @DisplayName("ADMIN 与 USER 的权限必须真的不同")
    void adminMustDifferFromUser() {
        Set<SqlType> admin = service.getAllowedTypes(UserRole.ADMIN);

        assertNotEquals(service.getAllowedTypes(UserRole.USER), admin,
                "两个角色配成一样，等于『按角色区分』没有落地");
        assertTrue(admin.contains(SqlType.SELECT));
        assertTrue(admin.contains(SqlType.UPDATE));
    }

    @Test
    @DisplayName("DDL 默认不在任何角色里")
    void ddlShouldNotBeGrantedByDefault() {
        assertFalse(service.getAllowedTypes(UserRole.USER).contains(SqlType.DDL));
        assertFalse(service.getAllowedTypes(UserRole.ADMIN).contains(SqlType.DDL),
                "DROP TABLE 这类语句在本平台没有正当用途，要开必须显式配置");
    }

    @Test
    @DisplayName("未知角色 / null / 空白：一律全拒，且不能抛 NPE")
    void unknownOrNullRoleShouldBeFailClosed() {
        assertTrue(service.getAllowedTypes("UNKNOWN").isEmpty());
        assertTrue(service.getAllowedTypes(null).isEmpty());
        assertTrue(service.getAllowedTypes("").isEmpty());
        assertTrue(service.getAllowedTypes("   ").isEmpty());
    }

    @Test
    @DisplayName("角色名大小写不敏感，前后空格也不影响")
    void roleShouldBeCaseInsensitive() {
        assertEquals(service.getAllowedTypes("USER"), service.getAllowedTypes("user"));
        assertEquals(service.getAllowedTypes("USER"), service.getAllowedTypes("  User  "));
    }

    @Test
    @DisplayName("配置了 role-whitelist 就整体替换默认值")
    void configuredWhitelistShouldReplaceDefaults() {
        Map<String, Set<SqlType>> configured = new LinkedHashMap<>();
        // 只配一个自定义角色：这意味着 USER 这个角色在配置里"不存在"了，
        // 按 fail-closed 应当变成全拒 —— 这是"替换"而不是"合并"的直接后果
        configured.put("AUDITOR", Set.of(SqlType.SELECT));
        properties.setRoleWhitelist(configured);

        assertEquals(Set.of(SqlType.SELECT), service.getAllowedTypes("AUDITOR"));
        assertTrue(service.getAllowedTypes(UserRole.USER).isEmpty(),
                "配置是整体替换：没写在配置里的角色应当全拒，而不是回退到默认值");
    }

    @Test
    @DisplayName("返回的集合不可变，调用方改不动全局配置")
    void returnedSetShouldBeImmutable() {
        Set<SqlType> allowed = service.getAllowedTypes(UserRole.ADMIN);
        assertThrows(UnsupportedOperationException.class, () -> allowed.add(SqlType.DDL));
    }

    // ------------------------------------------------------------------
    // 可申请类型（工单链路）—— 它和角色白名单是两套东西
    // ------------------------------------------------------------------

    @Test
    @DisplayName("可申请类型必须独立于角色白名单：否则 USER 连申请写操作都做不到")
    void reviewableTypesMustBeIndependentOfRoleWhitelist() {
        Set<SqlType> reviewable = service.getReviewableTypes();

        // 前提：USER 本来就没有写权限 —— 没有这个前提，工单系统就没有存在意义
        assertFalse(service.getAllowedTypes(UserRole.USER).contains(SqlType.UPDATE),
                "USER 不该有直接执行 UPDATE 的权限");

        // 而工单必须受理它。这两条放在一起才是完整的设计意图：
        // 「不能直接改」和「不能申请改」是两回事，工单存在的理由正是后者为真
        assertTrue(reviewable.contains(SqlType.UPDATE),
                "工单必须受理 USER 的写操作申请，否则工单系统只是个摆设");
        assertTrue(reviewable.contains(SqlType.INSERT));
        assertTrue(reviewable.contains(SqlType.DELETE));
    }

    @Test
    @DisplayName("可申请类型默认不含 SELECT，也不含 DDL")
    void reviewableTypesShouldExcludeSelectAndDdl() {
        Set<SqlType> reviewable = service.getReviewableTypes();

        assertFalse(reviewable.contains(SqlType.SELECT),
                "查询不需要审批：用户本来就有直接执行的权限，让它排队等审批是纯粹的功能倒退");
        assertFalse(reviewable.contains(SqlType.DDL),
                "DDL 应走 DBA 流程，不该默认通过业务工单开放");
    }

    @Test
    @DisplayName("可申请类型配成空集合时全拒（fail-closed），而不是放行")
    void emptyReviewableTypesShouldFailClosed() {
        properties.setReviewableTypes(Set.of());

        // 表现为「谁都不能提交工单」这个显眼的功能失效，
        // 而不是「谁都能提交任意语句」这个静默的越权
        assertTrue(service.getReviewableTypes().isEmpty());
    }

    @Test
    @DisplayName("可申请类型同样不可变")
    void reviewableTypesShouldBeImmutable() {
        assertThrows(UnsupportedOperationException.class,
                () -> service.getReviewableTypes().add(SqlType.DDL));
    }
}
