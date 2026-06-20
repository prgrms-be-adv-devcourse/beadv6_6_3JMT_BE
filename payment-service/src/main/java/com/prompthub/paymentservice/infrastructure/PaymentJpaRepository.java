package com.prompthub.paymentservice.infrastructure;

import com.prompthub.paymentservice.domain.model.Payment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentJpaRepository extends JpaRepository<Payment, UUID> {
}
