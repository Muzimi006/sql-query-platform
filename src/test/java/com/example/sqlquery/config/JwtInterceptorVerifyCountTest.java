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

    /**
     * 签名被改过（payload 不变）也必须拒绝 —— 证明比对的是签名本身。
     *
     * <p>⚠️ <b>改的是签名段的<b>第一个</b>字符，不能改最后一个。</b>
     * 这个用例原先改的是最后一个字符，是一个<b>约 1/16 概率失败的偶发测试</b>：
     * JWT 的签名是 Base64URL 编码且不带 padding，
     * HMAC-SHA256 的 32 字节编码成 43 个字符，最后一个字符只有高 4 位是有效位，
     * 低 2 位在解码时被忽略。而原用例的"篡改"是 {@code 'A' → 'B'} ——
     * 这两个字符的高 4 位都是 {@code 0000}，解码出来的<b>签名字节完全相同</b>，
     * 于是"被篡改的"token 依然是合法的，验签通过，用例失败。
     * 第一个字符承载完整的 6 个有效位，改它一定能改变解码结果。
     */
    @Test
    void shouldRejectTamperedSignature() throws Exception {
        String token = jwtUtil.generateToken(1L, "wulele", "USER");
        String[] parts = token.split("\\.");

        char first = parts[2].charAt(0);
        String flipped = (first == 'A' ? "B" : "A");
        String tampered = parts[0] + "." + parts[1] + "." + flipped + parts[2].substring(1);

        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(requestWithToken(tampered), response, new Object()));
        assertEquals(401, response.getStatus());
    }

    /**
     * 把"最后一个字符不能用来造篡改用例"这个事实本身固定下来。
     *
     * <p>它测的不是业务逻辑，而是<b>测试正确性的前提</b>：
     * 直接比较"改末位字符前后解码出的签名字节"，看它们是否可能完全相同。
     * 只要这个现象存在，用末位字符造的篡改用例就一定会偶发失败。
     *
     * <p>之所以在这里测而不是只写一句注释：注释会过期，而这个前提一旦不成立
     * （比如换成别的 Base64 变体），上面那条用例的理由就需要重新审视。
     */
    @Test
    void lastCharacterOfSignatureHasIgnoredBits() {
        int sameDecodedBytes = 0;

        for (int i = 0; i < 200; i++) {
            // ⚠️ userId 必须每次都不同。
            // 一开始这里写死成 1L，结果 200 次循环其实只测了 1 个样本 ——
            // JWT 的 payload 只包含 userId/username/role/exp，而 exp 精确到秒，
            // 同一秒内用同样的入参签出来的 token <b>完全相同</b>。
            // 那样这条用例自己也变成了 1/16 概率失败，和它想守的那个问题一样。
            String signature = jwtUtil.generateToken((long) i, "wulele", "USER").split("\\.")[2];

            byte[] original = java.util.Base64.getUrlDecoder().decode(signature);
            String lastChar = signature.substring(signature.length() - 1);
            String flippedSignature = signature.substring(0, signature.length() - 1)
                    + ("A".equals(lastChar) ? "B" : "A");
            byte[] flipped = java.util.Base64.getUrlDecoder().decode(flippedSignature);

            if (java.util.Arrays.equals(original, flipped)) {
                sameDecodedBytes++;
            }
        }

        assertTrue(sameDecodedBytes > 0,
                "200 次里一次都没出现『改了末位字符但签名字节不变』，"
                        + "说明 Base64URL 的行为变了 —— shouldRejectTamperedSignature 的注释需要更新");
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
