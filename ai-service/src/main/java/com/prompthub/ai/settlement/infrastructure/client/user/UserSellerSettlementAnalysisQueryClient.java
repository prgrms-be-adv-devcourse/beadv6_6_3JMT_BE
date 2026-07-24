package com.prompthub.ai.settlement.infrastructure.client.user;

import com.prompthub.ai.global.config.AiSettlementProperties;
import com.prompthub.ai.global.exception.AiErrorCode;
import com.prompthub.ai.global.exception.AiException;
import com.prompthub.ai.settlement.application.port.SellerSettlementAnalysisQuery;
import com.prompthub.user.grpc.sellersettlement.CompareSettlementPeriodsRequest;
import com.prompthub.user.grpc.sellersettlement.CompareSettlementPeriodsResponse;
import com.prompthub.user.grpc.sellersettlement.CountChange;
import com.prompthub.user.grpc.sellersettlement.DecimalChange;
import com.prompthub.user.grpc.sellersettlement.GetPayoutStatusRequest;
import com.prompthub.user.grpc.sellersettlement.GetPayoutStatusResponse;
import com.prompthub.user.grpc.sellersettlement.GetSettlementSummaryRequest;
import com.prompthub.user.grpc.sellersettlement.GetSettlementSummaryResponse;
import com.prompthub.user.grpc.sellersettlement.GetWeeklySettlementBreakdownRequest;
import com.prompthub.user.grpc.sellersettlement.GetWeeklySettlementBreakdownResponse;
import com.prompthub.user.grpc.sellersettlement.SellerSettlementQueryServiceGrpc.SellerSettlementQueryServiceBlockingStub;
import com.prompthub.user.grpc.sellersettlement.SettlementAggregate;
import com.prompthub.user.grpc.sellersettlement.SettlementAggregateComparison;
import com.prompthub.user.grpc.sellersettlement.SettlementPeriodType;
import com.prompthub.user.grpc.sellersettlement.WeeklyPayoutStatus;
import com.prompthub.user.grpc.sellersettlement.WeeklySettlementBucket;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class UserSellerSettlementAnalysisQueryClient implements SellerSettlementAnalysisQuery {

    private static final Metadata.Key<String> USER_ID = Metadata.Key.of(
            "x-user-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> INTERNAL_TOKEN = Metadata.Key.of(
            "x-internal-service-token", Metadata.ASCII_STRING_MARSHALLER);

    private final SellerSettlementQueryServiceBlockingStub baseStub;
    private final Duration deadline;
    private final String internalToken;
    private final MeterRegistry meterRegistry;

    @Autowired
    public UserSellerSettlementAnalysisQueryClient(
            SellerSettlementQueryServiceBlockingStub baseStub,
            AiSettlementProperties properties,
            MeterRegistry meterRegistry
    ) {
        this(baseStub, properties.userGrpcDeadline(), properties.userGrpcInternalToken(), meterRegistry);
    }

    UserSellerSettlementAnalysisQueryClient(
            SellerSettlementQueryServiceBlockingStub baseStub,
            Duration deadline,
            String internalToken,
            MeterRegistry meterRegistry
    ) {
        this.baseStub = baseStub;
        this.deadline = deadline;
        this.internalToken = internalToken;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public SettlementSummaryResult getSummary(UUID actorId, String periodType, String period) {
        GetSettlementSummaryResponse response = invoke(
                "GetSettlementSummary",
                actorId,
                stub -> stub.getSettlementSummary(GetSettlementSummaryRequest.newBuilder()
                        .setPeriodType(toPeriodType(periodType))
                        .setPeriod(period)
                        .build()));
        return new SettlementSummaryResult(
                response.getPeriodType().name(),
                response.getRequestedPeriod(),
                toAggregate(response.getAggregate()));
    }

    @Override
    public SettlementComparisonResult comparePeriods(
            UUID actorId,
            String periodType,
            String currentPeriod,
            String comparisonPeriod
    ) {
        CompareSettlementPeriodsResponse response = invoke(
                "CompareSettlementPeriods",
                actorId,
                stub -> stub.compareSettlementPeriods(CompareSettlementPeriodsRequest.newBuilder()
                        .setPeriodType(toPeriodType(periodType))
                        .setCurrentPeriod(currentPeriod)
                        .setComparisonPeriod(comparisonPeriod)
                        .build()));
        return new SettlementComparisonResult(
                response.getPeriodType().name(),
                response.getCurrentPeriod(),
                response.getComparisonPeriod(),
                toAggregate(response.getCurrent()),
                toAggregate(response.getComparison()),
                toComparison(response.getChanges()));
    }

    @Override
    public WeeklyBreakdownResult getWeeklyBreakdown(UUID actorId, String month) {
        GetWeeklySettlementBreakdownResponse response = invoke(
                "GetWeeklySettlementBreakdown",
                actorId,
                stub -> stub.getWeeklySettlementBreakdown(GetWeeklySettlementBreakdownRequest.newBuilder()
                        .setMonth(month)
                        .build()));
        return new WeeklyBreakdownResult(
                response.getRequestedMonth(),
                response.getPartial(),
                response.getDataThrough(),
                response.getWeeksList().stream().map(this::toWeeklyBucket).toList());
    }

    @Override
    public PayoutStatusResult getPayoutStatus(UUID actorId, String settlementMonth) {
        GetPayoutStatusResponse response = invoke(
                "GetPayoutStatus",
                actorId,
                stub -> stub.getPayoutStatus(GetPayoutStatusRequest.newBuilder()
                        .setSettlementMonth(settlementMonth)
                        .build()));
        return new PayoutStatusResult(
                response.getSettlementMonth(),
                response.getStatusCountsList().stream()
                        .map(count -> new PayoutStatusCountResult(count.getStatus(), count.getCount()))
                        .toList(),
                response.getWeeklySettlementsList().stream().map(this::toWeeklyPayoutStatus).toList());
    }

    private <T> T invoke(
            String rpc,
            UUID actorId,
            java.util.function.Function<SellerSettlementQueryServiceBlockingStub, T> invocation
    ) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        String status = Status.Code.OK.name();
        try {
            return invocation.apply(callStub(actorId));
        } catch (StatusRuntimeException exception) {
            outcome = "failure";
            status = exception.getStatus().getCode().name();
            meterRegistry.counter(
                    "ai.user.grpc.errors",
                    "rpc", rpc,
                    "status", status).increment();
            log.warn("User seller settlement gRPC call failed. rpc={}, status={}", rpc, status);
            // provider description, trailers와 stack trace를 사용자 예외 cause에 연결하지 않는다.
            throw new AiException(AiErrorCode.SETTLEMENT_DATA_UNAVAILABLE);
        } finally {
            meterRegistry.counter(
                    "ai.user.grpc.calls",
                    "rpc", rpc,
                    "outcome", outcome,
                    "status", status).increment();
            sample.stop(meterRegistry.timer(
                    "ai.user.grpc.duration",
                    "rpc", rpc,
                    "outcome", outcome,
                    "status", status));
        }
    }

    private SellerSettlementQueryServiceBlockingStub callStub(UUID actorId) {
        Metadata metadata = new Metadata();
        metadata.put(USER_ID, actorId.toString());
        metadata.put(INTERNAL_TOKEN, internalToken);
        return baseStub
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    private SettlementPeriodType toPeriodType(String periodType) {
        try {
            return SettlementPeriodType.valueOf(periodType);
        } catch (IllegalArgumentException | NullPointerException exception) {
            return SettlementPeriodType.SETTLEMENT_PERIOD_TYPE_UNSPECIFIED;
        }
    }

    private SettlementAggregateResult toAggregate(SettlementAggregate aggregate) {
        return new SettlementAggregateResult(
                aggregate.getIncludedStartDate(),
                aggregate.getIncludedEndDate(),
                aggregate.getDataThrough(),
                aggregate.getPartial(),
                aggregate.getSaleCount(),
                aggregate.getRefundCount(),
                aggregate.getGrossSaleAmount(),
                aggregate.getGrossRefundAmount(),
                aggregate.getSaleFeeAmount(),
                aggregate.getRefundedFeeAmount(),
                aggregate.getNetFeeAmount(),
                aggregate.getPayoutAmount());
    }

    private SettlementAggregateComparisonResult toComparison(SettlementAggregateComparison comparison) {
        return new SettlementAggregateComparisonResult(
                toCountChange(comparison.getSaleCount()),
                toCountChange(comparison.getRefundCount()),
                toDecimalChange(comparison.getGrossSaleAmount()),
                toDecimalChange(comparison.getGrossRefundAmount()),
                toDecimalChange(comparison.getSaleFeeAmount()),
                toDecimalChange(comparison.getRefundedFeeAmount()),
                toDecimalChange(comparison.getNetFeeAmount()),
                toDecimalChange(comparison.getPayoutAmount()));
    }

    private CountChangeResult toCountChange(CountChange change) {
        return new CountChangeResult(
                change.getDifference(),
                change.getChangeRatePercent(),
                change.getComparable());
    }

    private DecimalChangeResult toDecimalChange(DecimalChange change) {
        return new DecimalChangeResult(
                change.getDifference(),
                change.getChangeRatePercent(),
                change.getComparable());
    }

    private WeeklySettlementBucketResult toWeeklyBucket(WeeklySettlementBucket bucket) {
        return new WeeklySettlementBucketResult(
                bucket.getWeekStartDate(),
                bucket.getWeekEndDate(),
                bucket.getBoundaryWeek(),
                toAggregate(bucket.getAggregate()));
    }

    private WeeklyPayoutStatusResult toWeeklyPayoutStatus(WeeklyPayoutStatus status) {
        return new WeeklyPayoutStatusResult(
                status.getPeriodStartDate(),
                status.getPeriodEndDate(),
                status.getStatus(),
                status.getPaidAt());
    }
}
