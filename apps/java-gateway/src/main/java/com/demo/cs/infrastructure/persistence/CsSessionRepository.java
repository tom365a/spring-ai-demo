package com.demo.cs.infrastructure.persistence;

import com.demo.cs.domain.CsSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CsSessionRepository extends JpaRepository<CsSession, String> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from CsSession s where s.id = :id")
    java.util.Optional<CsSession> locked(@org.springframework.data.repository.query.Param("id") String id);
    @org.springframework.data.jpa.repository.Query("select s.id from CsSession s where s.status <> 'closed' and coalesce(s.lastInputAt,s.createdAt) <= :cutoff")
    List<String> expiredIds(@org.springframework.data.repository.query.Param("cutoff") java.time.Instant cutoff);
    List<CsSession> findByUserIdOrderByUpdatedAtDesc(String userId);
}
