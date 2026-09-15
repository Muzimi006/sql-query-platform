package com.example.sqlquery.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.example.sqlquery.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
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
    public String generateToken(Long userId, String username) {
        return JWT.create()
                .withClaim("userId", userId)
                .withClaim("username", username)
                .withExpiresAt(new Date(System.currentTimeMillis() + expireDays*24L*60*60*1000))
                .sign(Algorithm.HMAC256(secret));
    }

    /**
     * 验证 Token，解析成功返回 DecodedJWT
     */
    public DecodedJWT verifyToken(String token) {
        return JWT.require(Algorithm.HMAC256(secret))
                .build()
                .verify(token);
    }

    /**
     * 从 Token 中获取 userId
     */
    public Long getUserId(String token) {
        return verifyToken(token).getClaim("userId").asLong();
    }

    /**
     * 从 Token 中获取 username
     */
    public String getUsername(String token) {
        return verifyToken(token).getClaim("username").asString();
    }

    public Long getUserIdFromRequest(HttpServletRequest request){
        String authHeader = request.getHeader("Authorization");
        if(authHeader == null || !authHeader.startsWith("Bearer ") || authHeader.length() <= 7){
            throw new BusinessException("未登录");
        }
        String token = authHeader.substring(7);
        return getUserId(token);
    }
}
