package com.example.sqlquery.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class AesUtil {
    private static final String ALGORITHM="AES";
    private static final String TRANSFORMATION="AES/ECB/PKCS5Padding";

    @Value("${aes.key}")
    private String key;

    public String encrypt(String plainText){
        try {
            SecretKeySpec keySpec=new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),ALGORITHM);
            Cipher cipher=Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE,keySpec);
            byte[] encrypted=cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted);
        }catch (Exception e){
            throw new RuntimeException("加密服务",e);
        }
    }
    public String decrypt(String encryptedText){
        try {
            SecretKeySpec keySpec=new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),ALGORITHM);
            Cipher cipher=Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE,keySpec);
            byte[] decrypted=cipher.doFinal(Base64.getDecoder().decode(encryptedText));
            return new String(decrypted,StandardCharsets.UTF_8);
        }catch (Exception e){
            throw new RuntimeException("解密失败",e);
        }
    }
}
