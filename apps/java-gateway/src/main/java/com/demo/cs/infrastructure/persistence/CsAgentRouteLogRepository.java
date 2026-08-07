package com.demo.cs.infrastructure.persistence;

import com.demo.cs.domain.CsAgentRouteLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CsAgentRouteLogRepository extends JpaRepository<CsAgentRouteLog, Long> {
    List<CsAgentRouteLog> findBySessionIdOrderByCreatedAtDesc(String sessionId);
}
