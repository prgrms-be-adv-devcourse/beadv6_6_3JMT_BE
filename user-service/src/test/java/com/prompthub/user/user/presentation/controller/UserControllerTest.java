package com.prompthub.user.user.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prompthub.user.global.config.SecurityConfig;
import com.prompthub.user.user.application.dto.UpdateProfileResult;
import com.prompthub.user.user.application.usecase.UserUseCase;
import com.prompthub.user.user.domain.exception.EmailAlreadyUsedException;
import com.prompthub.user.user.domain.exception.UserNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserUseCase userUseCase;

    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void updateMe_name만_수정_name만_응답에_포함() throws Exception {
        given(userUseCase.updateProfile(any()))
                .willReturn(new UpdateProfileResult(USER_ID, "새이름", null));

        mockMvc.perform(patch("/api/v2/users/me")
                        .header("X-User-Id", USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "새이름" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.name").value("새이름"))
                .andExpect(jsonPath("$.data.email").doesNotExist());
    }

    @Test
    void updateMe_email만_수정_email만_응답에_포함() throws Exception {
        given(userUseCase.updateProfile(any()))
                .willReturn(new UpdateProfileResult(USER_ID, null, "new@example.com"));

        mockMvc.perform(patch("/api/v2/users/me")
                        .header("X-User-Id", USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "new@example.com" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("new@example.com"))
                .andExpect(jsonPath("$.data.name").doesNotExist());
    }

    @Test
    void updateMe_name과_email_모두_수정_둘_다_응답에_포함() throws Exception {
        given(userUseCase.updateProfile(any()))
                .willReturn(new UpdateProfileResult(USER_ID, "새이름", "new@example.com"));

        mockMvc.perform(patch("/api/v2/users/me")
                        .header("X-User-Id", USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "새이름", "email": "new@example.com" }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("새이름"))
                .andExpect(jsonPath("$.data.email").value("new@example.com"));
    }

    @Test
    void updateMe_XUserId_헤더_누락_403() throws Exception {
        mockMvc.perform(patch("/api/v2/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "새이름" }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateMe_중복_email_409_A007() throws Exception {
        given(userUseCase.updateProfile(any())).willThrow(new EmailAlreadyUsedException());

        mockMvc.perform(patch("/api/v2/users/me")
                        .header("X-User-Id", USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "email": "taken@example.com" }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("A007"));
    }

    @Test
    void updateMe_존재하지_않는_유저_404_A001() throws Exception {
        given(userUseCase.updateProfile(any())).willThrow(new UserNotFoundException());

        mockMvc.perform(patch("/api/v2/users/me")
                        .header("X-User-Id", USER_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "새이름" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("A001"));
    }
}
