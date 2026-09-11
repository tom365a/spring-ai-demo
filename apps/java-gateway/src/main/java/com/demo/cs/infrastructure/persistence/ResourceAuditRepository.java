package com.demo.cs.infrastructure.persistence;
import com.demo.cs.domain.ResourceAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ResourceAuditRepository extends JpaRepository<ResourceAudit,String> {
 List<ResourceAudit> findByResourceIdOrderByCreatedAtDesc(String id);
}
