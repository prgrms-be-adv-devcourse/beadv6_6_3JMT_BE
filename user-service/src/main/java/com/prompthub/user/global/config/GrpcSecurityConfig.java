package com.prompthub.user.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.grpc.server.security.AuthenticationProcessInterceptor;
import org.springframework.grpc.server.security.GrpcSecurity;

@Configuration
public class GrpcSecurityConfig {

    @Bean
    @GlobalServerInterceptor
    AuthenticationProcessInterceptor grpcPermitAllInterceptor(GrpcSecurity grpcSecurity) throws Exception {
        return grpcSecurity
                .authorizeRequests(requests -> requests.allRequests().permitAll())
                .build();
    }
}
