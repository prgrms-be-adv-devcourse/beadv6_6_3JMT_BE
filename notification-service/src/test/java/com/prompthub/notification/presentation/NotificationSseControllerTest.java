package com.prompthub.notification.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "notification.sse.max-connections-per-user=1")
class NotificationSseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void opensSseStreamAndEnforcesConnectionLimit() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(get("/api/v2/notifications/stream").header("X-User-Id", userId))
            .andExpect(request().asyncStarted());

        mockMvc.perform(get("/api/v2/notifications/stream").header("X-User-Id", userId))
            .andExpect(status().isTooManyRequests());
    }
}
