package com.prompthub.user.user.domain.model;

import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "\"user\"")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "email", nullable = false, length = 255, unique = true)
    private String email;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false, columnDefinition = "user_status_type")
    private UserStatus status;

    @Column(name = "terms_agreed", nullable = false)
    private boolean termsAgreed;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "role", nullable = false, columnDefinition = "user_role_type")
    private UserRole role;

    public static User create(
            String name,
            String email,
            String profileImageUrl,
            UserRole role,
            boolean termsAgreed
    ) {
        User user = new User();
        user.userId = UUID.randomUUID();
        user.name = name;
        user.email = email;
        user.profileImageUrl = profileImageUrl;
        user.status = UserStatus.ACTIVE;
        user.role = role;
        user.termsAgreed = termsAgreed;
        return user;
    }

    public void withdraw() {
        this.status = UserStatus.WITHDRAWN;
    }

    public void updateName(String name) {
        this.name = name;
    }

    public void updateEmail(String email) {
        this.email = email;
    }
}
