package com.example.sqlquery.controller;

import com.example.sqlquery.dto.LoginDTO;
import com.example.sqlquery.service.TokenBlacklistService;
import com.example.sqlquery.service.UserService;
import com.example.sqlquery.util.JwtUtil;
import com.example.sqlquery.util.RateLimitUtil;
import com.example.sqlquery.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录限流的**分桶键**。
 *
 * <h2>它补的是一个真实的绕过口子</h2>
 *
 * <p>账号维度的限流键原来是直接拼原始用户名的：
 *
 * <pre>
 * "rate:login:username:" + dto.getUsername()
 * </pre>
 *
 * <p>而这两个存储对大小写的态度**正好相反**：
 *
 * <table border="1">
 *   <tr><th></th><th>大小写</th><th>结果</th></tr>
 *   <tr><td>MySQL（{@code utf8mb4_unicode_ci}）</td><td><b>不敏感</b></td>
 *       <td>{@code admin} / {@code Admin} / {@code ADMIN} 是<b>同一行用户</b></td></tr>
 *   <tr><td>Redis 的 key</td><td><b>敏感</b></td>
 *       <td>三个写法是<b>三个不同的桶</b></td></tr>
 * </table>
 *
 * <p>于是「10 次 / 分钟」的账号维度限流，只要换个大小写就能重新拿满额度 ——
 * 撞库的人不需要代理池，改一个字母就够了。
 *
 * <p>这条用例断言的就是：<b>归一化之后的键必须与库里的匹配口径一致</b>（小写、去首尾空格）。
 * 它是纯单元测试（不起 Spring 上下文），因为要验的是「传给限流器的那个字符串」。
 */
class AuthControllerLoginRateLimitTest {

    private RateLimitUtil rateLimitUtil;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        rateLimitUtil = mock(RateLimitUtil.class);
        when(rateLimitUtil.tryAcquireSlidingWindow(anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(true);

        UserService userService = mock(UserService.class);
        UserVO vo = new UserVO();
        vo.setId(1L);
        vo.setUsername("admin");
        vo.setRole("ADMIN");
        when(userService.login(anyString(), anyString())).thenReturn(vo);

        JwtUtil jwtUtil = mock(JwtUtil.class);
        when(jwtUtil.generateToken(any(), anyString(), anyString())).thenReturn("token");

        controller = new AuthController(jwtUtil, userService, mock(TokenBlacklistService.class), rateLimitUtil);
    }

    private List<String> capturedKeys(String username) {
        // ⚠️ 同一个测试里可能调用多次（要比较不同写法的键），而 mock 的调用记录是累积的：
        //    不清掉上一次的，第二次 verify(times(2)) 就会看到 4 次调用而失败。
        org.mockito.Mockito.clearInvocations(rateLimitUtil);

        LoginDTO dto = new LoginDTO();
        dto.setUsername(username);
        dto.setPassword("Probe12345!");

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.0.2.9");

        controller.login(dto, request);

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(rateLimitUtil, times(2))
                .tryAcquireSlidingWindow(keys.capture(), anyInt(), anyInt(), anyString());
        return keys.getAllValues();
    }

    private String accountKey(List<String> keys) {
        return keys.stream()
                .filter(k -> k.startsWith("rate:login:username:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有取到账号维度的限流键：" + keys));
    }

    @Test
    @DisplayName("⭐ 账号维度必须大小写归一：admin / Admin / ADMIN 不能再是三个桶")
    void accountDimensionMustBeCaseInsensitive() {
        String lower = accountKey(capturedKeys("admin"));
        String upper = accountKey(capturedKeys("ADMIN"));
        String mixed = accountKey(capturedKeys("AdMiN"));

        assertEquals("rate:login:username:admin", lower, "小写用户名应当原样落进键里");
        assertEquals(lower, upper, "大写写法必须落到**同一个桶**，否则限流可以被大小写绕过");
        assertEquals(lower, mixed, "混合大小写同理");
    }

    @Test
    @DisplayName("首尾空格也要归一：用户名带空格不能变出一个新桶")
    void accountDimensionMustTrimWhitespace() {
        assertEquals(accountKey(capturedKeys("admin")), accountKey(capturedKeys("  admin  ")));
        assertTrue(accountKey(capturedKeys("\tADMIN ")).equals("rate:login:username:admin"),
                "制表符与空格都要被去掉");
    }

    @Test
    @DisplayName("两个维度都要生效：IP 桶与账号桶是两个不同的键")
    void bothDimensionsMustBeUsed() {
        List<String> keys = capturedKeys("admin");
        assertEquals(2, keys.size(), "一次登录应当同时占用 IP 与账号两个桶");
        assertTrue(keys.stream().anyMatch(k -> k.startsWith("rate:login:ip:")), "缺 IP 维度：" + keys);
        assertTrue(keys.stream().anyMatch(k -> k.startsWith("rate:login:username:")), "缺账号维度：" + keys);
    }
}