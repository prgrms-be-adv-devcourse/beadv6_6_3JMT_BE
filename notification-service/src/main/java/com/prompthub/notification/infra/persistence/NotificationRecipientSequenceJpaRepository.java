package com.prompthub.notification.infra.persistence;

import com.prompthub.notification.domain.model.NotificationRecipientSequence;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRecipientSequenceJpaRepository extends JpaRepository<NotificationRecipientSequence, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select sequence from NotificationRecipientSequence sequence where sequence.recipientId = :recipientId")
    Optional<NotificationRecipientSequence> findByRecipientIdForUpdate(UUID recipientId);
}
