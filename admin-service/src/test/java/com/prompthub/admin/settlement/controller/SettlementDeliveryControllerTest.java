package com.prompthub.admin.settlement.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prompthub.admin.global.exception.AdminErrorCode;
import com.prompthub.admin.global.exception.AdminException;
import com.prompthub.admin.settlement.dto.SettlementDeliveryListQuery;
import com.prompthub.admin.settlement.dto.response.SettlementDeliveryListResponse;
import com.prompthub.admin.settlement.dto.response.SettlementDeliverySummaryResponse;
import com.prompthub.admin.settlement.entity.enums.SettlementDeliveryStatus;
import com.prompthub.admin.settlement.service.SettlementDeliveryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SettlementDeliveryController.class)
@ActiveProfiles("test")
class SettlementDeliveryControllerTest {

    private static final UUID DELIVERY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000663");
    private static final UUID ACTOR_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SettlementDeliveryService service;

    @Test
    @DisplayName("문제 건과 식별자 조건으로 정산 전달 목록을 조회한다")
    void getsDeliveryList() throws Exception {
        when(service.getList(any()))
                .thenReturn(new SettlementDeliveryListResponse(
                        List.of(), 0L, 0, 20));

        UUID identifier = UUID.randomUUID();
        mockMvc.perform(get("/api/v2/admin/settlements/deliveries")
                        .param("problemOnly", "true")
                        .param("identifier", identifier.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.size").value(20));

        ArgumentCaptor<SettlementDeliveryListQuery> captor =
                ArgumentCaptor.forClass(SettlementDeliveryListQuery.class);
        verify(service).getList(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().problemOnly())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().identifier())
                .isEqualTo(identifier);
    }

    @Test
    @DisplayName("상태별 정산 전달 요약을 조회한다")
    void getsDeliverySummary() throws Exception {
        when(service.getSummary())
                .thenReturn(new SettlementDeliverySummaryResponse(2, 8, 3, 1, 1));

        mockMvc.perform(get("/api/v2/admin/settlements/deliveries/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.calculatedCount").value(2))
                .andExpect(jsonPath("$.data.deliveryFailedCount").value(3))
                .andExpect(jsonPath("$.data.retryInProgressCount").value(1));
    }

    @Test
    @DisplayName("전달 실패 재전송 요청을 접수하면 202를 반환한다")
    void acceptsDeliveryRetry() throws Exception {
        mockMvc.perform(post(
                        "/api/v2/admin/settlements/deliveries/{deliveryId}/retry",
                        DELIVERY_ID)
                        .header("X-User-Id", ACTOR_ID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("success"));

        verify(service).retry(DELIVERY_ID);
    }

    @Test
    @DisplayName("요청 수행자 ID가 없으면 재전송 요청을 거부한다")
    void rejectsRetryWithoutActorId() throws Exception {
        mockMvc.perform(post(
                        "/api/v2/admin/settlements/deliveries/{deliveryId}/retry",
                        DELIVERY_ID))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("재전송 대상이 없으면 404를 반환한다")
    void returnsNotFoundForMissingDelivery() throws Exception {
        org.mockito.Mockito.doThrow(
                        new AdminException(
                                AdminErrorCode.SETTLEMENT_DELIVERY_NOT_FOUND))
                .when(service)
                .retry(DELIVERY_ID);

        mockMvc.perform(post(
                        "/api/v2/admin/settlements/deliveries/{deliveryId}/retry",
                        DELIVERY_ID)
                        .header("X-User-Id", ACTOR_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("A-011"));
    }

    @Test
    @DisplayName("재전송 불가 상태나 중복 실행은 409를 반환한다")
    void returnsConflictForInvalidRetry() throws Exception {
        org.mockito.Mockito.doThrow(
                        new AdminException(
                                AdminErrorCode
                                        .SETTLEMENT_DELIVERY_RETRY_NOT_ALLOWED))
                .when(service)
                .retry(DELIVERY_ID);

        mockMvc.perform(post(
                        "/api/v2/admin/settlements/deliveries/{deliveryId}/retry",
                        DELIVERY_ID)
                        .header("X-User-Id", ACTOR_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("A-012"));
    }

    @Test
    @DisplayName("잘못된 필터와 페이지 요청은 400을 반환한다")
    void rejectsInvalidListParameters() throws Exception {
        mockMvc.perform(get("/api/v2/admin/settlements/deliveries")
                        .param("status", "NOPE"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v2/admin/settlements/deliveries")
                        .param("size", "101"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v2/admin/settlements/deliveries")
                        .param("status", SettlementDeliveryStatus.RECONCILED.name()))
                .andExpect(status().isOk());
    }
}
