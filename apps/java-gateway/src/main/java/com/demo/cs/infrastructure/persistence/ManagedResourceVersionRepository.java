package com.demo.cs.infrastructure.persistence;
import com.demo.cs.domain.ManagedResourceVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface ManagedResourceVersionRepository extends JpaRepository<ManagedResourceVersion,String> {
 Optional<ManagedResourceVersion> findByResourceIdAndVersionNumber(String id,int version);
 List<ManagedResourceVersion> findByResourceIdOrderByVersionNumberDesc(String id);
}
