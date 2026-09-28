package com.example.sqlquery.config;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.example.sqlquery.common.UserContext;
import com.example.sqlquery.service.TokenBlacklistService;
import com.example.sqlquery.util.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拦截器验签次数的回归测试。
 *
 * <p>背景：改造前拦截器是这样写的 ——
 * <pre>
 * jwtUtil.verifyToken(token);                    // 验签 1 次
 * UserContext.set(jwtUtil.getUserId(token));     // 内部又 verifyToken → 第 2 次
 * UserContext.setRole(jwtUtil.getRole(token));   // 内部又 verifyToken → 第 3 次
 * </pre>
 * 每个请求白验 3 次 HMAC-SHA256。三个方法各自看都「自洽」，
 * 拼在一起才暴露出重复 —— 这类问题逐个方法 review 发现不了，
 * 只有从调用方视角看整条链路才会暴露。
 */
class JwtInterceptorVerifyCountTest {

    private static final String SECRET = "test-secret-for-unit-test-only";

    private JwtUtil jwtUtil;
    private JwtInterceptor interceptor;

    @BeforeEach
    void setUp() {
        // spy 出来的 JwtUtil 走真实逻辑，同时可以统计方法调用次数
        jwtUtil = Mockito.spy(new JwtUtil());
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expireDays", 7);

        TokenBlacklistService blacklistService = Mockito.mock(TokenBlacklistService.class);
        Mockito.when(blacklistService.contains(Mockito.anyString())).thenReturn(false);

        interceptor = new JwtInterceptor(jwtUtil, blacklistService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    /** ★ 核心用例：每个请求只应该验一次签。 */
    @Test
    void shouldVerifySignatureOnlyOncePerRequest() throws Exception {
        String token = jwtUtil.generateToken(1L, "wulele", "USER");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(requestWithToken(token), response, new Object()));

        Mockito.verify(jwtUtil, Mockito.times(1)).verifyToken(token);

        // 顺带确认 claim 确实被正确取出来了
        assertEquals(1L, UserContext.get());
        assertEquals("USER", UserContext.getRole());
    }

    /**
     * ★ 伪造 token：攻击者不知道真实密钥，只能猜一个去签。
     * 这模拟的正是「把 userId 改成 1、role 改成 ADMIN」那种越权尝试。
     */
    @Test
    void shouldRejectTokenSignedWithWrongSecret() throws Exception {
        String forged = JWT.create()
                .withClaim("userId", 1L)
                .withClaim("username", "attacker")
                .withClaim("role", "ADMIN")
                .withExpiresAt(new Date(System.currentTimeMillis() + 3600_000L))
                .sign(Algorithm.HMAC256("attacker-guessed-secret"));

        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(requestWithToken(forged), response, new Object()));
        assertEquals(401, response.getStatus());
    }

    /** 签名被改过（payload 不变）也必须拒绝 —— 证明比对的是签名本身。 */
    @Test
    void shouldRejectTamperedSignature() throws Exception {
        String token = jwtUtil.generateToken(1L, "wulele", "USER");
        String[] parts = token.split("\\.");
        char last = parts[2].charAt(parts[2].length() - 1);
        String flipped = (last == 'A' ? "B" : "A");
        String tampered = parts[0] + "." + parts[1] + "."
                + parts[2].substring(0, parts[2].length() - 1) + flipped;

        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(requestWithToken(tampered), response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void shouldRejectExpiredToken() throws Exception {
        ReflectionTestUtils.setField(jwtUtil, "expireDays", -1);   // 生成一个已经过期的 token
        String expired = jwtUtil.generateToken(1L, "wulele", "USER");

        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(requestWithToken(expired), response, new Object()));
        assertEquals(401, response.getStatus());
    }
}
