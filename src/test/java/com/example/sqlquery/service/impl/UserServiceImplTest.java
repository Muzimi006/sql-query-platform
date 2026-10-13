package com.example.sqlquery.service.impl;

import com.example.sqlquery.entity.User;
import com.example.sqlquery.exception.BusinessException;
import com.example.sqlquery.service.AdminRegistrationPolicy;
import com.example.sqlquery.vo.UserVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import com.example.sqlquery.metrics.SqlPlatformMetrics;

/**
 * 注册 / 登录 / 改密码这条链路的测试。
 *
 * <p>守的不是"方法写得对不对"，而是 <b>{@link PasswordEncoder} 这个 API 的用法不能被写错</b>：
 * <ul>
 *   <li>{@code matches(原始密码, 加密后密码)} <b>参数顺序写反不会报错，只会永远返回 false</b> ——
 *       表现是"所有用户都登不上"，而异常提示是"用户名不存在或密码错误"，排查时会先怀疑数据库。</li>
 *   <li>BCrypt 每次 {@code encode} 都带随机盐，<b>同一个密码两次结果不同</b> ——
 *       密码永远不能用 {@code equals} 比较，必须用 {@code matches}。</li>
 *   <li>落库的必须是密文，<b>明文绝不能进数据库</b>；密码错误时也不能顺手把用户改了。</li>
 * </ul>
 *
 * <p>密码编码器用<b>真实实现</b>而不是 mock —— 把它 mock 掉，等于把要验证的契约一起 mock 掉了。
 * 只把数据访问（{@code getByUsername} / {@code save} / {@code getById} / {@code updateById}）打桩，
 * 这样这条链路的密码逻辑是真的在跑。
 */
class UserServiceImplTest {

    private PasswordEncoder passwordEncoder;
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder();
        // 本类只验密码链路，角色策略用 mock：不配邀请码时它返回 USER
        AdminRegistrationPolicy policy = mock(AdminRegistrationPolicy.class);
        doReturn("USER").when(policy).resolveRole(any());
        // 本类不验重哈希链路（那由 PasswordRehashOnLoginTest 覆盖），指标用一个真实注册表即可
        userService = spy(new UserServiceImpl(passwordEncoder, policy,
                new com.example.sqlquery.metrics.SqlPlatformMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry())));
    }

    @Test
    void registerShouldStoreEncodedPasswordNeverPlaintext() {
        doReturn(null).when(userService).getByUsername(anyString());
        doReturn(true).when(userService).save(any(User.class));

        userService.register("alice", "123456", "Alice", null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).save(captor.capture());
        User saved = captor.getValue();

        assertNotEquals("123456", saved.getPassword(), "明文密码绝不能落库");
        assertTrue(passwordEncoder.matches("123456", saved.getPassword()),
                "存进去的必须是能用 matches 校验通过的密文");
        assertEquals("USER", saved.getRole(), "不带邀请码的普通注册应该是 USER 角色");
    }

    @Test
    void registerShouldRejectDuplicateUsername() {
        doReturn(new User()).when(userService).getByUsername(anyString());

        assertThrows(BusinessException.class, () -> userService.register("alice", "123456", "Alice", null));
        verify(userService, never()).save(any(User.class));
    }

    @Test
    void loginShouldSucceedWithCorrectPassword() {
        doReturn(userWithPassword("123456")).when(userService).getByUsername(anyString());

        UserVO vo = userService.login("alice", "123456");

        assertEquals("alice", vo.getUsername());
    }

    @Test
    void loginShouldFailWithWrongPassword() {
        doReturn(userWithPassword("123456")).when(userService).getByUsername(anyString());

        assertThrows(BusinessException.class, () -> userService.login("alice", "654321"));
    }

    @Test
    void loginShouldFailWhenUserNotFound() {
        doReturn(null).when(userService).getByUsername(anyString());

        assertThrows(BusinessException.class, () -> userService.login("nobody", "123456"));
    }

    @Test
    void loginShouldRejectDisabledAccount() {
        // ⚠️ 这条用例补的是一个**真实存在过的缺口**：登录以前只比密码，完全不看 status。
        //    也就是说「停用一个账号」在代码上毫无作用 —— 运维把 user.status 改成 0 之后，
        //    本人照样能登录、照样拿到 token（密码对就行）。
        User disabled = userWithPassword("123456");
        disabled.setStatus(0);
        doReturn(disabled).when(userService).getByUsername(anyString());

        BusinessException e = assertThrows(BusinessException.class,
                () -> userService.login("alice", "123456"),
                "密码正确但账号已停用 —— 必须挡住");
        assertTrue(e.getMessage().contains("停用"), "提示要说清是「停用」而不是「密码错误」：" + e.getMessage());
    }

    @Test
    void loginShouldStillWorkWhenStatusIsNull() {
        // ⚠️ 边界：只认「明确等于 0」。status 为 NULL 的历史行必须按启用处理 ——
        //    否则一次上线会把所有没填过 status 的老账号锁在门外。
        User legacy = userWithPassword("123456");
        legacy.setStatus(null);
        doReturn(legacy).when(userService).getByUsername(anyString());

        assertEquals("alice", userService.login("alice", "123456").getUsername());
    }

    @Test
    void updatePasswordShouldVerifyOldPasswordBeforeWriting() {
        doReturn(userWithPassword("old123")).when(userService).getById(anyLong());
        doReturn(true).when(userService).updateById(any(User.class));

        userService.updatePassword(1L, "old123", "new456");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).updateById(captor.capture());
        assertTrue(passwordEncoder.matches("new456", captor.getValue().getPassword()),
                "新密码必须以密文落库");
        assertFalse(passwordEncoder.matches("old123", captor.getValue().getPassword()),
                "落库的不应该还是旧密码");
    }

    @Test
    void updatePasswordShouldRejectWrongOldPassword() {
        doReturn(userWithPassword("old123")).when(userService).getById(anyLong());

        assertThrows(BusinessException.class, () -> userService.updatePassword(1L, "wrong", "new456"));
        verify(userService, never()).updateById(any(User.class));
    }

    @Test
    void bcryptShouldUseRandomSaltSoTwoHashesDiffer() {
        // 这就是"密码不能用 equals 比较"的原因：同一个明文两次 encode 结果不同
        assertNotEquals(passwordEncoder.encode("123456"), passwordEncoder.encode("123456"));
    }

    private User userWithPassword(String rawPassword) {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setRole("USER");
        user.setStatus(1);
        return user;
    }
}
