package com.demo.cs.application.resources;

import com.demo.cs.domain.ResourceCredential;
import com.demo.cs.infrastructure.persistence.ResourceCredentialRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CredentialVault {
    private static final String DEFAULT_ALLOWED = "KIMI_API_KEY,OPENAI_API_KEY,OPENAI_EMBEDDING_API_KEY";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final ResourceCredentialRepository repository;
    private final Environment environment;
    private final SecureRandom random = new SecureRandom();

    public CredentialVault(ResourceCredentialRepository repository, Environment environment) {
        this.repository = repository;
        this.environment = environment;
    }

    /** Startup migration only: preserve an allowlisted environment reference even before deployment supplies its value. */
    @Transactional
    public String referenceEnvironment(String envName) {
        validateEnv(envName);
        String id = UUID.randomUUID().toString();
        repository.save(new ResourceCredential(id, "ENV", envName, null));
        return id;
    }

    /** KEEP is available to editors; any actual credential mutation requires publisher authorization. */
    @Transactional
    public String change(String previousRef, JsonNode change, boolean publisher) {
        if (change == null || change.isNull() || change.isMissingNode()) return previousRef;
        if (!change.isObject()) throw new IllegalArgumentException("凭证操作格式不正确");
        String action = text(change, "action");
        if (action.isBlank() || "KEEP".equals(action)) return previousRef;
        if (!"REPLACE".equals(action) && !"CLEAR".equals(action)) {
            throw new IllegalArgumentException("凭证操作必须为 KEEP、REPLACE 或 CLEAR");
        }
        if (!publisher) throw new SecurityException("仅发布管理员可以替换或清除凭证");
        if ("CLEAR".equals(action)) return null;
        String source = text(change, "source");
        String id = UUID.randomUUID().toString();
        ResourceCredential revision;
        if ("ENV".equals(source)) {
            String envName = text(change, "envName").trim();
            validateEnv(envName);
            String currentValue = environment.getProperty(envName);
            if (currentValue == null || currentValue.isBlank()) {
                throw new IllegalArgumentException("凭证引用的服务端变量未配置，请联系管理员");
            }
            revision = new ResourceCredential(id, "ENV", envName, null);
        } else if ("INPUT".equals(source)) {
            String value = text(change, "value");
            if (value.isBlank()) throw new IllegalArgumentException("替换凭证必须提供非空的新值");
            if (value.getBytes(StandardCharsets.UTF_8).length > 16384) {
                throw new IllegalArgumentException("凭证长度超过允许上限");
            }
            revision = new ResourceCredential(id, "INPUT", null, encrypt(id, value));
        } else {
            throw new IllegalArgumentException("凭证来源必须为 ENV 或 INPUT");
        }
        repository.save(revision);
        return id;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> status(String ref) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", "NONE");
        result.put("envName", null);
        result.put("configured", false);
        if (ref == null || ref.isBlank()) return result;
        ResourceCredential credential = repository.findById(ref).orElse(null);
        if (credential == null) return result;
        result.put("mode", credential.getMode());
        if ("ENV".equals(credential.getMode())) {
            result.put("envName", credential.getEnvName());
            boolean configured = allowedEnv(credential.getEnvName());
            if (configured) {
                String value = environment.getProperty(credential.getEnvName());
                configured = value != null && !value.isBlank();
            }
            result.put("configured", configured);
        } else if ("INPUT".equals(credential.getMode())) {
            result.put("configured", credential.getCiphertext() != null && !credential.getCiphertext().isBlank());
        }
        return result;
    }

    @Transactional(readOnly = true)
    public String resolve(String ref) {
        if (ref == null || ref.isBlank()) return null;
        ResourceCredential credential = repository.findById(ref)
                .orElseThrow(() -> new IllegalStateException("凭证版本不存在，请重新配置凭证"));
        if ("ENV".equals(credential.getMode())) {
            validateEnv(credential.getEnvName());
            String value = environment.getProperty(credential.getEnvName());
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("凭证引用的服务端变量未配置，请联系管理员");
            }
            return value;
        }
        if (!"INPUT".equals(credential.getMode())) {
            throw new IllegalStateException("凭证存储格式不受支持，请重新配置凭证");
        }
        return decrypt(credential);
    }

    private String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual()) throw new IllegalArgumentException("凭证操作字段必须为文本");
        return value.textValue();
    }

    private boolean allowedEnv(String name) {
        if (name == null || !name.matches("[A-Z][A-Z0-9_]{0,127}")) return false;
        String configured = environment.getProperty("app.resources.allowed-credential-env", DEFAULT_ALLOWED);
        return Arrays.stream(configured.split(",")).map(String::trim).anyMatch(name::equals);
    }

    private void validateEnv(String name) {
        if (!allowedEnv(name)) throw new IllegalArgumentException("该凭证环境变量未获管理员授权");
    }

    private byte[] masterKey() {
        String encoded = environment.getProperty("APP_RESOURCE_MASTER_KEY");
        if (encoded == null || encoded.isBlank()) {
            encoded = environment.getProperty("app.resources.master-key");
        }
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("未配置 APP_RESOURCE_MASTER_KEY；请在独立服务端环境设置 32 字节 Base64 主密钥，或使用已授权环境变量引用");
        }
        try {
            byte[] key = Base64.getDecoder().decode(encoded.trim());
            if (key.length != 32) {
                Arrays.fill(key, (byte) 0);
                throw new IllegalArgumentException();
            }
            return key;
        } catch (IllegalArgumentException ignored) {
            throw new IllegalStateException("APP_RESOURCE_MASTER_KEY 格式错误，必须为 32 字节 Base64 主密钥");
        }
    }

    private String encrypt(String id, String value) {
        byte[] key = masterKey();
        byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, id);
            byte[] encrypted = cipher.doFinal(plaintext);
            byte[] packed = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
            return "v1:" + Base64.getEncoder().encodeToString(packed);
        } catch (GeneralSecurityException ignored) {
            throw new IllegalStateException("凭证加密失败，未保存凭证，请检查服务端加密配置");
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private String decrypt(ResourceCredential credential) {
        byte[] key = masterKey();
        byte[] plaintext = null;
        try {
            String stored = credential.getCiphertext();
            if (stored == null || !stored.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] packed = Base64.getDecoder().decode(stored.substring(3));
            if (packed.length < NONCE_BYTES + TAG_BITS / 8) throw new IllegalArgumentException();
            byte[] nonce = Arrays.copyOfRange(packed, 0, NONCE_BYTES);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, key, nonce, credential.getId());
            plaintext = cipher.doFinal(packed, NONCE_BYTES, packed.length - NONCE_BYTES);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ignored) {
            throw new IllegalStateException("凭证解密失败，主密钥不匹配或凭证已损坏，请恢复正确主密钥或重新配置凭证");
        } finally {
            Arrays.fill(key, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private Cipher cipher(int mode, byte[] key, byte[] nonce, String id) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(("resource-credential:v1:" + id).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
