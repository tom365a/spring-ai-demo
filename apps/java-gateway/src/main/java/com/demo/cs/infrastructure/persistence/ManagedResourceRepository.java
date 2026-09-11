package com.demo.cs.infrastructure.persistence;
import com.demo.cs.domain.ManagedResource;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ManagedResourceRepository extends JpaRepository<ManagedResource,String> {
 Optional<ManagedResource> findByCode(String code);
 List<ManagedResource> findByDeletedFalseOrderByKindAscNameAsc();
}
