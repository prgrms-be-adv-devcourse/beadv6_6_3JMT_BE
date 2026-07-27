package com.prompthub.notification.domain.repository;

import com.prompthub.notification.domain.model.Notification;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static com.prompthub.notification.domain.model.QNotification.notification;

@RequiredArgsConstructor
public class NotificationRepositoryImpl implements NotificationRepositoryCustom {
    private final JPAQueryFactory queryFactory;

    @Override
    public List<Notification> findReplayCandidates(UUID recipientId, Instant now, Instant cursorCreatedAt, UUID cursorId, int limit) {
        BooleanExpression afterCursor = notification.createdAt.gt(cursorCreatedAt)
            .or(notification.createdAt.eq(cursorCreatedAt).and(notification.id.gt(cursorId)));
        return queryFactory.selectFrom(notification)
            .where(notification.recipientId.eq(recipientId), notification.expiresAt.gt(now), afterCursor)
            .orderBy(notification.createdAt.asc(), notification.id.asc())
            .limit(limit)
            .fetch();
    }
}
