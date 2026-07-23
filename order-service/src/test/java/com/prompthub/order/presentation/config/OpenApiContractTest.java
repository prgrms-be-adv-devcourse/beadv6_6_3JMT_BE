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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
        assertUsesBearerAndHidesUserId(openApi, "/api/v2/cart", "get");
    }

    @Test
    @DisplayName("오류 응답은 실제 ErrorResponse 계약으로 문서화된다")
    void errorResponsesUseErrorResponseSchema() throws Exception {
        JsonNode openApi = readOpenApi();

        assertErrorResponse(openApi, "/api/v2/orders", "post", "400");
        assertErrorResponse(openApi, "/api/v2/orders", "post", "409");
        assertErrorResponse(openApi, "/api/v2/orders/users", "get", "400");
        assertErrorResponse(openApi, "/api/v2/orders/{orderId}", "get", "403");
        assertErrorResponse(openApi, "/api/v2/orders/{orderId}/content/{orderProductId}", "get", "403");
        assertErrorResponse(openApi, "/api/v2/orders/{orderId}/refund", "post", "401");
        assertErrorResponse(openApi, "/api/v2/cart", "get", "400");
        assertErrorResponse(openApi, "/api/v2/cart", "get", "401");
    }

    @Test
    @DisplayName("주문 상태와 환불 요청 스키마는 실제 입력·출력 계약을 반영한다")
    void requestAndResponseSchemasMatchRuntimeContract() throws Exception {
        JsonNode openApi = readOpenApi();
        JsonNode schemas = openApi.path("components").path("schemas");

        JsonNode pageStatus = schemas.path("PageRequestParams").path("properties").path("status");
        assertThat(textValues(pageStatus.path("enum")))
            .containsExactly("CREATED", "COMPLETED", "FAILED", "REFUND_REQUESTED", "PARTIAL_REFUNDED", "ALL_REFUNDED");
        assertThat(pageStatus.path("example").asText()).isEqualTo("COMPLETED");
        assertThat(pageStatus.path("description").asText())
            .contains("CREATED", "COMPLETED", "REFUND_REQUESTED", "PARTIAL_REFUNDED", "ALL_REFUNDED")
            .doesNotContain("PENDING", "PAID", "CANCELED");

        JsonNode detailStatus = schemas.path("OrderDetailResponse").path("properties").path("orderStatus");
        assertThat(detailStatus.path("example").asText()).isEqualTo("COMPLETED");
        assertThat(detailStatus.path("description").asText())
            .contains("CREATED", "COMPLETED", "REFUND_REQUESTED", "PARTIAL_REFUNDED", "ALL_REFUNDED")
            .doesNotContain("PENDING", "PAID", "CANCELED");

        JsonNode refundRequest = schemas.path("RefundOrderRequest");
        JsonNode orderProductIds = refundRequest.path("properties").path("orderProductIds");
        assertThat(refundRequest.path("properties").has("orderProductIdsUnique")).isFalse();
        assertThat(textValues(refundRequest.path("required")))
            .contains("orderProductIds");
        assertThat(orderProductIds.path("minItems").asInt()).isEqualTo(1);
        assertThat(orderProductIds.path("uniqueItems").asBoolean()).isTrue();

        JsonNode cartGet = openApi.path("paths").path("/api/v2/cart").path("get");
        assertThat(cartGet.path("responses").has("404")).isFalse();
        assertThat(cartGet.path("responses").path("200").path("description").asText())
            .contains("빈 장바구니");
        assertThat(schemas.path("CartResponse").path("properties").path("cartId").path("description").asText())
            .contains("null");

        JsonNode createOrder = openApi.path("paths").path("/api/v2/orders").path("post");
        assertThat(createOrder.path("responses").has("404")).isFalse();

        assertUuidExamplesAreValid(openApi);
    }

    @Test
    @DisplayName("환불 응답은 클라이언트가 확인할 수 있는 필드를 설명한다")
    void refundResponseSchemaIsDocumented() throws Exception {
        JsonNode schemas = readOpenApi().path("components").path("schemas");
        JsonNode refundResult = schemas.path("RefundResult");

        assertThat(refundResult.path("properties").has("refundRequestId")).isTrue();
        assertThat(refundResult.path("properties").path("status").path("example").asText())
            .isEqualTo("REQUESTED");
        assertThat(refundResult.path("properties").path("orderProductIds").path("minItems").asInt())
            .isEqualTo(1);

        JsonNode refundOperation = readOpenApi().path("paths").path("/api/v2/orders/{orderId}/refund").path("post");
        assertThat(refundOperation.path("parameters").get(0).path("description").asText())
            .contains("주문 ID");
    }

    private JsonNode readOpenApi() throws Exception {
        String document = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        return objectMapper.readTree(document);
    }

    private void assertErrorResponse(JsonNode openApi, String path, String method, String responseCode) {
        JsonNode response = openApi.path("paths").path(path).path(method)
            .path("responses").path(responseCode);

        assertThat(response.isMissingNode())
            .as("OpenAPI response is missing: %s %s -> %s", method, path, responseCode)
            .isFalse();
        assertThat(response.path("content").path("application/json").path("schema").path("$ref").asText())
            .isEqualTo("#/components/schemas/ErrorResponse");
    }

    private List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private void assertUuidExamplesAreValid(JsonNode node) {
        if (node.isObject()) {
            if ("uuid".equals(node.path("format").asText()) && node.has("example")) {
                assertValidUuid(node.path("example").asText());
            }
            JsonNode schema = node.path("schema");
            if ("uuid".equals(schema.path("format").asText()) && node.has("example")) {
                assertValidUuid(node.path("example").asText());
            }
        }
        node.forEach(this::assertUuidExamplesAreValid);
    }

    private void assertValidUuid(String example) {
        assertThatCode(() -> UUID.fromString(example))
            .as("UUID example must be valid: %s", example)
            .doesNotThrowAnyException();
    }

    private void assertUsesBearerAndHidesUserId(JsonNode openApi, String path, String method) {
        JsonNode operation = openApi.path("paths").path(path).path(method);

        assertThat(operation.isMissingNode())
            .as("OpenAPI operation is missing: %s %s", method, path)
            .isFalse();
        assertThat(operation.path("security").get(0).has("Bearer")).isTrue();

        List<String> parameterNames = new ArrayList<>();
        operation.path("parameters")
            .forEach(parameter -> parameterNames.add(parameter.path("name").asText()));

        assertThat(parameterNames).doesNotContain(AuthHeaders.USER_ID);
    }
}
