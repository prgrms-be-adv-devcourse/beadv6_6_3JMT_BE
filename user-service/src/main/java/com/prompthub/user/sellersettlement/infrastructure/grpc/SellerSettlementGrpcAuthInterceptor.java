package com.prompthub.user.sellersettlement.infrastructure.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SellerSettlementGrpcAuthInterceptor implements ServerInterceptor {

    private static final Status UNAUTHENTICATED = Status.UNAUTHENTICATED
            .withDescription("내부 인증 정보가 올바르지 않습니다.");

    private final byte[] expectedToken;

    public SellerSettlementGrpcAuthInterceptor(
            SellerSettlementGrpcSecurityProperties properties) {
        expectedToken = properties.internalToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        String suppliedToken = headers.get(SellerSettlementGrpcMetadata.INTERNAL_TOKEN);
        if (!matchesExpectedToken(suppliedToken)) {
            return close(call);
        }

        UUID actorId;
        try {
            actorId = UUID.fromString(headers.get(SellerSettlementGrpcMetadata.USER_ID));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return close(call);
        }

        Context authenticated = Context.current()
                .withValue(SellerSettlementGrpcMetadata.ACTOR_ID, actorId);
        return Contexts.interceptCall(authenticated, call, headers, next);
    }

    private boolean matchesExpectedToken(String suppliedToken) {
        byte[] supplied = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, supplied);
    }

    private <ReqT, RespT> ServerCall.Listener<ReqT> close(
            ServerCall<ReqT, RespT> call) {
        call.close(UNAUTHENTICATED, new Metadata());
        return new ServerCall.Listener<>() {
        };
    }
}
