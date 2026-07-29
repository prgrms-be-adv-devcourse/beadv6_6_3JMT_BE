package com.prompthub.user.sellersettlement.infrastructure.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

@Component
public class SellerSettlementCommandGrpcAuthInterceptor
        implements ServerInterceptor {

    private static final Status UNAUTHENTICATED = Status.UNAUTHENTICATED
            .withDescription("내부 인증 정보가 올바르지 않습니다.");

    private final byte[] expectedToken;

    public SellerSettlementCommandGrpcAuthInterceptor(
            SellerSettlementCommandGrpcSecurityProperties properties) {
        expectedToken = properties.internalToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {
        String token = headers.get(SellerSettlementGrpcMetadata.INTERNAL_TOKEN);
        byte[] supplied = token == null
                ? new byte[0]
                : token.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedToken, supplied)) {
            call.close(UNAUTHENTICATED, new Metadata());
            return new ServerCall.Listener<>() {
            };
        }
        return next.startCall(call, headers);
    }
}
