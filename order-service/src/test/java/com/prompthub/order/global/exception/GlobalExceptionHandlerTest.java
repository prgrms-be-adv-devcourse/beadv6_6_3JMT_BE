package com.prompthub.order.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.prompthub.exception.BusinessException;
import com.prompthub.order.global.web.AuthHeaders;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    @DisplayName("BusinessException은 문서화된 에러 응답을 반환한다")
    void businessExceptionReturnsDocumentedErrorResponse() throws Exception {
        mockMvc.perform(get("/test/business").header("X-Request-Id", "request-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.message").value("장바구니가 비어 있습니다."))
                .andExpect(jsonPath("$.code").value("O004"))
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist())
                .andExpect(jsonPath("$.requestId").doesNotExist());
    }

    @Test
    @DisplayName("주문 상태 전이 도메인 예외는 O009 에러 코드를 반환한다")
    void invalidOrderStatusTransitionReturnsO009() throws Exception {
        mockMvc.perform(get("/test/domain/order-status").header("X-Request-Id", "request-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("대기 상태의 주문만 처리할 수 있습니다."))
                .andExpect(jsonPath("$.code").value("O009"));
    }

    @Test
    @DisplayName("검증 예외는 V001 에러 코드를 반환한다")
    void validationExceptionReturnsV001() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("입력값 검증 실패"))
                .andExpect(jsonPath("$.code").value("V001"));
    }

    @Test
    @DisplayName("사용자 헤더가 없으면 인증 에러를 반환한다")
    void missingUserHeaderReturnsAuthenticationError() throws Exception {
        mockMvc.perform(get("/test/header"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("토큰이 만료되었거나 유효하지 않습니다."))
                .andExpect(jsonPath("$.code").value("A003"));
    }

    @Test
    @DisplayName("사용자 권한 헤더가 없으면 인증 에러를 반환한다")
    void missingUserRoleHeaderReturnsAuthenticationError() throws Exception {
        mockMvc.perform(get("/test/role-header")
                        .header(AuthHeaders.USER_ID, "00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("토큰이 만료되었거나 유효하지 않습니다."))
                .andExpect(jsonPath("$.code").value("A003"));
    }

    @Test
    @DisplayName("읽을 수 없는 JSON은 V001 에러 코드를 반환한다")
    void unreadableJsonReturnsV001() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("입력값 검증 실패"))
                .andExpect(jsonPath("$.code").value("V001"));
    }

    @RestController
    static class TestController {

        @GetMapping("/test/business")
        void business() {
            throw new BusinessException(ErrorCode.CART_EMPTY);
        }

        @GetMapping("/test/domain/order-status")
        void invalidOrderStatusTransition() {
            throw new OrderException(ErrorCode.INVALID_ORDER_STATUS_TRANSITION, "대기 상태의 주문만 처리할 수 있습니다.");
        }

        @PostMapping("/test/validation")
        void validation(@Valid @RequestBody TestRequest request) {
        }

        @GetMapping("/test/header")
        void header(@RequestHeader(AuthHeaders.USER_ID) String userId) {
        }

        @GetMapping("/test/role-header")
        void roleHeader(
                @RequestHeader(AuthHeaders.USER_ID) String userId,
                @RequestHeader(AuthHeaders.USER_ROLE) String userRole
        ) {
        }
    }

    record TestRequest(@NotBlank String name) {
    }
}
