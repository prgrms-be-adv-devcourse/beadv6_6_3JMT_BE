package com.prompthub.paymentservice.domain.repository;

import com.prompthub.paymentservice.domain.model.Payment;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {
    Payment save(Payment payment);
    Optional<Payment> findById(UUID id);
}
