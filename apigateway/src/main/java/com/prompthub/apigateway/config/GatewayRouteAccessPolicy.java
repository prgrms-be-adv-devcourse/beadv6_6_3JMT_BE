package com.prompthub.apigateway.config;

import com.prompthub.apigateway.client.GatewayRole;

public enum GatewayRouteAccessPolicy {

    BUYER {
        @Override
        public boolean allows(GatewayRole actualRole) {
            return true;
        }
    },
    SELLER {
        @Override
        public boolean allows(GatewayRole actualRole) {
            return actualRole == GatewayRole.SELLER;
        }
    },
    SELLER_OR_ADMIN {
        @Override
        public boolean allows(GatewayRole actualRole) {
            return actualRole == GatewayRole.SELLER || actualRole == GatewayRole.ADMIN;
        }
    },
    ADMIN {
        @Override
        public boolean allows(GatewayRole actualRole) {
            return actualRole == GatewayRole.ADMIN;
        }
    };

    public abstract boolean allows(GatewayRole actualRole);
}
