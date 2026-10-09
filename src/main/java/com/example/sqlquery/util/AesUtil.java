package com.example.sqlquery.util;

import com.example.sqlquery.config.AesProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.TreeSet;

/**
 * 数据源密码的对称加解密（密钥版本化）。
 *
 * <h2>密文格式</h2>
 *
 * <pre>
 *   {版本号}:{Base64( IV(12 字节) || 密文 + 认证标签 )}
 *   例：v2:3Kk9.....
 *
 *   没有冒号的 → 历史 AES/ECB 密文，用 legacy-key-version 指定的密钥解
 * </pre>
 *
 * <p>版本号与前缀是<b>同一件事</b>：早期的 {@code v1:} 前缀表示"GCM 格式"，
 * 而 v1 恰好也是第一个密钥版本号，所以格式天然向后兼容 ——
 * 老的 {@code v1:...} 密文会被当成"用 v1 密钥加密的 GCM 密文"，语义完全一致。
 *
 * <h2>为什么把版本号明文放在前缀里</h2>
 *
 * <p>版本号不是秘密。把它放在密文里，解密方才能<b>不看数据库、不问配置</b>
 * 就知道该用哪把钥匙 —— 否则就得给每条记录加一个 version 列，
 * 而那意味着一次 schema 变更和同步改所有读写路径。
 *
 * <h2>算法</h2>
 *
 * <ul>
 *   <li>GCM 是认证加密（AEAD）：除机密性外还提供完整性校验，密文被篡改会直接解密失败；</li>
 *   <li>每次加密使用随机 IV（不保密，随密文一起存），相同明文不会产生相同密文，
 *       避免了 ECB 下「相同明文 → 相同密文」导致的模式分析。</li>
 * </ul>
 */
@Slf4j
@Component
public class AesUtil {

    private static final String ALGORITHM = "AES";
    private static final String GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String LEGACY_TRANSFORMATION = "AES/ECB/PKCS5Padding";

    /** 版本号与 Base64 之间用冒号分隔。Base64 的字母表里没有冒号，所以这个分隔符不会歧义。 */
    private static final char VERSION_SEPARATOR = ':';

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AesProperties properties;

    public AesUtil(AesProperties properties) {
        this.properties = properties;
    }

    /**
     * 启动时校验当前密钥。
     *
     * <p><b>快速失败</b>：密钥没配或长度不对时，与其让应用起来、
     * 等到用户第一次"添加数据源"才抛一个晦涩的异常，不如启动就报清楚。
     * 配置错误属于"部署时就能发现"的问题，不该拖到运行期。
     */
    @PostConstruct
    void validateOnStartup() {
        buildKey(properties.getCurrentKeyVersion(), requireKeyMaterial(properties.getCurrentKeyVersion()));

        Set<String> versions = new TreeSet<>(properties.getKeys().keySet());
        versions.add(properties.getCurrentKeyVersion());

        // 只打印版本号，不打印密钥本身
        log.info("AES 密钥已就绪。当前版本={}，可用版本={}，历史 ECB 密文按版本 {} 解",
                properties.getCurrentKeyVersion(), versions, properties.getLegacyKeyVersion());
    }

    public String encrypt(String plainText) {
        if (plainText == null) {
            throw new IllegalArgumentException("待加密内容不能为 null");
        }
        String version = properties.getCurrentKeyVersion();

        // ⚠️ 取密钥必须在 try <b>外面</b>。
        // 「没有配置 v2 的密钥」是配置错误，「加密失败」是运行错误，
        // 把前者包进后者，报错就变成了「加密失败」——
        // 排查的人看到这句话完全不知道该去改哪个配置项。
        SecretKeySpec key = buildKey(version, requireKeyMaterial(version));

        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return version + VERSION_SEPARATOR + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    public String decrypt(String encryptedText) {
        if (encryptedText == null) {
            throw new IllegalArgumentException("待解密内容不能为 null");
        }

        int separator = encryptedText.indexOf(VERSION_SEPARATOR);

        // 同 encrypt：先解析出版本并取到密钥（配置错误在这里带自己的信息抛出），
        // 再进 try 做真正的解密运算
        String version = separator > 0
                ? encryptedText.substring(0, separator)
                : properties.getLegacyKeyVersion();
        SecretKeySpec key = buildKey(version, requireKeyMaterial(version));

        try {
            if (separator > 0) {
                return decryptGcm(key, encryptedText.substring(separator + 1));
            }
            // 没有版本前缀 = 引入 GCM 之前的历史密文
            log.debug("解密历史 ECB 密文，使用密钥版本 {}", version);
            return decryptLegacyEcb(key, encryptedText);
        } catch (Exception e) {
            // 异常信息里<b>不能</b>带上密文或密钥
            throw new IllegalStateException("解密失败（密钥版本可能不匹配，或数据已损坏）", e);
        }
    }

    /**
     * 判断一段密文是不是用<b>当前</b>密钥版本加密的。
     *
     * <p>给"重新加密存量数据"用：只有版本不一致的才需要处理，所以这个判断必须是幂等的依据。
     * 历史 ECB 密文一律返回 {@code false}（它没有版本前缀）。
     */
    public boolean isEncryptedWithCurrentVersion(String encryptedText) {
        if (encryptedText == null) {
            return false;
        }
        int separator = encryptedText.indexOf(VERSION_SEPARATOR);
        if (separator <= 0) {
            return false;
        }
        return properties.getCurrentKeyVersion().equals(encryptedText.substring(0, separator));
    }

    /** 当前用于加密的密钥版本号。 */
    public String getCurrentKeyVersion() {
        return properties.getCurrentKeyVersion();
    }

    /** 取密文里的密钥版本；历史 ECB 密文返回 {@code null}。 */
    public String extractVersion(String encryptedText) {
        if (encryptedText == null) {
            return null;
        }
        int separator = encryptedText.indexOf(VERSION_SEPARATOR);
        return separator > 0 ? encryptedText.substring(0, separator) : null;
    }

    /**
     * 按版本取密钥材料。
     *
     * <p>优先级：{@code aes.keys[版本]} 显式配置 &gt; 当前版本回退到 {@code aes.key}。
     * 有了这个回退，<b>只配 {@code aes.key} 的现有部署行为完全不变</b>。
     */
    private String requireKeyMaterial(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalStateException("AES 密钥版本号不能为空");
        }

        String material = properties.getKeys().get(version);
        if (material != null && !material.isBlank()) {
            return material;
        }

        if (version.equals(properties.getCurrentKeyVersion())
                && properties.getKey() != null && !properties.getKey().isBlank()) {
            return properties.getKey();
        }

        throw new IllegalStateException("没有配置版本为「" + version + "」的 AES 密钥。"
                + "请检查配置 aes.keys." + version
                + "（当前版本是 " + properties.getCurrentKeyVersion() + "，可用 aes.key 提供它的密钥）");
    }

    private String decryptGcm(SecretKeySpec key, String payload) throws Exception {
        byte[] combined = Base64.getDecoder().decode(payload);
        if (combined.length <= GCM_IV_LENGTH) {
            throw new IllegalArgumentException("密文长度非法");
        }

        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

        Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));

        byte[] plain = cipher.doFinal(combined, GCM_IV_LENGTH, combined.length - GCM_IV_LENGTH);
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** 仅用于解密历史 ECB 密文，新数据一律使用 GCM。 */
    private String decryptLegacyEcb(SecretKeySpec key, String encryptedText) throws Exception {
        Cipher cipher = Cipher.getInstance(LEGACY_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] plain = cipher.doFinal(Base64.getDecoder().decode(encryptedText));
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** 校验密钥长度，避免把「密钥配错」这种问题拖到运行期才以晦涩的异常暴露。 */
    private SecretKeySpec buildKey(String version, String material) {
        byte[] raw = material == null ? new byte[0] : material.getBytes(StandardCharsets.UTF_8);
        if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
            throw new IllegalStateException("AES 密钥长度必须为 16/24/32 字节，"
                    + "版本「" + version + "」当前为 " + raw.length + " 字节");
        }
        return new SecretKeySpec(raw, ALGORITHM);
    }
}
