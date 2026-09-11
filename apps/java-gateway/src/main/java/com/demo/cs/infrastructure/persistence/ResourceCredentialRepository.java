package com.demo.cs.infrastructure.persistence;

import com.demo.cs.domain.ResourceCredential;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ResourceCredentialRepository extends JpaRepository<ResourceCredential, String> {}
