package com.demo.cs.infrastructure.persistence;

import com.demo.cs.domain.BizOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BizOrderRepository extends JpaRepository<BizOrder, String> {

    /** 取消必须在悲观锁下读取，避免两次确认并发把同一单取消两次。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from BizOrder o where o.orderId = :orderId")
    Optional<BizOrder> lockById(@Param("orderId") String orderId);
}
