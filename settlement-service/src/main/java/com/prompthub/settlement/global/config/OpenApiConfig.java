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
                .description("게이트웨이가 주입하는 사용자 ID(UUID). 관리자 ID를 입력합니다.");

        SecurityScheme userRoleScheme = new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name(AuthHeaders.USER_ROLE)
                .description("게이트웨이가 주입하는 사용자 역할. 관리자 API는 ADMIN 이 필요합니다.");

        return new OpenAPI()
                .info(new Info()
                        .title("Settlement Service API")
                        .description("정산 서비스 API 문서. 우측 상단 Authorize 에 X-User-Id(UUID)와 "
                                + "X-User-Role(ADMIN) 을 입력하면 모든 관리자 API 호출에 자동 적용됩니다.")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(AuthHeaders.USER_ID, userIdScheme)
                        .addSecuritySchemes(AuthHeaders.USER_ROLE, userRoleScheme))
                .addSecurityItem(new SecurityRequirement()
                        .addList(AuthHeaders.USER_ID)
                        .addList(AuthHeaders.USER_ROLE));
    }
}
