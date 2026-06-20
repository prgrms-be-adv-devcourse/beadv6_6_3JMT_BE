package com.prompthub.paymentservice.infrastructure;

import com.prompthub.paymentservice.domain.model.Refund;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundJpaRepository extends JpaRepository<Refund, UUID> {
}
