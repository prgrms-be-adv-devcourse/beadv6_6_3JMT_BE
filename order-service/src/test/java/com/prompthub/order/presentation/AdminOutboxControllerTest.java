package com.prompthub.order.presentation;

import com.prompthub.order.application.dto.outbox.OutboxEventSummary;
import com.prompthub.order.application.dto.outbox.OutboxRedriveResult;
import com.prompthub.order.application.usecase.OutboxAdminUseCase;
import com.prompthub.order.domain.enums.OutboxEventStatus;
import com.prompthub.order.global.exception.ErrorCode;
import com.prompthub.order.global.exception.GlobalExceptionHandler;
import com.prompthub.order.global.exception.OrderException;
import com.prompthub.order.global.web.AuthHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminOutboxControllerTest {

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000901");
    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000902");
    private static final UUID AGGREGATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000903");
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 8, 5, 10, 0);
    private static final LocalDateTime LAST_ATTEMPT_AT = LocalDateTime.of(2026, 8, 5, 10, 5);

    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private OutboxAdminUseCase outboxAdminUseCase;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders.standaloneSetup(new AdminOutboxController(outboxAdminUseCase))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
    }

    @Nested
    @DisplayName("실패한 Outbox 이벤트 조회 (GET /api/v1/admin/outbox-events)")
    class GetFailedOutboxEvents {

        @Test
        @DisplayName("실패 이벤트와 1-based 페이지 메타데이터를 반환한다")
        void getFailedEvents_returnsDataAndMetaWithoutPayload() throws Exception {
            OutboxEventSummary summary = failedEvent();
            when(outboxAdminUseCase.getFailedEvents(1, 20)).thenReturn(
                new PageImpl<>(List.of(summary), PageRequest.of(0, 20), 21)
            );

            mockMvc.perform(get("/api/v1/admin/outbox-events")
                    .param("page", "1")
                    .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].eventId").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.data[0].aggregateId").value(AGGREGATE_ID.toString()))
				.andExpect(jsonPath("$.data[0].eventType").value("ORDER_PAID"))
                .andExpect(jsonPath("$.data[0].status").value("FAILED"))
                .andExpect(jsonPath("$.data[0].retryCount").value(3))
                .andExpect(jsonPath("$.data[0].lastError").value("Kafka timeout"))
                .andExpect(jsonPath("$.data[0].payload").doesNotExist())
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.total").value(21))
                .andExpect(jsonPath("$.meta.hasNext").value(true));

            verify(outboxAdminUseCase).getFailedEvents(1, 20);
        }

        @Test
        @DisplayName("페이지가 0이면 400 Bad Request를 반환한다")
        void getFailedEvents_withZeroPage_badRequest() throws Exception {
            mockMvc.perform(get("/api/v1/admin/outbox-events").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

            verifyNoInteractions(outboxAdminUseCase);
        }

        @Test
        @DisplayName("페이지 크기가 101이면 400 Bad Request를 반환한다")
        void getFailedEvents_withOversizePage_badRequest() throws Exception {
            mockMvc.perform(get("/api/v1/admin/outbox-events").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

            verifyNoInteractions(outboxAdminUseCase);
        }
    }

    @Nested
    @DisplayName("실패한 Outbox 이벤트 재처리 (POST /api/v1/admin/outbox-events/{eventId}/redrive)")
    class RedriveOutboxEvent {

        @Test
        @DisplayName("Gateway 관리자 ID와 사유로 재처리를 요청하고 PENDING 응답을 반환한다")
        void redrive_returnsPending() throws Exception {
            when(outboxAdminUseCase.redrive(eq(EVENT_ID), eq(ADMIN_ID), eq("Kafka 복구 확인")))
                .thenReturn(new OutboxRedriveResult(EVENT_ID, OutboxEventStatus.PENDING, LAST_ATTEMPT_AT));

            mockMvc.perform(post("/api/v1/admin/outbox-events/{eventId}/redrive", EVENT_ID)
                    .header(AuthHeaders.USER_ID, ADMIN_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Kafka 복구 확인\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.eventId").value(EVENT_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("PENDING"));

            verify(outboxAdminUseCase).redrive(EVENT_ID, ADMIN_ID, "Kafka 복구 확인");
        }

        @Test
        @DisplayName("X-User-Id 헤더가 없으면 401 Unauthorized를 반환한다")
        void redrive_withoutUserIdHeader_unauthorized() throws Exception {
            mockMvc.perform(post("/api/v1/admin/outbox-events/{eventId}/redrive", EVENT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\"Kafka 복구 확인\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_AUTHENTICATION.getCode()));

            verifyNoInteractions(outboxAdminUseCase);
        }

        @Test
        @DisplayName("사유가 비어 있으면 400 Bad Request를 반환한다")
        void redrive_withBlankReason_badRequest() throws Exception {
            mockMvc.perform(post("/api/v1/admin/outbox-events/{eventId}/redrive", EVENT_ID)
                    .header(AuthHeaders.USER_ID, ADMIN_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

            verifyNoInteractions(outboxAdminUseCase);
        }

        @Test
        @DisplayName("사유가 500자를 초과하면 400 Bad Request를 반환한다")
        void redrive_withLongReason_badRequest() throws Exception {
            String request = objectMapper.writeValueAsString(new ReasonRequest("a".repeat(501)));

            mockMvc.perform(post("/api/v1/admin/outbox-events/{eventId}/redrive", EVENT_ID)
                    .header(AuthHeaders.USER_ID, ADMIN_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

            verifyNoInteractions(outboxAdminUseCase);
        }

        @Test
        @DisplayName("사유가 보조 평면 문자 500개이면 200 OK를 반환한다")
        void redrive_withFiveHundredSupplementaryCharacters_ok() throws Exception {
            String reason = "\uD83D\uDE80".repeat(500);
            when(outboxAdminUseCase.redrive(eq(EVENT_ID), eq(ADMIN_ID), eq(reason)))
                .thenReturn(new OutboxRedriveResult(EVENT_ID, OutboxEventStatus.PENDING, LAST_ATTEMPT_AT));

            mockMvc.perform(post("/api/v1/admin/outbox-events/{eventId}/redrive", EVENT_ID)
                    .header(AuthHeaders.USER_ID, ADMIN_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new ReasonRequest(reason))))
                .andExpect(status().isOk());

            verify(outboxAdminUseCase).redrive(EVENT_ID, ADMIN_ID, reason);
        }

        @Test
        @DisplayName("사유가 보조 평면 문자 501개이면 400 Bad Request를 반환한다")
        void redrive_withFiveHundredOneSupplementaryCharacters_badRequest() throws Exception {
            String reason = "\uD83D\uDE80".repeat(501);

            mockMvc.perform(post("/api/v1/admin/outbox-events/{eventId}/redrive", EVENT_ID)
                    .header(AuthHeaders.USER_ID, ADMIN_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new ReasonRequest(reason))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT_VALUE.getCode()));

            verifyNoInteractions(outboxAdminUseCase);
        }

        @Test
        @DisplayName("Outbox 이벤트가 없으면 O020과 404 Not Found를 반환한다")
        void redrive_whenEventMissing_notFound() throws Exception {
            when(outboxAdminUseCase.redrive(EVENT_ID, ADMIN_ID, "Kafka 복구 확인"))
                .thenThrow(new OrderException(ErrorCode.OUTBOX_EVENT_NOT_FOUND));

            mockMvc.perform(redriveRequest("Kafka 복구 확인"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("O020"));
        }

        @Test
        @DisplayName("재처리할 수 없는 상태이면 O021과 409 Conflict를 반환한다")
        void redrive_whenStatusDoesNotAllowRedrive_conflict() throws Exception {
            when(outboxAdminUseCase.redrive(EVENT_ID, ADMIN_ID, "Kafka 복구 확인"))
                .thenThrow(new OrderException(ErrorCode.OUTBOX_REDRIVE_NOT_ALLOWED));

            mockMvc.perform(redriveRequest("Kafka 복구 확인"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("O021"));
        }
    }

    private OutboxEventSummary failedEvent() {
        return new OutboxEventSummary(
            EVENT_ID,
            AGGREGATE_ID,
            "ORDER_PAID",
            OutboxEventStatus.FAILED,
            3,
            OCCURRED_AT,
            LAST_ATTEMPT_AT,
            "Kafka timeout"
        );
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder redriveRequest(String reason) {
        return post("/api/v1/admin/outbox-events/{eventId}/redrive", EVENT_ID)
            .header(AuthHeaders.USER_ID, ADMIN_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"" + reason + "\"}");
    }

    private record ReasonRequest(String reason) {
    }
}
