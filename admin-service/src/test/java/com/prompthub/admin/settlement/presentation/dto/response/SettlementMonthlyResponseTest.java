package com.prompthub.admin.settlement.presentation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.admin.settlement.domain.model.Settlement;
import com.prompthub.admin.settlement.domain.model.enums.SettlementDisplayStatus;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementMonthlyResponse.Action;
import com.prompthub.admin.settlement.presentation.dto.response.SettlementMonthlyResponse.WeeklySettlement;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;

class SettlementMonthlyResponseTest {

    @ParameterizedTest
    @MethodSource("adminActions")
    void 주간상태별_어드민액션을_매핑한다(
            SettlementDisplayStatus status, List<String> expectedActions) {
        Settlement settlement = settlement(status);

        WeeklySettlement response = WeeklySettlement.from(settlement);

        assertThat(response.availableActions())
                .extracting(Action::type)
                .containsExactlyElementsOf(expectedActions);
    }

    private static Stream<Arguments> adminActions() {
        return Stream.of(
                Arguments.of(SettlementDisplayStatus.WAITING,
                        List.of("APPROVE", "HOLD", "CANCEL")),
                Arguments.of(SettlementDisplayStatus.APPROVAL_ON_HOLD,
                        List.of("RELEASE_HOLD", "CANCEL")),
                Arguments.of(SettlementDisplayStatus.APPROVED,
                        List.of("CANCEL")),
                Arguments.of(SettlementDisplayStatus.PAYOUT_REQUESTED,
                        List.of("PAYOUT", "PAYOUT_HOLD", "CANCEL")),
                Arguments.of(SettlementDisplayStatus.PAYOUT_ON_HOLD,
                        List.of("RELEASE_PAYOUT_HOLD", "CANCEL")),
                Arguments.of(SettlementDisplayStatus.PAID, List.of()),
                Arguments.of(SettlementDisplayStatus.CANCELLED, List.of()));
    }

    private Settlement settlement(SettlementDisplayStatus status) {
        try {
            Constructor<Settlement> constructor = Settlement.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            Settlement settlement = constructor.newInstance();
            ReflectionTestUtils.setField(settlement, "status", status);
            ReflectionTestUtils.setField(settlement, "refundAmount", BigDecimal.ZERO);
            return settlement;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
