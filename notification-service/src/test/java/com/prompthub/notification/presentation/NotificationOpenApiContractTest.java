package com.prompthub.notification.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class NotificationOpenApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void openApi_exposesSettingsAndDeletionContracts() throws Exception {
        String document = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode openApi = objectMapper.readTree(document);
        JsonNode paths = openApi.path("paths");

        assertThat(paths.path("/api/v1/notifications/settings").path("get").isMissingNode())
            .isFalse();
        assertThat(paths.path("/api/v1/notifications/settings/{category}").path("put").isMissingNode())
            .isFalse();
        assertResponseCodes(
            paths.path("/api/v1/notifications/settings").path("get"),
            "200", "400"
        );
        assertResponseCodes(
            paths.path("/api/v1/notifications/settings/{category}").path("put"),
            "200", "400"
        );
        assertResponseCodes(
            paths.path("/api/v1/notifications/{notificationId}").path("delete"),
            "204", "400", "404"
        );
        assertResponseCodes(
            paths.path("/api/v1/notifications").path("delete"),
            "204", "400"
        );
        assertThat(paths.path("/api/v1/notifications/settings").path("get").path("parameters")
            .findValues("name")).noneMatch(node -> node.asText().equals("X-User-Id"));
        assertThat(paths.path("/api/v1/notifications/settings/{category}").path("put")
            .path("parameters").findValues("name"))
            .noneMatch(node -> node.asText().equals("X-User-Id"));
        assertThat(paths.path("/api/v1/notifications/{notificationId}").path("delete")
            .path("parameters").findValues("name"))
            .noneMatch(node -> node.asText().equals("X-User-Id"));
        assertThat(paths.path("/api/v1/notifications").path("delete").path("parameters")
            .findValues("name")).noneMatch(node -> node.asText().equals("X-User-Id"));

        JsonNode requestSchema = openApi.path("components").path("schemas")
            .path("UpdateNotificationSettingRequest");
        assertThat(requestSchema.path("required")).anySatisfy(node ->
            assertThat(node.asText()).isEqualTo("enabled")
        );
        assertThat(requestSchema.path("properties").path("enabled").path("type").asText())
            .isEqualTo("boolean");
    }

    @Test
    void openApi_settingsSuccessResponsesMatchApiResultEnvelopeAndNestedData() throws Exception {
        String document = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
        JsonNode openApi = objectMapper.readTree(document);

        JsonNode settingsEnvelope = successSchema(
            openApi,
            "/api/v1/notifications/settings",
            "get"
        );
        assertApiResultEnvelope(settingsEnvelope);
        JsonNode settingsData = resolveSchema(
            openApi,
            settingsEnvelope.path("properties").path("data")
        );
        JsonNode settingItem = resolveSchema(
            openApi,
            settingsData.path("properties").path("settings").path("items")
        );
        assertThat(settingsData.path("properties").propertyNames())
            .containsExactly("settings");
        assertThat(settingsData.path("properties").path("settings").path("type").asText())
            .isEqualTo("array");
        assertThat(settingItem.path("properties").propertyNames())
            .containsExactlyInAnyOrder("category", "enabled", "configurable");

        JsonNode updateEnvelope = successSchema(
            openApi,
            "/api/v1/notifications/settings/{category}",
            "put"
        );
        assertApiResultEnvelope(updateEnvelope);
        JsonNode updateData = resolveSchema(
            openApi,
            updateEnvelope.path("properties").path("data")
        );
        assertThat(updateData.path("properties").propertyNames())
            .containsExactlyInAnyOrder(
                "category",
                "enabled",
                "configurable",
                "updatedAt"
            );
    }

    private void assertResponseCodes(JsonNode operation, String... expectedCodes) {
        assertThat(operation.path("responses").propertyNames())
            .containsExactlyInAnyOrder(expectedCodes);
    }

    private JsonNode successSchema(JsonNode openApi, String path, String method) {
        JsonNode content = openApi.path("paths").path(path).path(method)
            .path("responses").path("200").path("content");
        assertThat(content.values()).isNotEmpty();
        return resolveSchema(
            openApi,
            content.values().iterator().next().path("schema")
        );
    }

    private void assertApiResultEnvelope(JsonNode schema) {
        assertThat(schema.path("properties").propertyNames())
            .containsExactlyInAnyOrder("success", "data", "message");
        assertThat(schema.path("properties").path("success").path("type").asText())
            .isEqualTo("boolean");
        assertThat(schema.path("properties").path("message").path("type").asText())
            .isEqualTo("string");
    }

    private JsonNode resolveSchema(JsonNode openApi, JsonNode schema) {
        String ref = schema.path("$ref").asText();
        if (ref.isEmpty()) {
            return schema;
        }

        String prefix = "#/components/schemas/";
        assertThat(ref).startsWith(prefix);
        return openApi.path("components").path("schemas")
            .path(ref.substring(prefix.length()));
    }
}
