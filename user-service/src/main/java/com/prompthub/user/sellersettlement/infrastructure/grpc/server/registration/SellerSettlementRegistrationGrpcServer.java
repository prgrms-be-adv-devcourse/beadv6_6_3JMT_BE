package com.prompthub.user.sellersettlement.infrastructure.grpc.server.registration;

import com.prompthub.user.grpc.sellersettlement.command.RegisterSellerSettlementRequest;
import com.prompthub.user.grpc.sellersettlement.command.RegisterSellerSettlementResponse;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementCommandServiceGrpc;
import com.prompthub.user.sellersettlement.application.dto.RegisteredSellerSettlementSnapshot;
import com.prompthub.user.sellersettlement.application.usecase.RegisterSellerSettlementUseCase;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SellerSettlementRegistrationGrpcServer
        extends SellerSettlementCommandServiceGrpc
        .SellerSettlementCommandServiceImplBase {

    private final RegisterSellerSettlementUseCase useCase;
    private final SellerSettlementRegistrationGrpcRequestMapper requestMapper;
    private final SellerSettlementRegistrationGrpcResponseMapper responseMapper;

    @Override
    public void registerSellerSettlement(
            RegisterSellerSettlementRequest request,
            StreamObserver<RegisterSellerSettlementResponse> responseObserver) {
        try {
            RegisteredSellerSettlementSnapshot stored =
                    useCase.register(requestMapper.toCommand(request));
            responseObserver.onNext(responseMapper.toResponse(stored));
            responseObserver.onCompleted();
        } catch (IllegalArgumentException | NullPointerException exception) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("정산 등록 요청이 올바르지 않습니다.")
                    .asRuntimeException());
        } catch (RuntimeException exception) {
            responseObserver.onError(Status.INTERNAL
                    .withDescription("정산 등록을 처리할 수 없습니다.")
                    .asRuntimeException());
        }
    }
}
