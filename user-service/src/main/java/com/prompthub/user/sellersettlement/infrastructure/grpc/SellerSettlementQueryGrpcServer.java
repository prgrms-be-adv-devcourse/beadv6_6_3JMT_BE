package com.prompthub.user.sellersettlement.infrastructure.grpc;

import com.prompthub.user.grpc.sellersettlement.CompareSettlementPeriodsRequest;
import com.prompthub.user.grpc.sellersettlement.CompareSettlementPeriodsResponse;
import com.prompthub.user.grpc.sellersettlement.GetPayoutStatusRequest;
import com.prompthub.user.grpc.sellersettlement.GetPayoutStatusResponse;
import com.prompthub.user.grpc.sellersettlement.GetSettlementSummaryRequest;
import com.prompthub.user.grpc.sellersettlement.GetSettlementSummaryResponse;
import com.prompthub.user.grpc.sellersettlement.GetWeeklySettlementBreakdownRequest;
import com.prompthub.user.grpc.sellersettlement.GetWeeklySettlementBreakdownResponse;
import com.prompthub.user.grpc.sellersettlement.SellerSettlementQueryServiceGrpc;
import com.prompthub.user.grpc.sellersettlement.SettlementPeriodType;
import com.prompthub.user.sellersettlement.application.dto.SettlementAnalysisPeriodType;
import com.prompthub.user.sellersettlement.application.usecase.SellerSettlementAnalysisUseCase;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.time.DateTimeException;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SellerSettlementQueryGrpcServer
        extends SellerSettlementQueryServiceGrpc.SellerSettlementQueryServiceImplBase {

    private static final String INVALID_PERIOD_DESCRIPTION =
            "정산 기간 형식이 올바르지 않습니다.";
    private static final String INTERNAL_DESCRIPTION =
            "정산 분석 데이터를 조회할 수 없습니다.";

    private final SellerSettlementAnalysisUseCase useCase;
    private final SellerSettlementGrpcResponseMapper mapper;

    @Override
    public void getSettlementSummary(
            GetSettlementSummaryRequest request,
            StreamObserver<GetSettlementSummaryResponse> responseObserver) {
        respond("GetSettlementSummary", responseObserver, actorId -> mapper.toSummary(
                useCase.getSummary(
                        actorId,
                        toApplicationPeriodType(request.getPeriodType()),
                        request.getPeriod())));
    }

    @Override
    public void compareSettlementPeriods(
            CompareSettlementPeriodsRequest request,
            StreamObserver<CompareSettlementPeriodsResponse> responseObserver) {
        respond("CompareSettlementPeriods", responseObserver, actorId -> mapper.toComparison(
                useCase.compare(
                        actorId,
                        toApplicationPeriodType(request.getPeriodType()),
                        request.getCurrentPeriod(),
                        request.getComparisonPeriod())));
    }

    @Override
    public void getWeeklySettlementBreakdown(
            GetWeeklySettlementBreakdownRequest request,
            StreamObserver<GetWeeklySettlementBreakdownResponse> responseObserver) {
        respond("GetWeeklySettlementBreakdown", responseObserver,
                actorId -> mapper.toWeeklyBreakdown(useCase.getWeeklyBreakdown(
                        actorId, parseMonth(request.getMonth()))));
    }

    @Override
    public void getPayoutStatus(
            GetPayoutStatusRequest request,
            StreamObserver<GetPayoutStatusResponse> responseObserver) {
        respond("GetPayoutStatus", responseObserver,
                actorId -> mapper.toPayoutStatus(useCase.getPayoutStatus(
                        actorId, parseMonth(request.getSettlementMonth()))));
    }

    private <T> void respond(
            String operation,
            StreamObserver<T> responseObserver,
            Function<UUID, T> responseFactory) {
        UUID actorId = SellerSettlementGrpcMetadata.ACTOR_ID.get();
        if (actorId == null) {
            responseObserver.onError(Status.UNAUTHENTICATED
                    .withDescription("인증된 사용자 정보가 없습니다.")
                    .asRuntimeException());
            return;
        }

        try {
            responseObserver.onNext(responseFactory.apply(actorId));
            responseObserver.onCompleted();
        } catch (DateTimeException | IllegalArgumentException exception) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription(INVALID_PERIOD_DESCRIPTION)
                    .asRuntimeException());
        } catch (Exception exception) {
            log.error("판매자 정산 gRPC 처리 실패. operation={}, exceptionType={}",
                    operation, exception.getClass().getName());
            responseObserver.onError(Status.INTERNAL
                    .withDescription(INTERNAL_DESCRIPTION)
                    .asRuntimeException());
        }
    }

    private SettlementAnalysisPeriodType toApplicationPeriodType(
            SettlementPeriodType type) {
        return switch (type) {
            case MONTH -> SettlementAnalysisPeriodType.MONTH;
            case WEEK -> SettlementAnalysisPeriodType.WEEK;
            case SETTLEMENT_PERIOD_TYPE_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalArgumentException("정산 기간 유형이 필요합니다.");
        };
    }

    private YearMonth parseMonth(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}")) {
            throw new IllegalArgumentException("정산 월 형식이 올바르지 않습니다.");
        }
        try {
            return YearMonth.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("정산 월 형식이 올바르지 않습니다.", exception);
        }
    }
}
