package com.prompthub.user.sellersettlement.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.user.grpc.sellersettlement.command.RegisterSellerSettlementResponse;
import com.prompthub.user.sellersettlement.application.dto.RegisteredSellerSettlementSnapshot;
import com.prompthub.user.sellersettlement.domain.model.enums.SellerSettlementLineType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SellerSettlementCommandGrpcResponseMapperTest {

    private final SellerSettlementCommandGrpcResponseMapper mapper =
            new SellerSettlementCommandGrpcResponseMapper();

    @Test
    @DisplayName("기존 상세의 source line ID가 없으면 optional 응답 필드를 비워 둔다")
    void omitsMissingSettlementSourceLineId() {
        RegisteredSellerSettlementSnapshot stored =
                new RegisteredSellerSettlementSnapshot(
                        null,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        LocalDate.of(2026, 7, 1),
                        LocalDate.of(2026, 7, 7),
                        1,
                        new BigDecimal("100.00"),
                        BigDecimal.ZERO,
                        new BigDecimal("15.00"),
                        new BigDecimal("85.00"),
                        LocalDateTime.of(2026, 7, 8, 2, 0),
                        List.of(new RegisteredSellerSettlementSnapshot.Detail(
                                UUID.randomUUID(),
                                null,
                                UUID.randomUUID(),
                                SellerSettlementLineType.SALE,
                                new BigDecimal("100.00"),
                                new BigDecimal("0.1500"),
                                new BigDecimal("15.00"),
                                new BigDecimal("85.00"),
                                LocalDateTime.of(2026, 7, 3, 12, 0))));

        RegisterSellerSettlementResponse response = mapper.toResponse(stored);

        assertThat(response.getStoredSettlement().hasDeliveryRequestId()).isFalse();
        assertThat(response.getStoredSettlement().getDetails(0)
                .hasSettlementSourceLineId()).isFalse();
    }
}
