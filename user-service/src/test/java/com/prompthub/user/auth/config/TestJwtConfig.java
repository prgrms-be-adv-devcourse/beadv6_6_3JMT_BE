package com.prompthub.user.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * 테스트 전용 JWT 키 설정.
 * 하드코딩된 base64 키 문자열은 YAML 개행/포맷팅 도구에 의해 손상되기 쉬워
 * 테스트 컨텍스트 기동 시점에 RSA 키 쌍을 직접 생성해 사용한다.
 */
@Configuration
@Profile("test")
public class TestJwtConfig {

    @Bean
    public RSAKey testRsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();

        return new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("test-rsa")
                .build();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(RSAKey testRsaKey) {
        return new ImmutableJWKSet<>(new JWKSet(testRsaKey));
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtDecoder jwtDecoder(RSAKey testRsaKey) throws Exception {
        return NimbusJwtDecoder.withPublicKey(testRsaKey.toRSAPublicKey()).build();
    }
}
