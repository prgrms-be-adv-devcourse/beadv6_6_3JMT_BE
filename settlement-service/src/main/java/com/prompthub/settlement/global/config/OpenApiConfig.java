package com.prompthub.settlement.global.config;

import com.prompthub.settlement.global.web.AuthHeaders;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "settlement.manual-api", name = "enabled", havingValue = "true")
public class OpenApiConfig {

    @Bean
    public OpenAPI settlementOpenAPI() {
        SecurityScheme userIdScheme = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name(AuthHeaders.USER_ID)
                .description("로컬 수동 실행자를 식별하는 사용자 ID(UUID)입니다.");

        return new OpenAPI()
                .info(new Info()
                        .title("Settlement Service Manual API")
                        .description("로컬 정산 배치 수동 실행과 상태 확인을 위한 API 문서입니다. "
                                + "수동 실행 요청에는 X-User-Id(UUID)만 입력합니다.")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(AuthHeaders.USER_ID, userIdScheme))
                .addSecurityItem(new SecurityRequirement()
                        .addList(AuthHeaders.USER_ID));
    }
}
