package com.prompthub.user.sellersettlement.infrastructure.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.user.grpc.sellersettlement.GetPayoutStatusRequest;
import com.prompthub.user.grpc.sellersettlement.GetPayoutStatusResponse;
import com.prompthub.user.grpc.sellersettlement.GetSettlementSummaryRequest;
import com.prompthub.user.grpc.sellersettlement.GetSettlementSummaryResponse;
import com.prompthub.user.grpc.sellersettlement.SettlementPeriodType;
import com.prompthub.user.sellersettlement.application.dto.PayoutStatusResult;
import com.prompthub.user.sellersettlement.application.dto.PayoutStatusResult.PayoutStatusCountResult;
import com.prompthub.user.sellersettlement.application.dto.PayoutStatusResult.WeeklyPayoutStatusResult;
import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisPeriodType;
import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisResult;
import com.prompthub.user.sellersettlement.application.usecase.SellerSettlementAnalysisUseCase;
import com.prompthub.user.sellersettlement.domain.model.enums.SettlementDisplayStatus;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("판매자 정산 분석 gRPC 서버")
class SellerSettlementQueryGrpcServerTest {

    @Mock
    private SellerSettlementAnalysisUseCase useCase;

    private SellerSettlementQueryGrpcServer server;

    @BeforeEach
    void setUp() {
        server = new SellerSettlementQueryGrpcServer(
                useCase, new SellerSettlementGrpcResponseMapper());
    }

    @Test
    @DisplayName("요약 RPC는 context actor와 기간을 전달하고 금융값을 십진 문자열로 매핑한다")
    void mapsSummaryForContextActor() {
        UUID actorId = UUID.randomUUID();
        given(useCase.getSummary(
                actorId, SettlementAnalysisPeriodType.MONTH, "2026-07"))
                .willReturn(analysisResult());
        CapturingObserver<GetSettlementSummaryResponse> observer = new CapturingObserver<>();

        withActor(actorId, () -> server.getSettlementSummary(
                GetSettlementSummaryRequest.newBuilder()
                        .setPeriodType(SettlementPeriodType.MONTH)
                        .setPeriod("2026-07")
                        .build(),
                observer));

        assertThat(observer.error).isNull();
        assertThat(observer.completed).isTrue();
        assertThat(observer.value.getAggregate().getGrossSaleAmount()).isEqualTo("100.00");
        assertThat(observer.value.getAggregate().getPayoutAmount()).isEqualTo("85");
        assertThat(observer.value.getAggregate().getSaleCount()).isEqualTo(2);
        then(useCase).should().getSummary(
                actorId, SettlementAnalysisPeriodType.MONTH, "2026-07");
    }

    @Test
    @DisplayName("지급 상태 RPC는 지급 시각이 없으면 빈 문자열로 매핑한다")
    void mapsMissingPaidAtToEmptyString() {
        UUID actorId = UUID.randomUUID();
        YearMonth month = YearMonth.of(2026, 7);
        given(useCase.getPayoutStatus(actorId, month)).willReturn(new PayoutStatusResult(
                month,
                List.of(new PayoutStatusCountResult(SettlementDisplayStatus.APPROVED, 1)),
                List.of(new WeeklyPayoutStatusResult(
                        LocalDate.of(2026, 7, 6),
                        LocalDate.of(2026, 7, 12),
                        SettlementDisplayStatus.APPROVED,
                        null))));
        CapturingObserver<GetPayoutStatusResponse> observer = new CapturingObserver<>();

        withActor(actorId, () -> server.getPayoutStatus(
                GetPayoutStatusRequest.newBuilder().setSettlementMonth("2026-07").build(),
                observer));

        assertThat(observer.value.getWeeklySettlements(0).getPaidAt()).isEmpty();
        assertThat(observer.value.getStatusCounts(0).getStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("UNSPECIFIED 기간 유형은 INVALID_ARGUMENT로 반환한다")
    void rejectsUnspecifiedPeriodType() {
        UUID actorId = UUID.randomUUID();
        CapturingObserver<GetSettlementSummaryResponse> observer = new CapturingObserver<>();

        withActor(actorId, () -> server.getSettlementSummary(
                GetSettlementSummaryRequest.newBuilder().setPeriod("2026-07").build(),
                observer));

        assertStatus(observer.error, Status.Code.INVALID_ARGUMENT,
                "정산 기간 형식이 올바르지 않습니다.");
    }

    @Test
    @DisplayName("내부 예외는 원문 없이 INTERNAL로 반환한다")
    void hidesUnexpectedFailureMessage() {
        UUID actorId = UUID.randomUUID();
        String sensitiveMessage = "select secret_token from actor_data";
        given(useCase.getSummary(
                actorId, SettlementAnalysisPeriodType.MONTH, "2026-07"))
                .willThrow(new IllegalStateException(sensitiveMessage));
        CapturingObserver<GetSettlementSummaryResponse> observer = new CapturingObserver<>();

        withActor(actorId, () -> server.getSettlementSummary(
                GetSettlementSummaryRequest.newBuilder()
                        .setPeriodType(SettlementPeriodType.MONTH)
                        .setPeriod("2026-07")
                        .build(),
                observer));

        assertStatus(observer.error, Status.Code.INTERNAL,
                "정산 분석 데이터를 조회할 수 없습니다.");
        assertThat(observer.error.getMessage()).doesNotContain(sensitiveMessage);
    }

    private SettlementAnalysisResult analysisResult() {
        return new SettlementAnalysisResult(
                SettlementAnalysisPeriodType.MONTH,
                "2026-07",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 7, 19),
                LocalDate.of(2026, 7, 19),
                true,
                2,
                1,
                new BigDecimal("100.00"),
                new BigDecimal("40.00"),
                new BigDecimal("15.00"),
                new BigDecimal("6.00"),
                new BigDecimal("9.00"),
                new BigDecimal("85"));
    }

    private void withActor(UUID actorId, Runnable invocation) {
        Context.current()
                .withValue(SellerSettlementGrpcMetadata.ACTOR_ID, actorId)
                .run(invocation);
    }

    private void assertStatus(Throwable error, Status.Code code, String description) {
        assertThat(error).isInstanceOf(StatusRuntimeException.class);
        StatusRuntimeException statusException = (StatusRuntimeException) error;
        assertThat(statusException.getStatus().getCode()).isEqualTo(code);
        assertThat(statusException.getStatus().getDescription()).isEqualTo(description);
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
