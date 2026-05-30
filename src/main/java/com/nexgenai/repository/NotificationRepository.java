package com.nexgenai.repository;

import com.nexgenai.model.Notification;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, String> {

    List<Notification> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    long countByUserIdAndReadFalse(String userId);

    Optional<Notification> findByIdAndUserId(String id, String userId);

    @Modifying
    @Transactional
    @Query("UPDATE Notification n SET n.read = true WHERE n.userId = :userId AND n.read = false")
    void markAllReadForUser(@Param("userId") String userId);
}
