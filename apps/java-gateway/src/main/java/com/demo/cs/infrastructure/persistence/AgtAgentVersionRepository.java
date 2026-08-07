package com.demo.cs.infrastructure.persistence;

import com.demo.cs.domain.AgtAgentVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgtAgentVersionRepository extends JpaRepository<AgtAgentVersion, Long> {
    List<AgtAgentVersion> findByAgentCodeOrderByVersionDesc(String agentCode);
    Optional<AgtAgentVersion> findByAgentCodeAndVersion(String agentCode, int version);
}
