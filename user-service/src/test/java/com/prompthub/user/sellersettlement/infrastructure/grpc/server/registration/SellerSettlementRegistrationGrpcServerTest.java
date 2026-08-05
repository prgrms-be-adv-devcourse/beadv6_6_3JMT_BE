package com.prompthub.user.sellersettlement.infrastructure.grpc.server.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.prompthub.user.grpc.sellersettlement.command.RegisterSellerSettlementRequest;
import com.prompthub.user.grpc.sellersettlement.command.RegisterSellerSettlementResponse;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementDetailSnapshot;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementLineType;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementSnapshot;
import com.prompthub.user.sellersettlement.application.dto.RegisteredSellerSettlementSnapshot;
import com.prompthub.user.sellersettlement.application.usecase.RegisterSellerSettlementUseCase;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SellerSettlementRegistrationGrpcServerTest {

    @Mock
    private RegisterSellerSettlementUseCase useCase;

    private SellerSettlementRegistrationGrpcServer server;

    @BeforeEach
    void setUp() {
        server = new SellerSettlementRegistrationGrpcServer(
                useCase,
                new SellerSettlementRegistrationGrpcRequestMapper(),
                new SellerSettlementRegistrationGrpcResponseMapper());
    }

    @Test
    @DisplayName("등록 RPC는 저장된 본체와 상세 전체를 응답한다")
    void returnsStoredSnapshot() {
        RegisterSellerSettlementRequest request = request();
        UUID deliveryRequestId = UUID.fromString(request.getDeliveryRequestId());
        UUID settlementId = UUID.fromString(request.getSettlement().getSettlementId());
        RegisteredSellerSettlementSnapshot stored = new RegisteredSellerSettlementSnapshot(
                deliveryRequestId,
                settlementId,
                UUID.fromString(request.getSettlement().getSellerId()),
                LocalDate.parse(request.getSettlement().getPeriodStart()),
                LocalDate.parse(request.getSettlement().getPeriodEnd()),
                1,
                new BigDecimal("100.00"),
                BigDecimal.ZERO,
                new BigDecimal("15.00"),
                new BigDecimal("85.00"),
                LocalDateTime.parse(request.getSettlement().getCalculatedAt()),
                List.of(new RegisteredSellerSettlementSnapshot.Detail(
                        UUID.fromString(request.getSettlement().getDetails(0)
                                .getSettlementDetailId()),
                        UUID.fromString(request.getSettlement().getDetails(0)
                                .getSettlementSourceLineId()),
                        UUID.fromString(request.getSettlement().getDetails(0)
                                .getOrderProductId()),
                        com.prompthub.user.sellersettlement.domain.model.enums
                                .SellerSettlementLineType.SALE,
                        new BigDecimal("100.00"),
                        new BigDecimal("0.1500"),
                        new BigDecimal("15.00"),
                        new BigDecimal("85.00"),
                        LocalDateTime.parse(request.getSettlement().getDetails(0)
                                .getOccurredAt()))));
        given(useCase.register(org.mockito.ArgumentMatchers.any())).willReturn(stored);
        CapturingObserver<RegisterSellerSettlementResponse> observer =
                new CapturingObserver<>();

        server.registerSellerSettlement(request, observer);

        assertThat(observer.error).isNull();
        assertThat(observer.completed).isTrue();
        assertThat(observer.value.getStoredSettlement().getDeliveryRequestId())
                .isEqualTo(deliveryRequestId.toString());
        assertThat(observer.value.getStoredSettlement().getGrossSalesAmount())
                .isEqualTo("100.00");
        assertThat(observer.value.getStoredSettlement().getDetailsCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("잘못된 UUID 요청은 INVALID_ARGUMENT로 반환한다")
    void rejectsMalformedUuid() {
        RegisterSellerSettlementRequest request = request().toBuilder()
                .setDeliveryRequestId("not-a-uuid")
                .build();
        CapturingObserver<RegisterSellerSettlementResponse> observer =
                new CapturingObserver<>();

        server.registerSellerSettlement(request, observer);

        assertThat(observer.error).isInstanceOf(StatusRuntimeException.class);
        StatusRuntimeException error = (StatusRuntimeException) observer.error;
        assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }

    private RegisterSellerSettlementRequest request() {
        SellerSettlementDetailSnapshot detail =
                SellerSettlementDetailSnapshot.newBuilder()
                        .setSettlementDetailId(UUID.randomUUID().toString())
                        .setSettlementSourceLineId(UUID.randomUUID().toString())
                        .setOrderProductId(UUID.randomUUID().toString())
                        .setLineType(SellerSettlementLineType.SALE)
                        .setLineAmount("100.00")
                        .setFeeRate("0.1500")
                        .setFeeAmount("15.00")
                        .setLineSettlementAmount("85.00")
                        .setOccurredAt("2026-07-03T12:00:00")
                        .build();
        SellerSettlementSnapshot settlement = SellerSettlementSnapshot.newBuilder()
                .setSettlementId(UUID.randomUUID().toString())
                .setSellerId(UUID.randomUUID().toString())
                .setPeriodStart("2026-07-01")
                .setPeriodEnd("2026-07-07")
                .setProductCount(1)
                .setGrossSalesAmount("100.00")
                .setRefundAmount("0.00")
                .setFeeTotalAmount("15.00")
                .setSettlementTotalAmount("85.00")
                .setCalculatedAt("2026-07-08T02:00:00")
                .addDetails(detail)
                .build();
        return RegisterSellerSettlementRequest.newBuilder()
                .setDeliveryRequestId(UUID.randomUUID().toString())
                .setSettlement(settlement)
                .build();
    }

    private static final class CapturingObserver<T> implements StreamObserver<T> {

        private T value;
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onCompleted() {
            completed = true;
        }
    }
}
