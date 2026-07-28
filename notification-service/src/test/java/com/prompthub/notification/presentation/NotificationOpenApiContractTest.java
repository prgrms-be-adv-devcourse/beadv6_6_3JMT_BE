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
        assertThat(paths.path("/api/v1/notifications/{notificationId}").path("delete")
            .path("responses").has("204")).isTrue();
        assertThat(paths.path("/api/v1/notifications/{notificationId}").path("delete")
            .path("responses").has("400")).isTrue();
        assertThat(paths.path("/api/v1/notifications").path("delete").path("responses")
            .has("204")).isTrue();
        assertThat(paths.path("/api/v1/notifications").path("delete").path("responses")
            .has("400")).isTrue();
        assertThat(paths.path("/api/v1/notifications/settings/{category}").path("put")
            .path("responses").has("400")).isTrue();
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
}
