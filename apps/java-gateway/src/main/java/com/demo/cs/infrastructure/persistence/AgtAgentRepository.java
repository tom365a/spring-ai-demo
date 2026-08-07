package com.demo.cs.infrastructure.persistence;

import com.demo.cs.domain.AgtAgent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgtAgentRepository extends JpaRepository<AgtAgent, String> {
    Optional<AgtAgent> findByCode(String code);
    boolean existsByCode(String code);
    List<AgtAgent> findByStatusAndEnabledTrue(String status);
    List<AgtAgent> findAllByOrderBySortOrderAscCodeAsc();
}
