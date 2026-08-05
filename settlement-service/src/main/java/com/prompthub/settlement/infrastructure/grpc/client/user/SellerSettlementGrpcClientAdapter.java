package com.prompthub.settlement.infrastructure.grpc.client.user;

import com.prompthub.settlement.application.dto.delivery.SellerSettlementRegistrationCommand;
import com.prompthub.settlement.application.dto.delivery.SellerSettlementStoredSnapshot;
import com.prompthub.settlement.application.client.user.SellerSettlementClient;
import com.prompthub.settlement.application.client.user.SellerSettlementClientException;
import com.prompthub.settlement.application.client.user.SellerSettlementClientFailure;
import com.prompthub.settlement.infrastructure.grpc.client.user.config.SellerSettlementGrpcClientProperties;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementCommandServiceGrpc;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SellerSettlementGrpcClientAdapter
        implements SellerSettlementClient {

    private static final Metadata.Key<String> INTERNAL_TOKEN = Metadata.Key.of(
            "x-internal-service-token", Metadata.ASCII_STRING_MARSHALLER);

    private final SellerSettlementCommandServiceGrpc
            .SellerSettlementCommandServiceBlockingStub stub;
    private final SellerSettlementGrpcClientProperties properties;
    private final SellerSettlementGrpcMapper mapper;

    @Override
    public SellerSettlementStoredSnapshot register(
            SellerSettlementRegistrationCommand command) {
        Metadata metadata = new Metadata();
        metadata.put(INTERNAL_TOKEN, properties.internalToken());
        try {
            var authenticated = stub
                    .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                    .withDeadlineAfter(properties.deadline().toMillis(),
                            TimeUnit.MILLISECONDS);
            return mapper.toSnapshot(authenticated.registerSellerSettlement(
                    mapper.toRequest(command)).getStoredSettlement());
        } catch (StatusRuntimeException exception) {
            Status.Code statusCode = exception.getStatus().getCode();
            throw new SellerSettlementClientException(
                    failure(statusCode),
                    "gRPC " + statusCode,
                    exception);
        }
    }

    private SellerSettlementClientFailure failure(Status.Code statusCode) {
        boolean retryable = statusCode == Status.Code.UNAVAILABLE
                || statusCode == Status.Code.DEADLINE_EXCEEDED;
        return new SellerSettlementClientFailure(statusCode.name(), retryable);
    }
}
