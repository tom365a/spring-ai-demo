package com.demo.cs.infrastructure.persistence;

import com.demo.cs.domain.BizTicket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BizTicketRepository extends JpaRepository<BizTicket, Long> {
    List<BizTicket> findAllByOrderByIdDesc();
    List<BizTicket> findByUserIdOrderByIdDesc(String userId);
    Optional<BizTicket> findByTicketNo(String ticketNo);
}
