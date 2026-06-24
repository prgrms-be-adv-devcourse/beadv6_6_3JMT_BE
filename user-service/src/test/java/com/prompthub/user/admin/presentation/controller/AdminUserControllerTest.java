package com.prompthub.user.admin.presentation.controller;

import com.prompthub.user.admin.application.dto.AdminUserPageResult;
import com.prompthub.user.admin.application.dto.AdminUserSummaryResult;
import com.prompthub.user.admin.application.usecase.AdminUserUseCase;
import com.prompthub.user.global.config.SecurityConfig;
import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.model.UserStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminUserController.class)
@Import(SecurityConfig.class)
class AdminUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminUserUseCase adminUserUseCase;

    private AdminUserPageResult emptyResult(int page, int size) {
        return new AdminUserPageResult(List.of(), page, size, 0L, false);
    }

    private AdminUserSummaryResult summaryResult(UserRole role, UserStatus status) {
        return new AdminUserSummaryResult(UUID.randomUUID(), "홍길동", "hong@example.com", role, status);
    }

    @Test
    void listUsers_정상_조회_200() throws Exception {
        given(adminUserUseCase.listUsers(any())).willReturn(emptyResult(1, 20));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void listUsers_기본_파라미터_page1_size20() throws Exception {
        given(adminUserUseCase.listUsers(any())).willReturn(emptyResult(1, 20));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(com.prompthub.user.admin.application.dto.AdminUserListQuery.class);
        verify(adminUserUseCase).listUsers(captor.capture());

        assertThat(captor.getValue().page()).isEqualTo(1);
        assertThat(captor.getValue().size()).isEqualTo(20);
        assertThat(captor.getValue().status()).isNull();
        assertThat(captor.getValue().role()).isNull();
    }

    @Test
    void listUsers_status_active_ACTIVE로_전달() throws Exception {
        given(adminUserUseCase.listUsers(any())).willReturn(emptyResult(1, 20));

        mockMvc.perform(get("/api/v1/admin/users").param("status", "active"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(com.prompthub.user.admin.application.dto.AdminUserListQuery.class);
        verify(adminUserUseCase).listUsers(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void listUsers_status_suspended_BLOCKED으로_전달() throws Exception {
        given(adminUserUseCase.listUsers(any())).willReturn(emptyResult(1, 20));

        mockMvc.perform(get("/api/v1/admin/users").param("status", "suspended"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(com.prompthub.user.admin.application.dto.AdminUserListQuery.class);
        verify(adminUserUseCase).listUsers(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(UserStatus.BLOCKED);
    }

    @Test
    void listUsers_status_withdrawn_WITHDRAWN으로_전달() throws Exception {
        given(adminUserUseCase.listUsers(any())).willReturn(emptyResult(1, 20));

        mockMvc.perform(get("/api/v1/admin/users").param("status", "withdrawn"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(com.prompthub.user.admin.application.dto.AdminUserListQuery.class);
        verify(adminUserUseCase).listUsers(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(UserStatus.WITHDRAWN);
    }

    @Test
    void listUsers_status_ALL_null로_전달() throws Exception {
        given(adminUserUseCase.listUsers(any())).willReturn(emptyResult(1, 20));

        mockMvc.perform(get("/api/v1/admin/users").param("status", "ALL"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(com.prompthub.user.admin.application.dto.AdminUserListQuery.class);
        verify(adminUserUseCase).listUsers(captor.capture());
        assertThat(captor.getValue().status()).isNull();
    }

    @Test
    void listUsers_role_buyer_BUYER로_전달() throws Exception {
        given(adminUserUseCase.listUsers(any())).willReturn(emptyResult(1, 20));

        mockMvc.perform(get("/api/v1/admin/users").param("role", "buyer"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(com.prompthub.user.admin.application.dto.AdminUserListQuery.class);
        verify(adminUserUseCase).listUsers(captor.capture());
        assertThat(captor.getValue().role()).isEqualTo(UserRole.BUYER);
    }

    @Test
    void listUsers_role_seller_SELLER로_전달() throws Exception {
        given(adminUserUseCase.listUsers(any())).willReturn(emptyResult(1, 20));

        mockMvc.perform(get("/api/v1/admin/users").param("role", "seller"))
                .andExpect(status().isOk());

        var captor = ArgumentCaptor.forClass(com.prompthub.user.admin.application.dto.AdminUserListQuery.class);
        verify(adminUserUseCase).listUsers(captor.capture());
        assertThat(captor.getValue().role()).isEqualTo(UserRole.SELLER);
    }

    @Test
    void listUsers_유효하지_않은_status_400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users").param("status", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V001"));
    }

    @Test
    void listUsers_유효하지_않은_role_400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users").param("role", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V001"));
    }

    @Test
    void listUsers_응답_role_소문자로_직렬화() throws Exception {
        AdminUserSummaryResult item = summaryResult(UserRole.BUYER, UserStatus.ACTIVE);
        given(adminUserUseCase.listUsers(any()))
                .willReturn(new AdminUserPageResult(List.of(item), 1, 20, 1L, false));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].role").value("buyer"));
    }

    @Test
    void listUsers_응답_status_소문자로_직렬화() throws Exception {
        AdminUserSummaryResult item = summaryResult(UserRole.BUYER, UserStatus.ACTIVE);
        given(adminUserUseCase.listUsers(any()))
                .willReturn(new AdminUserPageResult(List.of(item), 1, 20, 1L, false));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("active"));
    }

    @Test
    void listUsers_BLOCKED_사용자는_status_suspended로_직렬화() throws Exception {
        AdminUserSummaryResult item = summaryResult(UserRole.BUYER, UserStatus.BLOCKED);
        given(adminUserUseCase.listUsers(any()))
                .willReturn(new AdminUserPageResult(List.of(item), 1, 20, 1L, false));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("suspended"));
    }

    @Test
    void listUsers_응답_필드_확인() throws Exception {
        UUID userId = UUID.randomUUID();
        AdminUserSummaryResult item = new AdminUserSummaryResult(
                userId, "홍길동", "hong@example.com", UserRole.BUYER, UserStatus.ACTIVE);
        given(adminUserUseCase.listUsers(any()))
                .willReturn(new AdminUserPageResult(List.of(item), 1, 20, 1L, false));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(userId.toString()))
                .andExpect(jsonPath("$.data[0].name").value("홍길동"))
                .andExpect(jsonPath("$.data[0].email").value("hong@example.com"))
                .andExpect(jsonPath("$.data[0].role").value("buyer"))
                .andExpect(jsonPath("$.data[0].status").value("active"));
    }

    @Test
    void listUsers_meta_필드_포함() throws Exception {
        given(adminUserUseCase.listUsers(any()))
                .willReturn(new AdminUserPageResult(List.of(), 1, 20, 15L, false));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.size").value(20))
                .andExpect(jsonPath("$.meta.total").value(15))
                .andExpect(jsonPath("$.meta.hasNext").value(false));
    }
}
