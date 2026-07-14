package com.prompthub.apigateway.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRoleTest {

    @Test
    void ADMIN이_가장_상위이고_BUYER가_가장_하위다() {
        assertThat(GatewayRole.ADMIN.ordinal()).isGreaterThan(GatewayRole.SELLER.ordinal());
        assertThat(GatewayRole.SELLER.ordinal()).isGreaterThan(GatewayRole.BUYER.ordinal());
    }

    @Test
    void 상위_role은_하위_요구를_만족한다() {
        assertThat(GatewayRole.ADMIN.ordinal() >= GatewayRole.BUYER.ordinal()).isTrue();
        assertThat(GatewayRole.SELLER.ordinal() >= GatewayRole.BUYER.ordinal()).isTrue();
        assertThat(GatewayRole.BUYER.ordinal() >= GatewayRole.SELLER.ordinal()).isFalse();
    }
}
