package com.prompthub.user.auth.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenTest {

    @Test
    void create_epoch_0으로_시작() {
        RefreshToken refreshToken = RefreshToken.create(UUID.randomUUID(), "token", Instant.now().plusSeconds(604800));

        assertThat(refreshToken.getEpoch()).isEqualTo(0L);
    }

    @Test
    void rotate_토큰_교체하고_epoch_1증가() {
        RefreshToken refreshToken = RefreshToken.create(UUID.randomUUID(), "old-token", Instant.now().plusSeconds(604800));
        Instant newExpiresAt = Instant.now().plusSeconds(1209600);

        refreshToken.rotate("new-token", newExpiresAt);

        assertThat(refreshToken.getToken()).isEqualTo("new-token");
        assertThat(refreshToken.getEpoch()).isEqualTo(1L);
        assertThat(refreshToken.getExpiresAt()).isEqualTo(newExpiresAt);
    }

    @Test
    void rotate_여러번_호출하면_epoch_누적_증가() {
        RefreshToken refreshToken = RefreshToken.create(UUID.randomUUID(), "token-0", Instant.now().plusSeconds(604800));

        refreshToken.rotate("token-1", Instant.now().plusSeconds(604800));
        refreshToken.rotate("token-2", Instant.now().plusSeconds(604800));

        assertThat(refreshToken.getEpoch()).isEqualTo(2L);
    }

    @Test
    void reconstruct_캐시에서_복원한_필드_그대로_유지() {
        UUID id = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(604800);

        RefreshToken refreshToken = RefreshToken.reconstruct(id, userId, "cached-token", 3L, expiresAt);

        assertThat(refreshToken.getRefreshTokenId()).isEqualTo(id);
        assertThat(refreshToken.getUserId()).isEqualTo(userId);
        assertThat(refreshToken.getToken()).isEqualTo("cached-token");
        assertThat(refreshToken.getEpoch()).isEqualTo(3L);
        assertThat(refreshToken.getExpiresAt()).isEqualTo(expiresAt);
    }
}
