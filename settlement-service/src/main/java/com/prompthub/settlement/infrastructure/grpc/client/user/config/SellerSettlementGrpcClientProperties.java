package com.prompthub.settlement.infrastructure.grpc.client.user.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "settlement.delivery.grpc")
public record SellerSettlementGrpcClientProperties(
        @NotBlank String internalToken,
        Duration deadline) {

    public SellerSettlementGrpcClientProperties {
        deadline = deadline == null ? Duration.ofSeconds(10) : deadline;
    }
}
