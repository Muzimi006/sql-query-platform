package com.example.sqlquery.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码哈希（用户登录密码）。

 * <h2>⚠️ 这个类以前只有一行 `new BCryptPasswordEncoder()`</h2>

 * <p>那一行本身没错，但它把两件本该公司知道的事**交给了库的默认值**：
 *
 * <ul>
 *   <li><b>算法版本</b>（`$2a$`）与 <b>成本因子/轮次</b>（`cost=10`，即 2^10 = 1024 次 key setup）——
 *       全部来自 Spring Security 的默认值，**代码里一个字都没写**；</li>
 *   <li>于是"我们到底用了多少轮"这个问题，**只能靠读哈希串**（`$2a$10$…`）或者翻文档才能回答，
 *       而想调整它的人得先知道"要改的是这一行"。</li>
 * </ul>

 * <p>现在把它变成显式配置：`platform.password.bcrypt-strength`（默认 10，与之前的默认值一致，
 * 所以**现有部署行为不变**），并在启动时把生效值打进日志。

 * <h2>为什么上限是 16（而不是 BCrypt 允许的 31）</h2>

 * <p>成本因子每加 1，单次哈希耗时翻倍。10 大约几毫秒、12 约几十毫秒、16 已经是百毫秒级，
 * 而 **31 是"算不出来"**（2^21 倍于 10）。这里把它夹到 [4, 16] 并打 WARN，理由：
 *
 * <p><b>登录接口本身就是一个"故意很慢"的操作</b>（见 {@code AuthController} 的注释：
 * 限流必须放在校验密码之前，因为 BCrypt 很慢）—— 把它调到 2^20 以上，等于给自己造一个
 * **CPU 耗尽型拒绝服务**：几个并发登录就能把 CPU 打满，而这和"密码太弱"是两件事。
 * 真要更强的抗暴力破解，方向是换 Argon2/scrypt（内存困难），不是把 BCrypt 的轮次调爆。

 * <h2>⚠️ 调高之后：老哈希不会自动变</h2>

 * <p>BCrypt 的 cost **写在每条哈希串里**，`matches()` 读的是哈希串里的那个值 ——
 * 所以调高成本因子**不会**让老用户登录失败，但也**不会**让他们的哈希自动升级。
 * 升级发生在**登录成功那一刻**（见 {@code UserServiceImpl.login} 里的 rehash），
 * 也就是：**渐进升级**，进度看 `password_rehash_total{outcome="success"}`。
 */
@Slf4j
@Configuration
public class PasswordConfig {

    /** BCrypt 允许的最小成本因子。 */
    static final int MIN_STRENGTH = 4;

    /**
     * 本项目允许的最大成本因子。
     *
     * <p>不是 BCrypt 的上限（它是 31），而是**这个接口能承受的上限**：
     * 登录是每用户都会走的高频路径，2^16 已经约百毫秒级。
     */
    static final int MAX_STRENGTH = 16;

    @Bean
    public PasswordEncoder passwordEncoder(
            @Value("${platform.password.bcrypt-strength:10}") int strength) {

        // 配错方向选择：夹住 + WARN，而不是启动失败。
        // 与限流、保留策略那几处一致 —— 保护性/性能型配置配错时，
        // "用保守值继续跑"比"整个服务起不来"损失更小。
        // 但 WARN 必须打得足够明白：它意味着**你以为的强度并没有生效**。
        int effective = Math.min(MAX_STRENGTH, Math.max(MIN_STRENGTH, strength));
        if (effective != strength) {
            log.warn("platform.password.bcrypt-strength={} 超出允许范围 [{}, {}]，已按 {} 生效。",
                    strength, MIN_STRENGTH, MAX_STRENGTH, effective);
        }

        // 把生效值打出来：这样"我们用了多少轮"不再需要读哈希串或翻文档
        log.info("密码哈希已就绪。算法=BCrypt，版本前缀=$2a$，成本因子={}（2^{} = {} 次 key setup）。"
                + "⚠️ 调高只影响**新**哈希，老哈希在用户下次登录成功时被渐进升级。",
                effective, effective, 1L << effective);

        return new BCryptPasswordEncoder(effective);
    }
}
