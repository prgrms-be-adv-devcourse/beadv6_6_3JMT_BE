package com.prompthub.paymentservice.presentation.config;

import com.prompthub.paymentservice.presentation.dto.response.ConfirmPaymentResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI paymentOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Payment Service API")
                .description("결제 승인 및 환불 처리 API (토스페이먼츠 연동)")
                .version("v1"))
            .addServersItem(new Server()
                .url("http://localhost:8084")
                .description("로컬 개발 서버"));
    }

    @Schema(description = "결제 승인 성공 응답")
    public static class ConfirmPaymentApiResult {
        @Schema(example = "true")
        public boolean success;
        public ConfirmPaymentResponse data;
        @Schema(example = "success")
        public String message;
    }
}
