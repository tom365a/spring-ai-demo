package com.demo.cs;

import com.demo.cs.application.resources.CredentialVault;
import com.demo.cs.domain.ResourceCredential;
import com.demo.cs.infrastructure.persistence.ResourceCredentialRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CredentialVaultTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final Map<String, ResourceCredential> records = new HashMap<>();
    ResourceCredentialRepository repository;
    MockEnvironment environment;
    CredentialVault vault;

    @BeforeEach void setup() {
        repository = mock(ResourceCredentialRepository.class);
        environment = new MockEnvironment().withProperty("APP_RESOURCE_MASTER_KEY", key((byte) 7));
        when(repository.findById(anyString())).thenAnswer(a -> Optional.ofNullable(records.get(a.getArgument(0))));
        when(repository.save(any(ResourceCredential.class))).thenAnswer(a -> {
            ResourceCredential row = a.getArgument(0);
            records.put(row.getId(), row);
            return row;
        });
        vault = new CredentialVault(repository, environment);
    }

    String key(byte value) { byte[] bytes = new byte[32]; java.util.Arrays.fill(bytes,value); return Base64.getEncoder().encodeToString(bytes); }
    ObjectNode input(String value) { return mapper.createObjectNode().put("action","REPLACE").put("source","INPUT").put("value",value); }
    ObjectNode env(String name) { return mapper.createObjectNode().put("action","REPLACE").put("source","ENV").put("envName",name); }

    @Test void ciphertextIsRandomAuthenticatedAndNeverSerialized() throws Exception {
        String secret = "synthetic-credential-plaintext-汉字";
        String first = vault.change(null,input(secret),true);
        String second = vault.change(null,input(secret),true);
        assertThat(first).isNotEqualTo(second);
        assertThat(records.get(first).getCiphertext()).startsWith("v1:").doesNotContain(secret);
        assertThat(records.get(first).getCiphertext()).isNotEqualTo(records.get(second).getCiphertext());
        assertThat(vault.resolve(first)).isEqualTo(secret);
        assertThat(vault.resolve(second)).isEqualTo(secret);
        assertThat(mapper.writeValueAsString(records.get(first))).doesNotContain(secret,"ciphertext",records.get(first).getCiphertext());
        assertThat(vault.status(first)).containsOnlyKeys("mode","envName","configured").containsEntry("configured",true).containsEntry("mode","INPUT");
        assertThat(mapper.writeValueAsString(vault.status(first))).doesNotContain(secret,first,"v1:");
    }

    @Test void keepAndClearDoNotChangePublishedCredentialRevision() {
        String published = vault.change(null,input("first-secret"),true);
        String draft = vault.change(published,input("draft-secret"),true);
        assertThat(vault.resolve(published)).isEqualTo("first-secret");
        assertThat(vault.resolve(draft)).isEqualTo("draft-secret");
        assertThat(vault.change(published,null,false)).isEqualTo(published);
        assertThat(vault.change(published,mapper.createObjectNode().put("action","KEEP").put("value",""),false)).isEqualTo(published);
        assertThat(vault.change(draft,mapper.createObjectNode().put("action","CLEAR"),true)).isNull();
        assertThat(vault.resolve(published)).isEqualTo("first-secret");
        assertThat(records).hasSize(2);
        verify(repository,never()).delete(any(ResourceCredential.class));
    }

    @Test void explicitBlankReplacementAndNonPublisherMutationAreRejected() {
        assertThatThrownBy(()->vault.change(null,input("  "),true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->vault.change(null,input("private-marker"),false)).isInstanceOf(SecurityException.class).hasMessageNotContaining("private-marker");
        assertThatThrownBy(()->vault.change("old",mapper.createObjectNode().put("action","CLEAR"),false)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->vault.change(null,mapper.createObjectNode().put("action","DELETE"),true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->vault.change(null,mapper.createObjectNode().put("action","REPLACE").put("source",1),true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(records).isEmpty();
    }

    @Test void missingOrInvalidKeyNeverPersistsPlaintext() {
        environment.setProperty("APP_RESOURCE_MASTER_KEY", "");
        assertThatThrownBy(()->vault.change(null,input("never-save-this"),true)).isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_RESOURCE_MASTER_KEY").hasMessageNotContaining("never-save-this");
        environment.setProperty("APP_RESOURCE_MASTER_KEY", "invalid-master-key-marker");
        assertThatThrownBy(()->vault.change(null,input("never-save-this"),true)).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("invalid-master-key-marker").hasMessageNotContaining("never-save-this");
        environment.setProperty("APP_RESOURCE_MASTER_KEY",Base64.getEncoder().encodeToString(new byte[16]));
        assertThatThrownBy(()->vault.change(null,input("never-save-this"),true)).isInstanceOf(IllegalStateException.class);
        assertThat(records).isEmpty();
        verify(repository,never()).save(any());
    }

    @Test void wrongMasterKeyAndTamperedCiphertextFailWithoutFallback() {
        String ref=vault.change(null,input("protected-value"),true);
        environment.setProperty("APP_RESOURCE_MASTER_KEY",key((byte)8));
        assertThat(vault.status(ref)).containsEntry("configured",true);
        assertThatThrownBy(()->vault.resolve(ref)).isInstanceOf(IllegalStateException.class).hasMessageContaining("解密失败").hasMessageNotContaining("protected-value");
        environment.setProperty("APP_RESOURCE_MASTER_KEY",key((byte)7));
        String encoded=records.get(ref).getCiphertext();
        byte[] packed=Base64.getDecoder().decode(encoded.substring(3));packed[packed.length-1]^=1;
        records.put(ref,new ResourceCredential(ref,"INPUT",null,"v1:"+Base64.getEncoder().encodeToString(packed)));
        assertThatThrownBy(()->vault.resolve(ref)).isInstanceOf(IllegalStateException.class).hasMessageContaining("解密失败");
    }

    @Test void ciphertextCannotBeCopiedToAnotherCredentialIdentity() {
        String ref=vault.change(null,input("identity-bound-value"),true);
        records.put("different-id",new ResourceCredential("different-id","INPUT",null,records.get(ref).getCiphertext()));
        assertThatThrownBy(()->vault.resolve("different-id")).isInstanceOf(IllegalStateException.class).hasMessageContaining("解密失败");
    }

    @Test void environmentReferenceNeedsAllowlistAndConfiguredValueButNoMasterKey() throws Exception {
        environment.setProperty("APP_RESOURCE_MASTER_KEY","");
        environment.setProperty("KIMI_API_KEY","synthetic-env-only-key");
        String ref=vault.change(null,env("KIMI_API_KEY"),true);
        assertThat(vault.resolve(ref)).isEqualTo("synthetic-env-only-key");
        assertThat(vault.status(ref)).containsEntry("mode","ENV").containsEntry("envName","KIMI_API_KEY").containsEntry("configured",true);
        assertThat(records.get(ref).getCiphertext()).isNull();
        assertThat(mapper.writeValueAsString(records.get(ref))).doesNotContain("synthetic-env-only-key");
        assertThatThrownBy(()->vault.change(null,env("UNAUTHORIZED_KEY"),true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->vault.change(null,env("OPENAI_API_KEY"),true)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("未配置");
        assertThatThrownBy(()->vault.change(null,env("${KIMI_API_KEY}"),true)).isInstanceOf(IllegalArgumentException.class);
        environment.setProperty("KIMI_API_KEY","");
        assertThat(vault.status(ref)).containsEntry("configured",false);
        assertThatThrownBy(()->vault.resolve(ref)).isInstanceOf(IllegalStateException.class).hasMessageContaining("未配置");
    }

    @Test void explicitAllowlistIsEnforcedAgainAtResolution() {
        environment.setProperty("app.resources.allowed-credential-env","LOCAL_TEST_TOKEN, OTHER_TOKEN");
        environment.setProperty("LOCAL_TEST_TOKEN","synthetic-local-token");
        String ref=vault.change(null,env("LOCAL_TEST_TOKEN"),true);
        assertThat(vault.resolve(ref)).isEqualTo("synthetic-local-token");
        environment.setProperty("app.resources.allowed-credential-env","OTHER_TOKEN");
        assertThat(vault.status(ref)).containsEntry("configured",false);
        assertThatThrownBy(()->vault.resolve(ref)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("未获管理员授权");
    }

    @Test void absentCredentialIsUnconfiguredAndDanglingReferenceFails() {
        assertThat(vault.status(null)).containsEntry("mode","NONE").containsEntry("configured",false);
        assertThat(vault.status("deleted-reference")).containsEntry("configured",false);
        assertThat(vault.resolve(null)).isNull();
        assertThatThrownBy(()->vault.resolve("deleted-reference")).isInstanceOf(IllegalStateException.class).hasMessageContaining("版本不存在");
    }

    @Test void startupMigrationPreservesAnUnconfiguredButAllowedReference() {
        environment.setProperty("APP_RESOURCE_MASTER_KEY", "");
        String ref = vault.referenceEnvironment("KIMI_API_KEY");
        assertThat(vault.status(ref)).containsEntry("mode", "ENV").containsEntry("configured", false);
        assertThat(records.get(ref).getCiphertext()).isNull();
        assertThatThrownBy(() -> vault.resolve(ref)).isInstanceOf(IllegalStateException.class);
        environment.setProperty("KIMI_API_KEY", "configured-after-migration");
        assertThat(vault.resolve(ref)).isEqualTo("configured-after-migration");
        assertThatThrownBy(() -> vault.referenceEnvironment("UNKNOWN_SECRET")).isInstanceOf(IllegalArgumentException.class);
    }
}
