package com.prompthub.user.auth.infrastructure.jwt;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.prompthub.user.global.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class JwtTokenProviderAccessTest {

    @Test
    void generateAccessToken_클레임에_sub와_epoch만_담고_roles와_status는_없다() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID("test-rsa")
                .build();
        JwtEncoder jwtEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
        JwtDecoder jwtDecoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        JwtProperties jwtProperties = mock(JwtProperties.class);
        given(jwtProperties.getAccessTokenExpireSeconds()).willReturn(900L);

        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(jwtEncoder, jwtDecoder, jwtProperties);
        UUID userId = UUID.randomUUID();

        JwtTokenProvider.TokenResult result = jwtTokenProvider.generateAccessToken(userId, 3L);
        Jwt decoded = jwtDecoder.decode(result.token());

        assertThat(decoded.getSubject()).isEqualTo(userId.toString());
        assertThat(decoded.getClaimAsString("type")).isEqualTo("access");
        assertThat(String.valueOf(decoded.getClaims().get("epoch"))).isEqualTo("3");
        assertThat(decoded.getClaims()).doesNotContainKeys("roles", "status");
    }
}
