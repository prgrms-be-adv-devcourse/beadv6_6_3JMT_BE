package com.prompthub.user.user.domain.model;

import com.prompthub.user.global.common.BaseEntity;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "\"user\"")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

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
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "terms_agreed", nullable = false)
    private boolean termsAgreed;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @Getter(AccessLevel.NONE)
    private Set<UserRole> roles = new HashSet<>();

    public static User create(
            String name,
            String email,
            String profileImageUrl,
            UserRole initialRole,
            boolean termsAgreed
    ) {
        User user = new User();
        user.userId = UUID.randomUUID();
        user.name = name;
        user.email = email;
        user.profileImageUrl = profileImageUrl;
        user.status = UserStatus.ACTIVE;
        user.roles = new HashSet<>();
        user.roles.add(initialRole);
        user.termsAgreed = termsAgreed;
        return user;
    }

    public Set<UserRole> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public UserRole getPrimaryRole() {
        if (roles.contains(UserRole.ADMIN)) return UserRole.ADMIN;
        if (roles.contains(UserRole.SELLER)) return UserRole.SELLER;
        return UserRole.BUYER;
    }

    public void addRole(UserRole role) {
        this.roles.add(role);
    }

    public void activate() {
        this.status = UserStatus.ACTIVE;
    }

    public void block() {
        this.status = UserStatus.BLOCKED;
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
