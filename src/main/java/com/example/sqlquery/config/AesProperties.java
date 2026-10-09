package com.example.sqlquery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据源密码的加密密钥配置。
 *
 * <h2>为什么要「版本化」</h2>
 *
 * <p>只有一个密钥时，<b>轮换密钥 = 所有已加密的数据全部解不开</b>。
 * 结果就是密钥一旦泄露也没法换 —— 只能让用户把每个数据源密码重新填一遍。
 * 一个"换不了"的密钥，实际上等于"永远用同一个密钥"，这跟没有密钥管理是一回事。
 *
 * <p>版本化之后：密文里带上"我是用哪个版本的密钥加密的"，程序同时持有新旧密钥，
 * 于是<b>新数据用新密钥、老数据仍然解得开</b>，轮换变成一个可以慢慢做的过程。
 *
 * <h2>保留 {@code aes.key} 的原因</h2>
 *
 * <p>它是原来就有的配置项（对应环境变量 {@code AES_KEY}）。
 * 保留它意味着<b>现有部署不需要改任何配置</b>：只配 {@code aes.key} 时，
 * 它同时充当「当前版本的密钥」和「历史 ECB 密文的密钥」，行为与改造前完全一致。
 *
 * <h2>轮换怎么操作（三步，每步都可以停下来）</h2>
 *
 * <pre>
 * 第一步 加新密钥、切当前版本（老的仍能解）
 *   aes:
 *     current-key-version: v2
 *     key: &lt;新密钥 K2&gt;
 *     keys:
 *       v1: &lt;老密钥 K1&gt;
 *   此时：新写入的数据是 v2:...；已经存在的 v1:... 仍然解得开
 *
 * 第二步 把存量数据重新加密到 v2
 *   aes.reencrypt-on-startup: true      # 启动时跑一次，幂等，跑完改回 false
 *
 * 第三步 确认没有 v1 的残留后，删掉 keys.v1 和 legacy-key-version
 *   （这一步是可选的收尾，不做也不影响正确性）
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "aes")
public class AesProperties {

    /**
     * 当前使用的密钥版本号。
     *
     * <p>加密时用它，并把版本号写进密文前缀。
     */
    private String currentKeyVersion = "v1";

    /**
     * 当前版本的密钥（保持与改造前一致的配置项）。
     *
     * <p>只在"这个版本没有在 {@link #keys} 里显式配置"时作为它的取值来源。
     * 也就是说 {@code keys} 优先，{@code key} 是兼容用的兜底。
     */
    private String key;

    /**
     * 历史密钥：版本号 → 密钥。只用于<b>解密</b>，加密永远用 {@link #currentKeyVersion}。
     *
     * <p>用 {@link LinkedHashMap} 是因为 Spring Boot 绑定时需要往 map 里 put，
     * 不可变实现会在绑定时直接抛异常。
     */
    private Map<String, String> keys = new LinkedHashMap<>();

    /**
     * 历史 ECB 密文（没有版本前缀的那种）用哪个版本的密钥解。
     *
     * <p>默认 {@code v1}：本项目引入 GCM 时的密钥版本就是 v1，
     * 而在那之前产生的密文用的正是同一个密钥。
     */
    private String legacyKeyVersion = "v1";

    public String getCurrentKeyVersion() {
        return currentKeyVersion;
    }

    public void setCurrentKeyVersion(String currentKeyVersion) {
        this.currentKeyVersion = currentKeyVersion;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Map<String, String> getKeys() {
        return keys;
    }

    public void setKeys(Map<String, String> keys) {
        this.keys = keys;
    }

    public String getLegacyKeyVersion() {
        return legacyKeyVersion;
    }

    public void setLegacyKeyVersion(String legacyKeyVersion) {
        this.legacyKeyVersion = legacyKeyVersion;
    }
}
