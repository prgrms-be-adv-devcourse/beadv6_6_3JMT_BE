package com.prompthub.user.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "auth",
        uniqueConstraints = @UniqueConstraint(columnNames = {"provider", "oauth_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Auth {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID authId;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "provider", nullable = false, columnDefinition = "auth_provider_type")
    private OAuthProvider provider;

    @Column(name = "oauth_id", nullable = false, length = 100)
    private String oauthId;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;

    public static Auth create(UUID userId, OAuthProvider provider, String oauthId) {
        Auth auth = new Auth();
        auth.authId = UUID.randomUUID();
        auth.userId = userId;
        auth.provider = provider;
        auth.oauthId = oauthId;
        auth.connectedAt = Instant.now();
        return auth;
    }
}
