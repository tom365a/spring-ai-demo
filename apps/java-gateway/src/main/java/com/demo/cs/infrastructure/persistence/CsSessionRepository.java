package com.demo.cs.infrastructure.persistence;

import com.demo.cs.domain.CsSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CsSessionRepository extends JpaRepository<CsSession, String> {
    List<CsSession> findByUserIdOrderByUpdatedAtDesc(String userId);
}
