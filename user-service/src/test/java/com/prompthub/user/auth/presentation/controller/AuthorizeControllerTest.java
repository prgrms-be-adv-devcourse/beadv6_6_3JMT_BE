package com.prompthub.user.auth.presentation.controller;

import com.prompthub.user.auth.application.dto.AuthorizeResult;
import com.prompthub.user.auth.application.usecase.AuthorizeUseCase;
import com.prompthub.user.user.domain.exception.UserNotFoundException;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.model.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthorizeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthorizeUseCase authorizeUseCase;

    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void authorize_사용자_존재_시_200_status와_role_반환() throws Exception {
        given(authorizeUseCase.authorize(USER_ID))
                .willReturn(new AuthorizeResult(UserStatus.ACTIVE, UserRole.BUYER));

        mockMvc.perform(get("/internal/authorize/{userId}", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.role").value("BUYER"));
    }

    @Test
    void authorize_사용자_없으면_404() throws Exception {
        given(authorizeUseCase.authorize(USER_ID)).willThrow(new UserNotFoundException());

        mockMvc.perform(get("/internal/authorize/{userId}", USER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("A001"));
    }
}
