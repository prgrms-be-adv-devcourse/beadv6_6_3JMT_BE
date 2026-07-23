package com.prompthub.user.sellersettlement.infrastructure.messaging.kafka.consumer.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.user.global.exception.SettlementEventDeserializeException;
import com.prompthub.user.sellersettlement.application.event.SettlementCreatedEventV1;
import com.prompthub.user.sellersettlement.application.event.SettlementCreatedEventV2;
import com.prompthub.user.sellersettlement.application.usecase.SeedSellerSettlementUseCase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class SettlementEventConsumerTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();

    private String fixture(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (input == null) {
                throw new IOException("fixture를 찾을 수 없습니다: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void payloadVersion이_없으면_V1으로_seed하고_ack한다() throws IOException {
        SeedSellerSettlementUseCase useCase = mock(SeedSellerSettlementUseCase.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        SettlementEventConsumer consumer = new SettlementEventConsumer(objectMapper, useCase);

        consumer.consume(fixture("settlement-created-v1.json"), ack);

        then(useCase).should().seed(any(SettlementCreatedEventV1.class));
        then(ack).should().acknowledge();
    }

    @Test
    void V2는_Detail_UUID와_signed_금액을_보존해_seed하고_ack한다() throws IOException {
        SeedSellerSettlementUseCase useCase = mock(SeedSellerSettlementUseCase.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        SettlementEventConsumer consumer = new SettlementEventConsumer(objectMapper, useCase);

        consumer.consume(fixture("settlement-created-v2.json"), ack);

        ArgumentCaptor<SettlementCreatedEventV2> captor =
                ArgumentCaptor.forClass(SettlementCreatedEventV2.class);
        then(useCase).should().seed(captor.capture());
        SettlementCreatedEventV2 event = captor.getValue();
        assertThat(event.details()).extracting(detail -> detail.settlementDetailId())
                .containsExactly(
                        UUID.fromString("bba2a583-599e-41a9-88bf-4521c4b5302f"),
                        UUID.fromString("21f48bec-7b00-44b4-9d8f-5767141a759b"));
        assertThat(event.details().get(1).lineAmount()).isEqualByComparingTo("-40.00");
        assertThat(event.details().get(1).feeAmount()).isEqualByComparingTo("-6.00");
        then(ack).should().acknowledge();
    }

    @Test
    void unknown_payloadVersion은_예외를_전파하고_ack하지_않는다() throws IOException {
        SeedSellerSettlementUseCase useCase = mock(SeedSellerSettlementUseCase.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        SettlementEventConsumer consumer = new SettlementEventConsumer(objectMapper, useCase);
        String unknownVersion = fixture("settlement-created-v2.json")
                .replace("\"payloadVersion\": 2", "\"payloadVersion\": 3");

        assertThatThrownBy(() -> consumer.consume(unknownVersion, ack))
                .isInstanceOf(SettlementEventDeserializeException.class);

        then(useCase).shouldHaveNoInteractions();
        then(ack).shouldHaveNoInteractions();
    }

    @Test
    void details가_없는_V2는_예외를_전파하고_ack하지_않는다() throws IOException {
        SeedSellerSettlementUseCase useCase = mock(SeedSellerSettlementUseCase.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        SettlementEventConsumer consumer = new SettlementEventConsumer(objectMapper, useCase);
        String invalidV2 = fixture("settlement-created-v2.json")
                .replaceFirst("(?s),\\s*\"details\"\\s*:\\s*\\[.*]\\s*", "");

        assertThatThrownBy(() -> consumer.consume(invalidV2, ack))
                .isInstanceOf(SettlementEventDeserializeException.class);

        then(useCase).shouldHaveNoInteractions();
        then(ack).shouldHaveNoInteractions();
    }

    @Test
    void V2_부모_총액이_Detail_합계와_다르면_예외를_전파하고_ack하지_않는다() throws IOException {
        SeedSellerSettlementUseCase useCase = mock(SeedSellerSettlementUseCase.class);
        Acknowledgment ack = mock(Acknowledgment.class);
        SettlementEventConsumer consumer = new SettlementEventConsumer(objectMapper, useCase);
        String inconsistentV2 = fixture("settlement-created-v2.json")
                .replace("\"totalAmount\": 100.00", "\"totalAmount\": 101.00");

        assertThatThrownBy(() -> consumer.consume(inconsistentV2, ack))
                .isInstanceOf(SettlementEventDeserializeException.class);

        then(useCase).shouldHaveNoInteractions();
        then(ack).shouldHaveNoInteractions();
    }

    @Test
    void eventType_형식과_지원여부를_구분한다() throws IOException {
        SeedSellerSettlementUseCase malformedUseCase = mock(SeedSellerSettlementUseCase.class);
        Acknowledgment malformedAck = mock(Acknowledgment.class);
        SettlementEventConsumer malformedConsumer =
                new SettlementEventConsumer(objectMapper, malformedUseCase);
        String blankType = fixture("settlement-created-v1.json")
                .replace("SETTLEMENT_CREATED", "   ");

        assertThatThrownBy(() -> malformedConsumer.consume(blankType, malformedAck))
                .isInstanceOf(SettlementEventDeserializeException.class);

        then(malformedUseCase).shouldHaveNoInteractions();
        then(malformedAck).shouldHaveNoInteractions();

        SeedSellerSettlementUseCase unsupportedUseCase = mock(SeedSellerSettlementUseCase.class);
        Acknowledgment unsupportedAck = mock(Acknowledgment.class);
        SettlementEventConsumer unsupportedConsumer =
                new SettlementEventConsumer(objectMapper, unsupportedUseCase);
        String unsupportedType = fixture("settlement-created-v1.json")
                .replace("SETTLEMENT_CREATED", "SETTLEMENT_UNKNOWN");

        unsupportedConsumer.consume(unsupportedType, unsupportedAck);

        then(unsupportedUseCase).shouldHaveNoInteractions();
        then(unsupportedAck).should().acknowledge();
    }
}
