package com.demo.cs.infrastructure.persistence;
import com.demo.cs.domain.PendingResourceAction;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.*;
public interface PendingResourceActionRepository extends JpaRepository<PendingResourceAction,String> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select p from PendingResourceAction p where p.id=:id") Optional<PendingResourceAction> locked(String id);
 List<PendingResourceAction> findByState(String state);
}
