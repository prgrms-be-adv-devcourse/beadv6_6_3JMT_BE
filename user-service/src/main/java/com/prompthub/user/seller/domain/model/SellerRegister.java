package com.prompthub.user.seller.domain.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
@Table(name = "seller_register")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerRegister {

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    private UUID sellerRegisterId;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SellerRegisterStatus status;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "seller_register_category", joinColumns = @JoinColumn(name = "seller_register_id"))
    @Column(name = "category", nullable = false, length = 100)
    private List<String> categories = new ArrayList<>();

    @Column(name = "introduction", columnDefinition = "TEXT")
    private String introduction;

    @Column(name = "portfolio_url", length = 500)
    private String portfolioUrl;

    @Column(name = "agreed_to_terms", nullable = false)
    private boolean agreedToTerms;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    public void approve() {
        this.status = SellerRegisterStatus.APPROVED;
        this.reviewedAt = LocalDateTime.now();
    }

    public void reject(String reason) {
        this.status = SellerRegisterStatus.REJECTED;
        this.reviewedAt = LocalDateTime.now();
        this.rejectReason = reason;
    }

    public static SellerRegister create(
            UUID userId,
            List<String> categories,
            String introduction,
            String portfolioUrl,
            boolean agreedToTerms
    ) {
        SellerRegister register = new SellerRegister();
        register.sellerRegisterId = UUID.randomUUID();
        register.userId = userId;
        register.status = SellerRegisterStatus.PENDING;
        register.categories = new ArrayList<>(categories);
        register.introduction = introduction;
        register.portfolioUrl = portfolioUrl;
        register.agreedToTerms = agreedToTerms;
        register.submittedAt = LocalDateTime.now();
        return register;
    }
}
