package com.prompthub.order.presentation.config;

import com.prompthub.order.application.service.refund.OrderRefundService;
import com.prompthub.order.application.usecase.CartUseCase;
import com.prompthub.order.application.usecase.ConfirmDownloadUseCase;
import com.prompthub.order.application.usecase.CreateOrderUseCase;
import com.prompthub.order.application.usecase.OrderQueryUseCase;
import com.prompthub.order.global.web.AuthHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CreateOrderUseCase createOrderUseCase;

    @MockitoBean
    private ConfirmDownloadUseCase confirmDownloadUseCase;

    @MockitoBean
    private OrderQueryUseCase orderQueryUseCase;

    @MockitoBean
    private CartUseCase cartUseCase;

    @MockitoBean
    private OrderRefundService orderRefundService;

    @Test
    @DisplayName("OpenAPI 문서는 주문 서비스 정보와 Bearer 인증 스킴을 제공한다")
    void openApiDefinesOrderServiceBearerAuthentication() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.info.title").value("PromptHub Order Service API"))
            .andExpect(jsonPath("$.info.version").value("v1"))
            .andExpect(jsonPath("$.servers[0].url").value("/"))
            .andExpect(jsonPath("$.components.securitySchemes.Bearer.type").value("http"))
            .andExpect(jsonPath("$.components.securitySchemes.Bearer.scheme").value("bearer"))
            .andExpect(jsonPath("$.components.securitySchemes.Bearer.bearerFormat").value("JWT"))
            .andExpect(jsonPath("$.security[0].Bearer").isArray());
    }

    @Test
    @DisplayName("주문과 장바구니 API는 Bearer 인증을 사용하고 Gateway 사용자 헤더를 숨긴다")
    void orderAndCartOperationsUseBearerAndHideGatewayUserId() throws Exception {
        String document = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode openApi = objectMapper.readTree(document);

        assertUsesBearerAndHidesUserId(openApi, "/api/v2/orders", "post");
        assertUsesBearerAndHidesUserId(openApi, "/api/v2/cart/products", "get");
    }

    private void assertUsesBearerAndHidesUserId(JsonNode openApi, String path, String method) {
        JsonNode operation = openApi.path("paths").path(path).path(method);

        assertThat(operation.isMissingNode()).isFalse();
        assertThat(operation.path("security").get(0).has("Bearer")).isTrue();

        List<String> parameterNames = new ArrayList<>();
        operation.path("parameters")
            .forEach(parameter -> parameterNames.add(parameter.path("name").asText()));

        assertThat(parameterNames).doesNotContain(AuthHeaders.USER_ID);
    }
}
