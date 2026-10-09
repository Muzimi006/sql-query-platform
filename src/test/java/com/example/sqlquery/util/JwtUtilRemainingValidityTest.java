package com.example.sqlquery.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JwtUtil#getRemainingValidity} 的行为。
 *
 * <p>守的失败模式：把 token 加进黑名单时用的 TTL 和 token 自己的生命周期<b>脱钩</b>。
 * 原来的实现写死 {@code Duration.ofDays(7)}，看起来"和 token 有效期一样"，
 * 其实只在一个特定时刻成立 —— <b>刚签发就登出</b>的那一次。
 * 一个已经用了 6 天的 token，登出后还会在 Redis 里再躺 7 天，
 * 而它本身 1 天后就过期了：多出来的 6 天是永远不会被再查一次的垃圾键。
 *
 * <p>这个 bug 用接口测<b>测不出来</b>：刚登录就登出时，
 * "写死 7 天"和"剩余 7 天"的数值完全一样。所以必须直接对换算逻辑下断言。
 */
class JwtUtilRemainingValidityTest {

    private static final String SECRET = "UnitTestSecretKeyAtLeast32BytesLong!!";

    private JwtUtil jwtUtil() {
        JwtUtil jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        return jwtUtil;
    }

    /** 造一个"签发于 issuedAgo 之前、有效期 totalValidity"的 token。 */
    private DecodedJWT tokenIssuedAgo(Duration issuedAgo, Duration totalValidity) {
        long now = System.currentTimeMillis();
        String token = JWT.create()
                .withClaim("userId", 1L)
                .withClaim("role", "USER")
                .withIssuedAt(new Date(now - issuedAgo.toMillis()))
                .withExpiresAt(new Date(now - issuedAgo.toMillis() + totalValidity.toMillis()))
                .sign(Algorithm.HMAC256(SECRET));
        // 用不带过期校验的方式解析：这里要测的是换算，不是验签
        return JWT.decode(token);
    }

    @Test
    void freshTokenRemainingShouldBeNearlyFullValidity() {
        JwtUtil util = jwtUtil();
        DecodedJWT jwt = tokenIssuedAgo(Duration.ZERO, Duration.ofDays(7));

        Duration remaining = util.getRemainingValidity(jwt);

        // 允许几秒误差（测试执行本身要花时间）
        assertTrue(Math.abs(remaining.toSeconds() - Duration.ofDays(7).toSeconds()) < 10,
                "刚签发的 token 剩余有效期应该接近满值，实际 " + remaining.toSeconds() + " 秒");
    }

    @Test
    void agedTokenRemainingMustBeMuchShorterThanFullValidity() {
        JwtUtil util = jwtUtil();
        // 签发于 6 天前，总有效期 7 天 —— 真实世界里最常见的那种 token
        DecodedJWT jwt = tokenIssuedAgo(Duration.ofDays(6), Duration.ofDays(7));

        Duration remaining = util.getRemainingValidity(jwt);

        // 关键断言：剩余必须是「1 天上下」，而不是「7 天」。
        // 写死 Duration.ofDays(7) 的实现会在这里失败。
        //
        // 用分钟而不是小时比较：JWT 的 exp 精度是秒，签发时间里的毫秒会被截掉，
        // 剩余时间会是 23 小时 59 分 59 秒这种值 —— 拿 toHours() 比会得到 23，
        // 差的这一点是精度问题，不是实现问题。
        long remainingMinutes = remaining.toMinutes();
        assertTrue(Math.abs(remainingMinutes - Duration.ofDays(1).toMinutes()) < 10,
                "已经用了 6 天的 token，剩余有效期应该约 24 小时，实际 " + remainingMinutes + " 分钟");
        assertTrue(remaining.compareTo(Duration.ofDays(7)) < 0,
                "剩余有效期不可能等于满有效期 —— 那说明 TTL 是和 token 脱钩的写死值");
    }

    @Test
    void expiredTokenShouldReportNonPositiveRemaining() {
        JwtUtil util = jwtUtil();
        DecodedJWT jwt = tokenIssuedAgo(Duration.ofDays(8), Duration.ofDays(7));

        Duration remaining = util.getRemainingValidity(jwt);

        assertTrue(remaining.isNegative() || remaining.isZero(),
                "已过期的 token 剩余有效期必须是非正数，实际 " + remaining.toSeconds() + " 秒");
    }

    @Test
    void tokenWithoutExpiryShouldReportZero() {
        JwtUtil util = jwtUtil();
        String token = JWT.create().withClaim("userId", 1L).sign(Algorithm.HMAC256(SECRET));

        assertEquals(Duration.ZERO, util.getRemainingValidity(JWT.decode(token)),
                "没有 exp 的 token 没有可用的剩余有效期，必须返回 0 让调用方放弃拉黑");
    }
}
