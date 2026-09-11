package com.demo.cs.infrastructure.persistence;

import com.demo.cs.domain.BizLogisticsTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BizLogisticsTraceRepository extends JpaRepository<BizLogisticsTrace, Long> {
    List<BizLogisticsTrace> findByTrackingNoOrderByOccurredAtAsc(String trackingNo);
    boolean existsByTrackingNo(String trackingNo);
}
