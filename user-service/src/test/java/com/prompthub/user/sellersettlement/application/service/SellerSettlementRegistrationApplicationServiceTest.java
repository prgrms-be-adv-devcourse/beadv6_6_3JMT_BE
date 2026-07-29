package com.prompthub.user.sellersettlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.prompthub.user.sellersettlement.application.dto.RegisterSellerSettlementCommand;
import com.prompthub.user.sellersettlement.application.dto.RegisterSellerSettlementCommand.Detail;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlement;
import com.prompthub.user.sellersettlement.domain.model.SellerSettlementDetail;
import com.prompthub.user.sellersettlement.domain.model.enums.SellerSettlementLineType;
import com.prompthub.user.sellersettlement.domain.repository.SellerSettlementRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellerSettlementRegistrationApplicationServiceTest {

    @Mock
    private SellerSettlementRepository repository;

    private SellerSettlementRegistrationApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SellerSettlementRegistrationApplicationService(
                repository, new SellerSettlementRegistrationMatcher());
    }

    @Test
    @DisplayName("신규 요청은 deliveryRequestId와 상세 전체를 저장한다")
    void registersNewSettlementWithDeliveryRequestId() {
        RegisterSellerSettlementCommand command = command();
        given(repository.findByDeliveryRequestId(command.deliveryRequestId()))
                .willReturn(Optional.empty());
        given(repository.findBySettlementId(command.settlementId()))
                .willReturn(Optional.empty());

        UUID result = service.register(command);

        ArgumentCaptor<SellerSettlement> captor =
                ArgumentCaptor.forClass(SellerSettlement.class);
        then(repository).should().save(captor.capture());
        SellerSettlement saved = captor.getValue();
        assertThat(result).isEqualTo(command.settlementId());
        assertThat(saved.getDeliveryRequestId()).isEqualTo(command.deliveryRequestId());
        assertThat(saved.getDetails())
                .extracting(SellerSettlementDetail::getSettlementDetailId)
                .containsExactly(command.details().getFirst().settlementDetailId());
    }

    @Test
    @DisplayName("같은 deliveryRequestId 재호출은 기존 정산을 반환하고 새로 저장하지 않는다")
    void returnsExistingSettlementForDuplicateDeliveryRequest() {
        RegisterSellerSettlementCommand command = command();
        SellerSettlement existing = settlementFrom(command, command.deliveryRequestId());
        given(repository.findByDeliveryRequestId(command.deliveryRequestId()))
                .willReturn(Optional.of(existing));

        UUID result = service.register(command);

        assertThat(result).isEqualTo(command.settlementId());
        then(repository).should(never()).save(any());
    }

    @Test
    @DisplayName("모든 값이 같은 legacy 정산은 전달 요청과 SourceLine 식별자를 연결한다")
    void linksDeliveryRequestIdToMatchingLegacySettlement() {
        RegisterSellerSettlementCommand command = command();
        SellerSettlement legacy = settlementFrom(command, null);
        given(repository.findByDeliveryRequestId(command.deliveryRequestId()))
                .willReturn(Optional.empty());
        given(repository.findBySettlementId(command.settlementId()))
                .willReturn(Optional.of(legacy));

        service.register(command);

        assertThat(legacy.getDeliveryRequestId()).isEqualTo(command.deliveryRequestId());
        assertThat(legacy.getDetails().getFirst().getSettlementSourceLineId())
                .isEqualTo(command.details().getFirst().settlementSourceLineId());
        then(repository).should().save(legacy);
    }

    @Test
    @DisplayName("값이 다른 기존 정산은 덮어쓰거나 deliveryRequestId를 연결하지 않는다")
    void doesNotOverwriteMismatchedExistingSettlement() {
        RegisterSellerSettlementCommand command = command();
        RegisterSellerSettlementCommand mismatched = new RegisterSellerSettlementCommand(
                command.deliveryRequestId(),
                command.settlementId(),
                command.sellerId(),
                command.periodStart(),
                command.periodEnd(),
                command.productCount(),
                command.grossSalesAmount(),
                command.refundAmount(),
                new BigDecimal("999.00"),
                command.settlementTotalAmount(),
                command.calculatedAt(),
                command.details());
        SellerSettlement existing = settlementFrom(mismatched, null);
        given(repository.findByDeliveryRequestId(command.deliveryRequestId()))
                .willReturn(Optional.empty());
        given(repository.findBySettlementId(command.settlementId()))
                .willReturn(Optional.of(existing));

        UUID result = service.register(command);

        assertThat(result).isEqualTo(command.settlementId());
        assertThat(existing.getDeliveryRequestId()).isNull();
        assertThat(existing.getFeeTotalAmount()).isEqualByComparingTo("999.00");
        then(repository).should(never()).save(any());
    }

    private RegisterSellerSettlementCommand command() {
        return new RegisterSellerSettlementCommand(
                UUID.randomUUID(),
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
                List.of(new Detail(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        SellerSettlementLineType.SALE,
                        new BigDecimal("100.00"),
                        new BigDecimal("0.1500"),
                        new BigDecimal("15.00"),
                        new BigDecimal("85.00"),
                        LocalDateTime.of(2026, 7, 3, 12, 0))));
    }

    private SellerSettlement settlementFrom(
            RegisterSellerSettlementCommand command,
            UUID deliveryRequestId) {
        List<SellerSettlementDetail> details = command.details().stream()
                .map(detail -> SellerSettlementDetail.seed(
                        detail.settlementDetailId(),
                        detail.orderProductId(),
                        detail.lineType(),
                        detail.lineAmount(),
                        detail.feeRate(),
                        detail.feeAmount(),
                        detail.lineSettlementAmount(),
                        detail.occurredAt()))
                .toList();
        SellerSettlement settlement = SellerSettlement.seedV2(
                command.settlementId(),
                command.sellerId(),
                command.periodStart(),
                command.periodEnd(),
                command.productCount(),
                command.grossSalesAmount(),
                command.settlementTotalAmount(),
                command.feeTotalAmount(),
                command.refundAmount(),
                command.calculatedAt(),
                details);
        if (deliveryRequestId != null) {
            settlement.linkDeliveryRequestId(deliveryRequestId);
        }
        return settlement;
    }
}
