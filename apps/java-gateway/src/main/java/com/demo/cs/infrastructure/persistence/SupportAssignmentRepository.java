package com.demo.cs.infrastructure.persistence;

import com.demo.cs.domain.SupportAssignment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SupportAssignmentRepository extends JpaRepository<SupportAssignment, String> {

    List<SupportAssignment> findByStatusInOrderByAssignedAtAsc(List<String> statuses);

    /** 认领必须加锁，避免两个坐席同时抢到同一个会话。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from SupportAssignment a where a.sessionId = :id")
    Optional<SupportAssignment> lockById(@Param("id") String id);
}
