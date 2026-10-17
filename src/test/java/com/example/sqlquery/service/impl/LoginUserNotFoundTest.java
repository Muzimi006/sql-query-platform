package com.example.sqlquery.service.impl;

import com.example.sqlquery.entity.User;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.service.AdminRegistrationPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 「用户不存在」和「密码错」这两条路径必须付出**同样的哈希开销**。
 *
 * <h2>它守的是什么</h2>
 *
 * <p>登录失败时的文案是统一的（「用户名不存在或密码错误」），这是为了不让攻击者
 * <b>从错误信息</b>区分"这个用户名存不存在"。但只统一文案是不够的 ——
 * 原来的写法是 {@code if (user == null || !passwordEncoder.matches(...))}，
 * 其中的 {@code ||} 会<b>短路</b>：用户名不存在时根本不做 BCrypt，
 * 于是两条路径的响应时间差一个数量级（本机实测 <b>17ms vs 100ms</b>，成本因子 10），
 * 拿一份用户名字典按耗时分类就能枚举出哪些用户名存在。
 *
 * <p>修法是"用户不存在时也拿一条假哈希比一次"（见 {@code UserServiceImpl#dummyHash()}）。
 *
 * <h2>⚠️ 为什么断言的是"调用次数"而不是"耗时"</h2>
 *
 * <p>写成"两条路径耗时接近"的用例在 CI 上<b>必然偶发</b>：
 * 17ms vs 100ms 的差距在负载高的机器上会被噪声吃掉，于是测试时红时绿，最后被人加 {@code @Disabled}。
 * 这里断言的是<b>确定性的代理指标</b>：两条路径都必须且只调用一次 {@code matches}。
 * 把 {@code passwordEncoder.matches(password, dummyHash())} 那一行删掉，本类立刻变红。
 */
class LoginUserNotFoundTest {

    private static final String RAW_PASSWORD = "Whatever123!";

    private PasswordEncoder passwordEncoder;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        // cost=4：本类验的是"调用了几次 matches"，与强度无关 —— 用最低成本让用例跑得快
        passwordEncoder = spy(new BCryptPasswordEncoder(4));
        AdminRegistrationPolicy policy = mock(AdminRegistrationPolicy.class);
        doReturn("USER").when(policy).resolveRole(any());
        userService = spy(new UserServiceImpl(passwordEncoder, policy,
                new SqlPlatformMetrics(new SimpleMeterRegistry())));
    }

    @Test
    @DisplayName("用户不存在时也要做一次哈希比对（假哈希），且文案与密码错完全一致")
    void loginOfUnknownUserMustStillBurnOneHashComparison() {
        doReturn(null).when(userService).getByUsername(anyString());

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.login("no_such_user_at_all", RAW_PASSWORD));

        assertEquals("用户名不存在或密码错误", e.getMessage(),
                "文案必须与「密码错」那条完全一样 —— 否则等于换了个方式泄露用户名是否存在");
        verify(passwordEncoder, times(1)).matches(eq(RAW_PASSWORD), anyString());
    }

    @Test
    @DisplayName("对照组：密码错时同样只有一次哈希比对 —— 两条路径次数相同才叫修复")
    void loginWithWrongPasswordBurnsExactlyOneHashComparison() {
        doReturn(userWithPassword("Correct123!")).when(userService).getByUsername(anyString());

        assertThrows(BusinessException.class, () -> userService.login("alice", RAW_PASSWORD));

        verify(passwordEncoder, times(1)).matches(eq(RAW_PASSWORD), anyString());
    }

    @Test
    @DisplayName("第二次登录不存在的用户名时，假哈希要复用（不再重新生成）")
    void dummyHashIsGeneratedOnlyOnce() {
        doReturn(null).when(userService).getByUsername(anyString());

        assertThrows(BusinessException.class, () -> userService.login("ghost_one", RAW_PASSWORD));
        assertThrows(BusinessException.class, () -> userService.login("ghost_two", RAW_PASSWORD));

        // encode 只该被调用一次：第一次生成假哈希，之后一直复用
        verify(passwordEncoder, times(1)).encode(anyString());
    }

    @Test
    @DisplayName("⭐ 即使假哈希比对「碰对」返回 true，登录也必须照样抛出同一句文案")
    void dummyHashHitMustNeverGrantLogin() {
        doReturn(null).when(userService).getByUsername(anyString());
        // 刻意让假哈希比对返回 true：模拟「假哈希的明文被猜中」这个极端情况
        doReturn(true).when(passwordEncoder).matches(anyString(), anyString());

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.login("ghost", RAW_PASSWORD));

        assertEquals("用户名不存在或密码错误", e.getMessage(),
                "假哈希的返回值只能用来烧时间，绝不能作为认证依据 —— 一旦被拿去判断就是绕过");
    }

    @Test
    @DisplayName("假哈希的明文必须是随机的：拿任何固定串去比对都不能命中")
    void dummyHashPlaintextMustNotBeAnyFixedLiteral() {
        // 用真实 encoder（本类的 passwordEncoder 是 spy，matches 未被 stub，会委派给真实实现）
        String dummy = userService.dummyHash();

        assertFalse(passwordEncoder.matches("timing-equalizer-placeholder", dummy),
                "旧实现把明文写死在源码里，理论上可以被「碰对」；现在必须是随机 UUID，不能命中任何固定串");
        assertFalse(passwordEncoder.matches("", dummy), "空串也不能命中");
    }

    private User userWithPassword(String rawPassword) {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setStatus(1);
        return user;
    }
}
