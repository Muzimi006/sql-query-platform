package com.example.sqlquery.util;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class AesUtilTest {

    @Test
    void shouldEncryptAndDecrypt() {
        AesUtil aesUtil = new AesUtil();
        ReflectionTestUtils.setField(aesUtil, "key", "SqlQueryPlatform123");

        String raw = "123456";
        String encrypted = aesUtil.encrypt(raw);

        assertNotEquals(raw, encrypted);
        assertEquals(raw, aesUtil.decrypt(encrypted));
    }
}
