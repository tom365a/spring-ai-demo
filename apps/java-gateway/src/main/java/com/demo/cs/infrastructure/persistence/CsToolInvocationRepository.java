package com.demo.cs.infrastructure.persistence;

import com.demo.cs.domain.CsToolInvocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CsToolInvocationRepository extends JpaRepository<CsToolInvocation, Long> {
    List<CsToolInvocation> findBySessionIdOrderByCreatedAtDesc(String sessionId);
}
