package com.demo.cs.infrastructure.persistence;
import com.demo.cs.domain.ConversationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface ConversationEventRepository extends JpaRepository<ConversationEvent,Long> {
 List<ConversationEvent> findBySessionIdOrderByIdAsc(String sessionId);
}
