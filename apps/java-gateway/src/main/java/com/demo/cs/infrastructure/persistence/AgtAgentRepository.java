package com.demo.cs.infrastructure.persistence;

import com.demo.cs.domain.AgtAgent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgtAgentRepository extends JpaRepository<AgtAgent, String> {
    Optional<AgtAgent> findByCode(String code);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from AgtAgent a where a.code = :code")
    Optional<AgtAgent> findForUpdateByCode(@org.springframework.data.repository.query.Param("code") String code);
    boolean existsByCode(String code);
    List<AgtAgent> findByStatusAndEnabledTrue(String status);
    List<AgtAgent> findAllByOrderBySortOrderAscCodeAsc();
}
