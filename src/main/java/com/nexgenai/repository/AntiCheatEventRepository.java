package com.nexgenai.repository;

import com.nexgenai.model.AntiCheatEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AntiCheatEventRepository extends JpaRepository<AntiCheatEvent, String> {

    List<AntiCheatEvent> findByTestIdAndSessionIdOrderByOccurredAtAsc(
            String testId, String sessionId);

    List<AntiCheatEvent> findBySessionIdOrderByOccurredAtAsc(String sessionId);

    long countBySessionIdAndType(String sessionId, String type);
    
 
    
    /**
     * Count events for a session (for quick risk-level check).
     */
    long countBySessionId(String sessionId);
 
    /**
     * All events for a test (for admin overview).
     */
    List<AntiCheatEvent> findByTestIdOrderByOccurredAtAsc(String testId);
}