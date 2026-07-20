package com.prompthub.payment.infrastructure.persistence;

import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.domain.model.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentJpaRepository extends JpaRepository<Payment, UUID> {
    boolean existsByPgTxId(String pgTxId);

    boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<PaymentStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.orderId = :orderId AND p.status IN :statuses")
    Optional<Payment> findByOrderIdAndStatusInForUpdate(
        @Param("orderId") UUID orderId, @Param("statuses") Collection<PaymentStatus> statuses);
}
