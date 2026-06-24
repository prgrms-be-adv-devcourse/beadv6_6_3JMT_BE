package com.prompthub.user.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.prompthub.user.global.config.JwtProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Configuration
public class JwtConfig {

    private static final String PEM_HEADER_FOOTER_REGEX = "-----[^-]+-----|\\s";

    @Bean
    public JWKSource<SecurityContext> jwkSource(JwtProperties jwtProperties) throws Exception {
        RSAPrivateKey privateKey = loadPrivateKey(jwtProperties.getPrivateKey());
        RSAPublicKey publicKey = loadPublicKey(jwtProperties.getPublicKey());

        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID("user-service-rsa")
                .build();

        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    private RSAPrivateKey loadPrivateKey(String pem) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(pem.replaceAll(PEM_HEADER_FOOTER_REGEX, ""));
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
    }

    private RSAPublicKey loadPublicKey(String pem) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(pem.replaceAll(PEM_HEADER_FOOTER_REGEX, ""));
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(keyBytes));
    }
}
