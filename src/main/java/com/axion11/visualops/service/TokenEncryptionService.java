package com.axion11.visualops.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Service
public class TokenEncryptionService {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    @Value("${oauth.token.encryption-key:}")
    private String encryptionKeyBase64;

    private SecretKey secretKey;
    private final SecureRandom random = new SecureRandom();

    @PostConstruct
    public void init() {
        if (encryptionKeyBase64 == null || encryptionKeyBase64.isEmpty()) {
            // Generate an ephemeral key — WARNING: tokens won't survive restarts.
            try {
                KeyGenerator gen = KeyGenerator.getInstance(ALGORITHM);
                gen.init(256);
                this.secretKey = gen.generateKey();
                log.warn("OAUTH_TOKEN_ENCRYPTION_KEY not set — generated ephemeral key. Tokens will not survive restarts.");
            } catch (Exception e) {
                throw new IllegalStateException("Failed to generate encryption key", e);
            }
        } else {
            byte[] keyBytes = Base64.getDecoder().decode(encryptionKeyBase64);
            if (keyBytes.length != 32) {
                throw new IllegalStateException("OAUTH_TOKEN_ENCRYPTION_KEY must decode to 32 bytes (256 bits)");
            }
            this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes("UTF-8"));

            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length);
            buf.put(iv);
            buf.put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decrypt(String ciphertextBase64) {
        if (ciphertextBase64 == null) return null;
        try {
            byte[] data = Base64.getDecoder().decode(ciphertextBase64);
            ByteBuffer buf = ByteBuffer.wrap(data);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
