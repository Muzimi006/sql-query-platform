package com.example.sqlquery.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expire-days}")
    private int expireDays;

    /**
     * 生成 Token
     */
    public String generateToken(Long userId, String username, String role) {
        return JWT.create()
                .withClaim("userId", userId)
                .withClaim("username", username)
                .withClaim("role", role)
                .withExpiresAt(new Date(System.currentTimeMillis() + expireDays*24L*60*60*1000))
                .sign(Algorithm.HMAC256(secret));
    }

    /**
     * 验签并解析 Token，成功返回 {@link DecodedJWT}。
     *
     * <p><b>一次请求只应该调用这里一次。</b> 验签内部已经做了 Base64URL 解码，
     * 返回对象里包含全部 claim —— 后续取值请用下面的 {@link #getUserId(DecodedJWT)} /
     * {@link #getRole(DecodedJWT)}，不要再传 token 字符串进来重新验一遍。
     */
    public DecodedJWT verifyToken(String token) {
        return JWT.require(Algorithm.HMAC256(secret))
                .build()
                .verify(token);
    }

    /**
     * 从<b>已验签</b>的 Token 中取 userId。
     *
     * <p>⚠️ 这个方法本身<b>不做验签</b>，入参必须是 {@link #verifyToken(String)} 的返回值。
     * 之所以不再接收 token 字符串，就是为了从签名上杜绝「传个字符串进来又验一次」的误用。
     */
    public Long getUserId(DecodedJWT jwt) {
        return jwt.getClaim("userId").asLong();
    }

    /**
     * 从<b>已验签</b>的 Token 中取 role。
     *
     * <p>⚠️ 同 {@link #getUserId(DecodedJWT)}，本方法不做验签。
     */
    public String getRole(DecodedJWT jwt) {
        return jwt.getClaim("role").asString();
    }
}
