package com.example.sqlquery.service.impl;

import com.example.sqlquery.entity.User;
import com.example.sqlquery.exception.BusinessException;
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
        userService = spy(new UserServiceImpl(passwordEncoder));
    }

    @Test
    void registerShouldStoreEncodedPasswordNeverPlaintext() {
        doReturn(null).when(userService).getByUsername(anyString());
        doReturn(true).when(userService).save(any(User.class));

        userService.register("alice", "123456", "Alice");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userService).save(captor.capture());
        User saved = captor.getValue();

        assertNotEquals("123456", saved.getPassword(), "明文密码绝不能落库");
        assertTrue(passwordEncoder.matches("123456", saved.getPassword()),
                "存进去的必须是能用 matches 校验通过的密文");
        assertEquals("USER", saved.getRole(), "新注册用户的角色应固定为 USER");
    }

    @Test
    void registerShouldRejectDuplicateUsername() {
        doReturn(new User()).when(userService).getByUsername(anyString());

        assertThrows(BusinessException.class, () -> userService.register("alice", "123456", "Alice"));
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
