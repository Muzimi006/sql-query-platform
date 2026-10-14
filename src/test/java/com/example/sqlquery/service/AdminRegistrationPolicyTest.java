package com.example.sqlquery.service;

import com.example.sqlquery.domain.UserRole;
import com.example.sqlquery.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 注册时角色是怎么定的。
 *
 * <p>原来 {@code user.setRole("USER")} 是写死的，结果是<b>系统里永远不存在管理员</b> ——
 * 权限配置里那个 ADMIN 分支永远到不了。一个"配了但到不了"的分支比没有它更危险，
 * 因为它让人以为权限体系已经落地了。
 *
 * <p>这个类固定三件事：
 * <ol>
 *   <li><b>默认关闭</b>：不配置邀请码时，任何人都注册不出管理员；</li>
 *   <li><b>不可抢注</b>：不采用"某个用户名注册即管理员"，因为那种做法只要没人先注册
 *       那个名字，任何人都能拿到管理员权限；</li>
 *   <li><b>错误要报出来</b>：提交了邀请码但不正确时抛异常，而不是悄悄降级成 USER ——
 *       否则运维会以为自己已经建出管理员账号了。</li>
 * </ol>
 */
class AdminRegistrationPolicyTest {

    private AdminRegistrationPolicy policyWithCode(String code) {
        AdminRegistrationPolicy policy = new AdminRegistrationPolicy();
        ReflectionTestUtils.setField(policy, "adminRegisterCode", code);
        return policy;
    }

    @Test
    void unconfiguredCodeMeansAdminRegistrationIsClosed() {
        AdminRegistrationPolicy policy = policyWithCode("");

        assertEquals(UserRole.USER, policy.resolveRole(null), "普通注册仍然可用");
        assertEquals(UserRole.USER, policy.resolveRole(""));

        assertThrows(BusinessException.class, () -> policy.resolveRole("guess"),
                "环境没开放时提交邀请码应当明确报错，而不是悄悄给一个 USER 账号");
    }

    @Test
    void matchingCodeGrantsAdmin() {
        AdminRegistrationPolicy policy = policyWithCode("s3cret-invite");

        assertEquals(UserRole.ADMIN, policy.resolveRole("s3cret-invite"));
    }

    @Test
    void wrongCodeIsRejectedLoudly() {
        AdminRegistrationPolicy policy = policyWithCode("s3cret-invite");

        assertThrows(BusinessException.class, () -> policy.resolveRole("wrong"));
    }

    @Test
    void omittingCodeStillRegistersNormalUser() {
        AdminRegistrationPolicy policy = policyWithCode("s3cret-invite");

        assertEquals(UserRole.USER, policy.resolveRole(null));
        assertEquals(UserRole.USER, policy.resolveRole("   "));
    }

    @Test
    void blankConfiguredCodeIsTreatedAsNotConfigured() {
        // 配置成空白（比如环境变量没设、或者设成了空串）必须等同于"没配置"，
        // 否则一个空白邀请码就能换到管理员
        AdminRegistrationPolicy policy = policyWithCode("   ");

        // 提交空白的邀请码 == 没提交邀请码，属于普通注册，给 USER 是对的
        assertEquals(UserRole.USER, policy.resolveRole(null));
        assertEquals(UserRole.USER, policy.resolveRole("   "));

        // 但真的提交了一个邀请码、而环境没开放时，必须明确报错 ——
        // 否则提交的人会以为提权成功了
        assertThrows(BusinessException.class, () -> policy.resolveRole("anything"));
    }
}
