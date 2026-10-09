package com.example.sqlquery.util;

import com.example.sqlquery.config.AesProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据源密码的加解密与<b>密钥轮换</b>。
 *
 * <p>本类守的核心失败模式：<b>换密钥之后老数据解不开</b>。
 * 那把"换不了的密钥"实际上等于"永远用同一个密钥" ——
 * 密钥一旦泄露也没法换，只能让用户把每个数据源密码重新填一遍。
 */
class AesUtilTest {

    /** 16 字节 = AES-128。 */
    private static final String KEY_V1 = "SqlQueryPlatform";
    /** 32 字节 = AES-256。长度必须正好是 16/24/32，多一个字符少一个字符都会被拒。 */
    private static final String KEY_V2 = "SecondKeyForRotation32BytesLong!";

    private AesProperties properties(String currentVersion, String key,
                                     Map<String, String> extraKeys, String legacyVersion) {
        AesProperties properties = new AesProperties();
        ReflectionTestUtils.setField(properties, "currentKeyVersion", currentVersion);
        ReflectionTestUtils.setField(properties, "key", key);
        ReflectionTestUtils.setField(properties, "keys",
                extraKeys == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extraKeys));
        ReflectionTestUtils.setField(properties, "legacyKeyVersion", legacyVersion);
        return properties;
    }

    /** 只配 aes.key 的形态 —— 与改造前的部署完全一致。 */
    private AesUtil simple(String key) {
        return new AesUtil(properties("v1", key, null, "v1"));
    }

    // ================= 基本加解密 =================

    @Test
    void shouldEncryptAndDecrypt() {
        AesUtil aesUtil = simple(KEY_V1);

        String raw = "123456";
        String encrypted = aesUtil.encrypt(raw);

        assertNotEquals(raw, encrypted);
        assertTrue(encrypted.startsWith("v1:"), "密文必须带密钥版本前缀");
        assertEquals(raw, aesUtil.decrypt(encrypted));
    }

    @Test
    void shouldProduceDifferentCipherTextForSamePlainText() {
        AesUtil aesUtil = simple(KEY_V1);

        String first = aesUtil.encrypt("123456");
        String second = aesUtil.encrypt("123456");

        // ECB 下两者会完全相同；GCM 每次使用随机 IV，密文必须不同
        assertNotEquals(first, second);
        assertEquals("123456", aesUtil.decrypt(first));
        assertEquals("123456", aesUtil.decrypt(second));
    }

    @Test
    void shouldRejectInvalidKeyLength() {
        AesUtil aesUtil = simple("too-short-key");

        assertThrows(IllegalStateException.class, () -> aesUtil.encrypt("123456"));
        assertThrows(IllegalStateException.class, aesUtil::validateOnStartup);
    }

    // ================= 历史格式兼容 =================

    @Test
    void shouldStillDecryptLegacyEcbCipherText() throws Exception {
        AesUtil aesUtil = simple(KEY_V1);

        // 手工构造历史版本（AES/ECB/PKCS5Padding）产生的密文，验证迁移期兼容
        Cipher legacy = Cipher.getInstance("AES/ECB/PKCS5Padding");
        legacy.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY_V1.getBytes(StandardCharsets.UTF_8), "AES"));
        String legacyCipherText = Base64.getEncoder()
                .encodeToString(legacy.doFinal("legacy-password".getBytes(StandardCharsets.UTF_8)));

        assertEquals("legacy-password", aesUtil.decrypt(legacyCipherText));
        assertNull(aesUtil.extractVersion(legacyCipherText), "历史密文没有版本前缀");
        assertFalse(aesUtil.isEncryptedWithCurrentVersion(legacyCipherText),
                "历史密文必须被判定为「需要重新加密」");
    }

    // ================= 密钥轮换 =================

    @Test
    void oldCipherTextMustStillDecryptAfterRotation() {
        // 轮换前：只有 v1
        String encryptedWithV1 = simple(KEY_V1).encrypt("db-password");

        // 轮换后：当前版本 v2，老密钥保留在 keys.v1
        AesUtil afterRotation = new AesUtil(properties("v2", KEY_V2, Map.of("v1", KEY_V1), "v1"));

        assertEquals("db-password", afterRotation.decrypt(encryptedWithV1),
                "换密钥之后老数据必须仍然解得开，否则「轮换」根本做不了");
        assertEquals("v1", afterRotation.extractVersion(encryptedWithV1));
        assertFalse(afterRotation.isEncryptedWithCurrentVersion(encryptedWithV1));
    }

    @Test
    void newWritesShouldUseTheCurrentVersion() {
        AesUtil afterRotation = new AesUtil(properties("v2", KEY_V2, Map.of("v1", KEY_V1), "v1"));

        String fresh = afterRotation.encrypt("new-password");

        assertTrue(fresh.startsWith("v2:"), "新写入必须用当前版本加密");
        assertEquals("new-password", afterRotation.decrypt(fresh));
        assertTrue(afterRotation.isEncryptedWithCurrentVersion(fresh));
    }

    @Test
    void reEncryptionMakesOldCipherTextUpToDate() {
        String encryptedWithV1 = simple(KEY_V1).encrypt("db-password");
        AesUtil afterRotation = new AesUtil(properties("v2", KEY_V2, Map.of("v1", KEY_V1), "v1"));

        // 这正是 PasswordReEncryptRunner 做的那一步
        String reEncrypted = afterRotation.encrypt(afterRotation.decrypt(encryptedWithV1));

        assertTrue(afterRotation.isEncryptedWithCurrentVersion(reEncrypted));
        assertEquals("db-password", afterRotation.decrypt(reEncrypted));
        // 迁移是幂等的判据：再跑一次会跳过
        assertTrue(afterRotation.isEncryptedWithCurrentVersion(reEncrypted));
    }

    @Test
    void droppingTheOldKeyAfterMigrationShouldStillWork() {
        // 第三步：确认没有 v1 残留之后，旧密钥可以真的丢掉
        AesUtil afterCleanup = new AesUtil(properties("v2", KEY_V2, null, "v1"));

        String fresh = afterCleanup.encrypt("db-password");
        assertEquals("db-password", afterCleanup.decrypt(fresh));

        // 而这时如果还有漏网的 v1 密文，应当<b>明确报错</b>而不是静默返回垃圾。
        // 报错内容要指向"缺哪个配置"，而不是笼统一句「解密失败」——
        // 后者会让人以为是数据坏了，去查数据库；实际是少配了一个密钥。
        String leftover = simple(KEY_V1).encrypt("missed-one");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> afterCleanup.decrypt(leftover));
        assertTrue(e.getMessage().contains("aes.keys." + "v1"),
                "报错要说清是哪个版本的密钥没配，实际：" + e.getMessage());
    }

    @Test
    void explicitKeysShouldTakePrecedenceOverTheCompatProperty() {
        // 当前版本是 v1，aes.key 给的是 KEY_V2，但 keys.v1 显式写成了 KEY_V1。
        // 规则是「keys 优先，aes.key 只是当前版本的兜底」—— 所以实际生效的是 KEY_V1。
        AesUtil aesUtil = new AesUtil(properties("v1", KEY_V2, Map.of("v1", KEY_V1), "v1"));

        assertEquals("x", aesUtil.decrypt(aesUtil.encrypt("x")));
        assertTrue(aesUtil.encrypt("x").startsWith("v1:"));

        // 用 keys.v1（KEY_V1）加密的密文解得开
        assertEquals("with-explicit-key", aesUtil.decrypt(simple(KEY_V1).encrypt("with-explicit-key")));

        // 而用 aes.key（KEY_V2）加密的同版本密文必须解不开 —— 这证明生效的确实是 keys.v1
        assertThrows(IllegalStateException.class,
                () -> aesUtil.decrypt(simple(KEY_V2).encrypt("with-compat-key")),
                "keys 里显式配置的密钥应当优先于 aes.key");
    }

    @Test
    void missingKeyVersionShouldFailWithAClearMessage() {
        AesUtil aesUtil = new AesUtil(properties("v2", "", null, "v2"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> aesUtil.encrypt("x"));
        assertTrue(e.getMessage().contains("aes.keys.v2") || e.getMessage().contains("密钥长度"),
                "报错要说清楚是哪个配置项缺失，实际：" + e.getMessage());
    }

    @Test
    void tamperedCipherTextShouldFailInsteadOfReturningGarbage() {
        AesUtil aesUtil = simple(KEY_V1);
        String encrypted = aesUtil.encrypt("secret");

        // 翻掉 Base64 段中间的一个字符（避开末尾可能被忽略的填充位）
        String payload = encrypted.substring(3);
        int middle = payload.length() / 2;
        char flipped = payload.charAt(middle) == 'A' ? 'B' : 'A';
        String tampered = "v1:" + payload.substring(0, middle) + flipped + payload.substring(middle + 1);

        // GCM 是认证加密：改一个字符就必须解密失败，而不是返回一段乱码
        assertThrows(IllegalStateException.class, () -> aesUtil.decrypt(tampered));
    }

    @Test
    void nullInputsShouldBeRejectedExplicitly() {
        AesUtil aesUtil = simple(KEY_V1);
        assertThrows(IllegalArgumentException.class, () -> aesUtil.encrypt(null));
        assertThrows(IllegalArgumentException.class, () -> aesUtil.decrypt(null));
    }
}
