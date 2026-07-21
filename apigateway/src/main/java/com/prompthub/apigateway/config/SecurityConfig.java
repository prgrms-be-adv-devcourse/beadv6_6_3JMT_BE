package com.prompthub.apigateway.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.config.Customizer;

@EnableWebFluxSecurity
@Configuration
public class SecurityConfig {

    private final ReactiveJwtDecoder reactiveJwtDecoder;
    private final GatewayApiVersionProperties apiVersionProperties;

    public SecurityConfig(ReactiveJwtDecoder reactiveJwtDecoder, GatewayApiVersionProperties apiVersionProperties) {
        this.reactiveJwtDecoder = reactiveJwtDecoder;
        this.apiVersionProperties = apiVersionProperties;
    }

    @Bean
    public SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
        List<String> authWhitelist = WhitelistPathResolver.authWhitelist(apiVersionProperties);
        List<String> productReadWhitelist = WhitelistPathResolver.productReadWhitelist(apiVersionProperties);
        List<String> sellerLookupWhitelist = WhitelistPathResolver.sellerLookupWhitelist(apiVersionProperties);
        List<String> sellerSingleLookupWhitelist =
            WhitelistPathResolver.sellerSingleLookupWhitelist(apiVersionProperties);

        return http
            .cors(Customizer.withDefaults())
            .authorizeExchange(ex -> {
                ex.pathMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                ex.pathMatchers(authWhitelist.toArray(String[]::new)).permitAll();
                if (!productReadWhitelist.isEmpty()) {
                    ex.pathMatchers(HttpMethod.GET, productReadWhitelist.toArray(String[]::new)).permitAll();
                }
                if (!sellerSingleLookupWhitelist.isEmpty()) {
                    ex.pathMatchers(HttpMethod.GET, sellerSingleLookupWhitelist.toArray(String[]::new)).permitAll();
                }
                if (!sellerLookupWhitelist.isEmpty()) {
                    ex.pathMatchers(HttpMethod.POST, sellerLookupWhitelist.toArray(String[]::new)).permitAll();
                }
                ex.anyExchange().authenticated();
            })
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtDecoder(reactiveJwtDecoder))
            )
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
            .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
            .build();
    }
}
