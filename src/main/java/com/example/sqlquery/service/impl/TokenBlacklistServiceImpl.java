package com.example.sqlquery.service.impl;

import com.example.sqlquery.metrics.SqlPlatformMetrics;
import com.example.sqlquery.service.TokenBlacklistService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * 登出 Token 黑名单（存 Redis）。
 *
 * <h2>两个刻意的设计决定</h2>
 *
 * <p><b>一、存哈希而不是 token 原文。</b>
 * 原文入库等价于把一把还能用的钥匙抄了一份放在缓存里 —— Redis 一旦被读
 * （备份泄露、误开公网端口、运维直接 GET），攻击者不需要 JWT 密钥就能直接重放登录态。
 * 存 SHA-256 后，从 Redis 里拿到的值无法反推出可用的 token，而查询时对同一个 token
 * 算出的哈希是稳定的，功能完全不受影响。
 *
 * <p><b>二、Redis 不可用时"放行 + 告警"，而不是让全站崩掉。</b>
 * 见 {@link #contains(String)} 的说明。
 */
@Slf4j
@Service
public class TokenBlacklistServiceImpl implements TokenBlacklistService {

    private static final String BLACK_PREFIX = "jwt:blacklist:";

    private final StringRedisTemplate redisTemplate;
    private final SqlPlatformMetrics metrics;

    public TokenBlacklistServiceImpl(StringRedisTemplate redisTemplate,
                                     SqlPlatformMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
    }

    @Override
    public void add(String token, Duration expire) {
        if (expire == null || expire.isNegative() || expire.isZero()) {
            // 已经过期的 token 没有拉黑的必要：验签那一步就会拒绝它
            return;
        }
        try {
            redisTemplate.opsForValue().set(BLACK_PREFIX + sha256(token), "1", expire);
        } catch (Exception e) {
            // ⚠️ 这是一个"本来该生效却没生效"的<b>安全动作</b>，绝不能静默 ——
            // 所以走统一的降级出口：计数器 + 限流的 WARN 日志。
            //
            // 为什么不是 ERROR + 全栈：Redis 挂掉时<b>每个带 token 的请求都会走到这里</b>，
            // 一级 ERROR 会把告警系统打爆，而堆栈对"连不上 Redis"这个已知原因毫无增量信息。
            metrics.recordRedisDegradation("blacklist-write", "expire=" + expire, e);
        }
    }

    /**
     * 判断 token 是否已被拉黑。
     *
     * <p><b>失败路径是显式选择的"放行"（fail-open）</b>，理由：
     * 能走到这里说明 token 的<b>签名已经验过、且尚未过期</b>，它本身是一个合法签发的凭据；
     * 黑名单提供的是"提前失效"这个便利，不是唯一的防线。
     * 反过来如果 Redis 一挂就拒绝所有请求，一次缓存故障会放大成整站不可用
     * （实测复现过：Redis 停掉后除注册登录外所有接口返回「系统异常」）。
     *
     * <p>代价必须说清楚：Redis 不可用期间，<b>已登出的 token 会重新变得可用</b>，
     * 直到 Redis 恢复。所以这里必须留下痕迹 —— 走统一的降级出口
     * （计数器 {@code redis.degradation{reason="blacklist-read"}} + 限流日志），
     * 而不是静默忽略。
     */
    @Override
    public boolean contains(String token) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(BLACK_PREFIX + sha256(token)));
        } catch (Exception e) {
            metrics.recordRedisDegradation("blacklist-read",
                    "已登出的 token 在 Redis 恢复前会重新可用", e);
            return false;
        }
    }

    /** 对 token 做 SHA-256 后转十六进制，作为 Redis 键的一部分。 */
    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境不完整，属于不可恢复错误
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
