package com.prompthub.user.sellersettlement.infrastructure.grpc;

import io.grpc.Context;
import io.grpc.Metadata;
import java.util.UUID;

public final class SellerSettlementGrpcMetadata {

    public static final Metadata.Key<String> USER_ID = Metadata.Key.of(
            "x-user-id", Metadata.ASCII_STRING_MARSHALLER);
    public static final Metadata.Key<String> INTERNAL_TOKEN = Metadata.Key.of(
            "x-internal-service-token", Metadata.ASCII_STRING_MARSHALLER);
    public static final Context.Key<UUID> ACTOR_ID = Context.key(
            "seller-settlement-actor-id");

    private SellerSettlementGrpcMetadata() {
    }
}
