package com.prompthub.settlement.infrastructure.client.user.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "settlement.delivery.grpc")
public record SellerSettlementCommandGrpcProperties(
        @NotBlank String internalToken,
        Duration deadline) {

    public SellerSettlementCommandGrpcProperties {
        deadline = deadline == null ? Duration.ofSeconds(10) : deadline;
    }
}
