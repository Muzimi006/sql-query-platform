package com.example.sqlquery.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 数据源密码的对称加解密。
 *
 * <p>当前算法为 AES/GCM/NoPadding。相比早期使用的 AES/ECB/PKCS5Padding：
 * <ul>
 *   <li>GCM 是认证加密（AEAD），除机密性外还提供完整性校验，密文被篡改会直接解密失败；</li>
 *   <li>每次加密使用随机 IV（不保密，随密文一起存），相同明文不会产生相同密文，
 *       避免了 ECB 下「相同明文 → 相同密文」导致的模式分析。</li>
 * </ul>
 *
 * <p>密文格式：{@code v1:} + Base64( IV(12 字节) || 密文+认证标签 )。
 * 不带前缀的密文按历史 ECB 格式解密，仅用于数据迁移期兼容，加密侧已不再产生。
 */
@Component
public class AesUtil {

    private static final String ALGORITHM = "AES";
    private static final String GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String LEGACY_TRANSFORMATION = "AES/ECB/PKCS5Padding";

    /** 版本前缀，用于区分当前 GCM 密文与历史 ECB 密文。 */
    private static final String GCM_PREFIX = "v1:";

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${aes.key}")
    private String key;

    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, buildKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return GCM_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    public String decrypt(String encryptedText) {
        try {
            if (encryptedText != null && encryptedText.startsWith(GCM_PREFIX)) {
                return decryptGcm(encryptedText.substring(GCM_PREFIX.length()));
            }
            return decryptLegacyEcb(encryptedText);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败", e);
        }
    }

    private String decryptGcm(String payload) throws Exception {
        byte[] combined = Base64.getDecoder().decode(payload);
        if (combined.length <= GCM_IV_LENGTH) {
            throw new IllegalArgumentException("密文长度非法");
        }

        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

        Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, buildKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));

        byte[] plain = cipher.doFinal(combined, GCM_IV_LENGTH, combined.length - GCM_IV_LENGTH);
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** 仅用于解密历史 ECB 密文，新数据一律使用 GCM。 */
    private String decryptLegacyEcb(String encryptedText) throws Exception {
        Cipher cipher = Cipher.getInstance(LEGACY_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, buildKey());
        byte[] plain = cipher.doFinal(Base64.getDecoder().decode(encryptedText));
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** 校验密钥长度，避免把「密钥配错」这种问题拖到运行期才以晦涩的异常暴露。 */
    private SecretKeySpec buildKey() {
        byte[] raw = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
        if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
            throw new IllegalStateException("AES 密钥长度必须为 16/24/32 字节，当前为 " + raw.length + " 字节");
        }
        return new SecretKeySpec(raw, ALGORITHM);
    }
}
