package com.example.sqlquery.util;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AesUtilTest {

    /** 16 字节 = AES-128。 */
    private static final String VALID_KEY = "SqlQueryPlatform";

    private AesUtil newAesUtil(String key) {
        AesUtil aesUtil = new AesUtil();
        ReflectionTestUtils.setField(aesUtil, "key", key);
        return aesUtil;
    }

    @Test
    void shouldEncryptAndDecrypt() {
        AesUtil aesUtil = newAesUtil(VALID_KEY);

        String raw = "123456";
        String encrypted = aesUtil.encrypt(raw);

        assertNotEquals(raw, encrypted);
        assertTrue(encrypted.startsWith("v1:"));
        assertEquals(raw, aesUtil.decrypt(encrypted));
    }

    @Test
    void shouldProduceDifferentCipherTextForSamePlainText() {
        AesUtil aesUtil = newAesUtil(VALID_KEY);

        String first = aesUtil.encrypt("123456");
        String second = aesUtil.encrypt("123456");

        // ECB 下两者会完全相同；GCM 每次使用随机 IV，密文必须不同
        assertNotEquals(first, second);
        assertEquals("123456", aesUtil.decrypt(first));
        assertEquals("123456", aesUtil.decrypt(second));
    }

    @Test
    void shouldRejectInvalidKeyLength() {
        AesUtil aesUtil = newAesUtil("too-short-key");

        assertThrows(IllegalStateException.class, () -> aesUtil.encrypt("123456"));
    }

    @Test
    void shouldStillDecryptLegacyEcbCipherText() throws Exception {
        AesUtil aesUtil = newAesUtil(VALID_KEY);

        // 手工构造历史版本（AES/ECB/PKCS5Padding）产生的密文，验证迁移期兼容
        Cipher legacy = Cipher.getInstance("AES/ECB/PKCS5Padding");
        legacy.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(VALID_KEY.getBytes(StandardCharsets.UTF_8), "AES"));
        String legacyCipherText = Base64.getEncoder()
                .encodeToString(legacy.doFinal("legacy-password".getBytes(StandardCharsets.UTF_8)));

        assertEquals("legacy-password", aesUtil.decrypt(legacyCipherText));
    }
}
