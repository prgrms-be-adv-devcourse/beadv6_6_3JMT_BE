package com.prompthub.admin.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.settlement.dto.SettlementDeliveryListQuery;
import com.prompthub.admin.settlement.dto.response.SettlementDeliveryListResponse;
import com.prompthub.admin.settlement.dto.response.SettlementDeliverySummaryResponse;
import com.prompthub.admin.settlement.entity.SettlementDelivery;
import com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus;
import com.prompthub.admin.settlement.infrastructure.kubernetes.SettlementDeliveryRetryJobAlreadyExistsException;
import com.prompthub.admin.settlement.infrastructure.kubernetes.SettlementDeliveryRetryJobClient;
import com.prompthub.admin.settlement.repository.SettlementDeliveryQueryRepository;
import com.prompthub.admin.settlement.repository.SettlementDeliveryQueryRepository.DeliveryPage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementDeliveryServiceTest {

    private static final UUID DELIVERY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000663");
    private final SettlementDeliveryQueryRepository repository =
            mock(SettlementDeliveryQueryRepository.class);
    private final SettlementDeliveryRetryJobClient retryJobClient =
            mock(SettlementDeliveryRetryJobClient.class);
    private final SettlementDeliveryService service =
            new SettlementDeliveryService(repository, retryJobClient);

    @Test
    @DisplayName("전달 실패 건은 실행 중인 Job이 없으면 RETRY 액션을 제공한다")
    void returnsRetryActionForFailedDelivery() {
        SettlementDelivery delivery = delivery(SettlementDeliveryStatus.DELIVERY_FAILED);
        SettlementDeliveryListQuery query =
                new SettlementDeliveryListQuery(null, true, null, 0, 20);
        when(repository.findPage(query))
                .thenReturn(new DeliveryPage(List.of(delivery), 1L));
        when(retryJobClient.findActiveDeliveryIds()).thenReturn(Set.of());

        SettlementDeliveryListResponse response = service.getList(query);

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.settlementDeliveryId()).isEqualTo(DELIVERY_ID);
            assertThat(item.retryInProgress()).isFalse();
            assertThat(item.availableActions()).containsExactly("RETRY");
        });
        assertThat(response.totalElements()).isEqualTo(1L);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("불일치 건과 실행 중인 전달 실패 건은 RETRY 액션을 제공하지 않는다")
    void hidesRetryActionForMismatchAndActiveRetry() {
        SettlementDelivery mismatch = delivery(SettlementDeliveryStatus.MISMATCH);
        SettlementDelivery failed = delivery(SettlementDeliveryStatus.DELIVERY_FAILED);
        SettlementDeliveryListQuery query =
                new SettlementDeliveryListQuery(null, true, null, 0, 20);
        when(repository.findPage(query))
                .thenReturn(new DeliveryPage(List.of(mismatch, failed), 2L));
        when(retryJobClient.findActiveDeliveryIds()).thenReturn(Set.of(DELIVERY_ID));

        SettlementDeliveryListResponse response = service.getList(query);

        assertThat(response.items())
                .allSatisfy(item -> assertThat(item.availableActions()).isEmpty());
        assertThat(response.items())
                .allSatisfy(item -> assertThat(item.retryInProgress()).isTrue());
    }

    @Test
    @DisplayName("전달 상태와 실행 중 Job 건수를 요약한다")
    void summarizesDeliveryStatuses() {
        when(repository.countByStatus()).thenReturn(Map.of(
                SettlementDeliveryStatus.CALCULATED, 2L,
                SettlementDeliveryStatus.RECONCILED, 8L,
                SettlementDeliveryStatus.DELIVERY_FAILED, 3L,
                SettlementDeliveryStatus.MISMATCH, 1L));
        when(retryJobClient.findActiveDeliveryIds())
                .thenReturn(Set.of(DELIVERY_ID));

        SettlementDeliverySummaryResponse response = service.getSummary();

        assertThat(response.calculatedCount()).isEqualTo(2L);
        assertThat(response.reconciledCount()).isEqualTo(8L);
        assertThat(response.deliveryFailedCount()).isEqualTo(3L);
        assertThat(response.mismatchCount()).isEqualTo(1L);
        assertThat(response.retryInProgressCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("전달 실패 건의 단발성 재전송 Job을 요청한다")
    void requestsRetryJobForFailedDelivery() {
        SettlementDelivery delivery = delivery(SettlementDeliveryStatus.DELIVERY_FAILED);
        when(repository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery));
        when(retryJobClient.isActive(DELIVERY_ID)).thenReturn(false);

        service.retry(DELIVERY_ID);

        verify(retryJobClient).launch(DELIVERY_ID, 3);
    }

    @Test
    @DisplayName("존재하지 않는 전달 건은 404 예외로 변환한다")
    void rejectsMissingDelivery() {
        when(repository.findById(DELIVERY_ID)).thenReturn(Optional.empty());

        AdminException exception = catchThrowableOfType(
                AdminException.class,
                () -> service.retry(DELIVERY_ID));

        assertThat(exception.getErrorCode())
                .isEqualTo(AdminErrorCode.SETTLEMENT_DELIVERY_NOT_FOUND);
        verifyNoInteractions(retryJobClient);
    }

    @Test
    @DisplayName("전달 실패가 아니거나 이미 실행 중이면 409 예외로 변환한다")
    void rejectsInvalidOrActiveRetry() {
        SettlementDelivery mismatch = delivery(SettlementDeliveryStatus.MISMATCH);
        when(repository.findById(DELIVERY_ID)).thenReturn(Optional.of(mismatch));

        AdminException invalidState = catchThrowableOfType(
                AdminException.class,
                () -> service.retry(DELIVERY_ID));

        assertThat(invalidState.getErrorCode())
                .isEqualTo(
                        AdminErrorCode.SETTLEMENT_DELIVERY_RETRY_NOT_ALLOWED);

        SettlementDelivery failed = delivery(SettlementDeliveryStatus.DELIVERY_FAILED);
        when(repository.findById(DELIVERY_ID)).thenReturn(Optional.of(failed));
        when(retryJobClient.isActive(DELIVERY_ID)).thenReturn(true);

        AdminException activeRetry = catchThrowableOfType(
                AdminException.class,
                () -> service.retry(DELIVERY_ID));

        assertThat(activeRetry.getErrorCode())
                .isEqualTo(
                        AdminErrorCode.SETTLEMENT_DELIVERY_RETRY_ALREADY_RUNNING);
        verify(retryJobClient, never()).launch(DELIVERY_ID, 3);
    }

    @Test
    @DisplayName("조회와 생성 사이에 같은 Job이 생성돼도 409 예외로 변환한다")
    void rejectsRetryWhenJobCreationRaces() {
        SettlementDelivery failed = delivery(
                SettlementDeliveryStatus.DELIVERY_FAILED);
        when(repository.findById(DELIVERY_ID)).thenReturn(Optional.of(failed));
        when(retryJobClient.isActive(DELIVERY_ID)).thenReturn(false);
        org.mockito.Mockito.doThrow(
                        new SettlementDeliveryRetryJobAlreadyExistsException(
                                "already exists",
                                new RuntimeException()))
                .when(retryJobClient)
                .launch(DELIVERY_ID, 3);

        AdminException exception = catchThrowableOfType(
                AdminException.class,
                () -> service.retry(DELIVERY_ID));

        assertThat(exception.getErrorCode())
                .isEqualTo(
                        AdminErrorCode.SETTLEMENT_DELIVERY_RETRY_ALREADY_RUNNING);
    }

    private SettlementDelivery delivery(SettlementDeliveryStatus status) {
        SettlementDelivery delivery = mock(SettlementDelivery.class);
        when(delivery.getSettlementDeliveryId()).thenReturn(DELIVERY_ID);
        when(delivery.getSettlementId()).thenReturn(UUID.randomUUID());
        when(delivery.getDeliveryRequestId()).thenReturn(UUID.randomUUID());
        when(delivery.getStatus()).thenReturn(status);
        when(delivery.canRetry())
                .thenReturn(status == SettlementDeliveryStatus.DELIVERY_FAILED);
        when(delivery.getAttemptCount()).thenReturn(3);
        when(delivery.getStatusReason()).thenReturn("gRPC UNAVAILABLE: attempts=3");
        when(delivery.getFirstAttemptAt()).thenReturn(LocalDateTime.of(2026, 7, 29, 10, 0));
        when(delivery.getLastAttemptAt()).thenReturn(LocalDateTime.of(2026, 7, 29, 10, 0, 4));
        return delivery;
    }
}
