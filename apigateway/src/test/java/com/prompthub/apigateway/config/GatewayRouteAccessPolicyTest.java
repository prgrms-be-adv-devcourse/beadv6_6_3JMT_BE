package com.prompthub.apigateway.config;

import com.prompthub.apigateway.client.GatewayRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRouteAccessPolicyTest {

    @Test
    void BUYER_정책은_상품을_구매할_수_있는_모든_역할을_허용한다() {
        assertThat(GatewayRouteAccessPolicy.BUYER.allows(GatewayRole.BUYER)).isTrue();
        assertThat(GatewayRouteAccessPolicy.BUYER.allows(GatewayRole.SELLER)).isTrue();
        assertThat(GatewayRouteAccessPolicy.BUYER.allows(GatewayRole.ADMIN)).isTrue();
    }
}
