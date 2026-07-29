package com.prompthub.settlement.infrastructure.client.user;

import com.prompthub.settlement.application.dto.SellerSettlementRegistrationCommand;
import com.prompthub.settlement.application.dto.SellerSettlementStoredSnapshot;
import com.prompthub.settlement.application.exception.SellerSettlementDeliveryException;
import com.prompthub.settlement.application.port.SellerSettlementRegistrationPort;
import com.prompthub.settlement.infrastructure.client.user.config.SellerSettlementCommandGrpcProperties;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementCommandServiceGrpc;
import io.grpc.Metadata;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SellerSettlementCommandGrpcClient
        implements SellerSettlementRegistrationPort {

    private static final Metadata.Key<String> INTERNAL_TOKEN = Metadata.Key.of(
            "x-internal-service-token", Metadata.ASCII_STRING_MARSHALLER);

    private final SellerSettlementCommandServiceGrpc
            .SellerSettlementCommandServiceBlockingStub stub;
    private final SellerSettlementCommandGrpcProperties properties;
    private final SellerSettlementCommandGrpcMapper mapper;

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
            throw SellerSettlementDeliveryException.from(
                    exception.getStatus().getCode(),
                    "gRPC " + exception.getStatus().getCode(),
                    exception);
        }
    }
}
