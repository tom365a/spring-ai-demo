package com.demo.cs.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Immutable credential revision. Resource snapshots hold only its opaque identifier. */
@Entity
@Table(name = "cfg_resource_credential")
public class ResourceCredential {
    @Id
    @Column(length = 36, updatable = false)
    private String id;
    @Column(nullable = false, length = 16, updatable = false)
    private String mode;
    @Column(length = 128, updatable = false)
    private String envName;
    @JsonIgnore
    @Column(length = 32768, updatable = false)
    private String ciphertext;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected ResourceCredential() {}

    public ResourceCredential(String id, String mode, String envName, String ciphertext) {
        this.id = id;
        this.mode = mode;
        this.envName = envName;
        this.ciphertext = ciphertext;
        this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getMode() { return mode; }
    public String getEnvName() { return envName; }
    @JsonIgnore
    public String getCiphertext() { return ciphertext; }
    public Instant getCreatedAt() { return createdAt; }
}
