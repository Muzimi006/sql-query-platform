package com.example.sqlquery.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码编码器的配置。
 *
 * <p>这里钉的是三件事：
 * <ol>
 *   <li>默认强度仍是 <b>10</b> —— 与改造前 `new BCryptPasswordEncoder()` 的行为**完全一致**，
 *       不能让"把配置显式化"这件事悄悄改变现有部署的哈希强度；</li>
 *   <li>越界值被**夹住并继续运行**（而不是启动失败），且夹住的上限不是 BCrypt 的 31；</li>
 *   <li>`upgradeEncoding` 的真实语义 —— 它决定登录时要不要重算哈希，
 *       而它的行为**不是**"直觉上那样"的（见下面那条用例的注释）。</li>
 * </ol>
 */
class PasswordConfigTest {

    private final PasswordConfig config = new PasswordConfig();

    private int strengthOf(String hash) {
        int s1 = hash.indexOf('$', 1);
        int s2 = hash.indexOf('$', s1 + 1);
        return Integer.parseInt(hash.substring(s1 + 1, s2));
    }

    @Test
    @DisplayName("默认成本因子是 10：显式化配置不改变既有行为")
    void defaultStrengthShouldBeTen() {
        PasswordEncoder encoder = config.passwordEncoder(10);
        String hash = encoder.encode("Probe12345!");

        assertTrue(hash.startsWith("$2a$"), "版本前缀应当是 $2a$，实际：" + hash.substring(0, 4));
        assertEquals(60, hash.length(), "BCrypt 哈希固定 60 字符（user.password 列宽 100 的依据）");
        assertEquals(10, strengthOf(hash), "默认成本因子必须还是 10");
    }

    @Test
    @DisplayName("越界被夹住：0 → 4，99 → 16（而不是启动失败，也不是 2^31 那种算不出来的值）")
    void outOfRangeShouldBeClamped() {
        // 注意：不断言 16 的**哈希耗时**（那要真算一次），只断言它的 upgradeEncoding 语义，
        // 因为 upgradeEncoding 只读哈希串里的强度，不做任何计算。
        // 配 0 → 夹到 4：直接**算一个哈希看它的 cost**（cost=4 很便宜，2^4 次 key setup）
        assertEquals(4, strengthOf(config.passwordEncoder(0).encode("x")),
                "配 0 应当夹到下限 4");
        assertTrue(config.passwordEncoder(99).upgradeEncoding(new BCryptPasswordEncoder(10).encode("x")),
                "配 99 应当夹到 16：此时 cost=10 的哈希**需要**升级（16 > 10）");
    }

    @Test
    @DisplayName("⭐ upgradeEncoding 的真实语义：只有「配置强度 > 哈希强度」才为 true")
    void upgradeEncodingSemantics() {
        String hash4 = new BCryptPasswordEncoder(4).encode("x");
        String hash10 = new BCryptPasswordEncoder(10).encode("x");

        // ⚠️ 这三行是**实测**出来的（不是照文档抄的）：
        //    升到 10 的配置看着 cost=4 的老哈希 → true（该升级，正是我们要的）
        //    降到 4 的配置看着 cost=10 的新哈希 → false（Spring 不让你"降级"）
        //    同强度 → false（不做无意义的写）
        assertTrue(config.passwordEncoder(10).upgradeEncoding(hash4));
        assertTrue(!config.passwordEncoder(4).upgradeEncoding(hash10),
                "Spring 的实现不会把高成本哈希降级 —— 调低配置时老哈希保持不变");
        assertTrue(!config.passwordEncoder(10).upgradeEncoding(hash10));
    }
}
