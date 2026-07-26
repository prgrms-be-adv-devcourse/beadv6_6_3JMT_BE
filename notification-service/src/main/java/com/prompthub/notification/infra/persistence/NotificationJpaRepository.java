package com.prompthub.notification.infra.persistence;

import com.prompthub.notification.domain.model.Notification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationJpaRepository extends JpaRepository<Notification, UUID> {
}
