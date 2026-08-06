package com.prompthub.user.sellersettlement.infrastructure.grpc.server.analysis;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "user.grpc.seller-settlement")
public record SellerSettlementAnalysisGrpcSecurityProperties(@NotBlank String internalToken) {
}
