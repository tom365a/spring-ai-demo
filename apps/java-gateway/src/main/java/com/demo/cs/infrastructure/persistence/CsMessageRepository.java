package com.demo.cs.infrastructure.persistence;

import com.demo.cs.domain.CsMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CsMessageRepository extends JpaRepository<CsMessage, String> {
    List<CsMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
