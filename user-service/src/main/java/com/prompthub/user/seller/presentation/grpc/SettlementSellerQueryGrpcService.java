package com.prompthub.user.seller.presentation.grpc;

import java.util.List;

import org.springframework.grpc.server.service.GrpcService;

import com.prompthub.settlement.grpc.seller.SellerBatchQueryRequest;
import com.prompthub.settlement.grpc.seller.SellerBatchQueryResponse;
import com.prompthub.settlement.grpc.seller.SellerInfo;
import com.prompthub.settlement.grpc.seller.SellerQueryServiceGrpc;
import com.prompthub.user.seller.application.usecase.SellerQueryUseCase;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;

@GrpcService
@RequiredArgsConstructor
public class SettlementSellerQueryGrpcService extends SellerQueryServiceGrpc.SellerQueryServiceImplBase {

    private final SellerQueryUseCase sellerQueryUseCase;

    @Override
    public void findSellers(SellerBatchQueryRequest request, StreamObserver<SellerBatchQueryResponse> responseObserver) {
        List<SellerInfo> sellers = sellerQueryUseCase.findSellers(request.getSellerIdsList())
                .stream()
                .map(result -> SellerInfo.newBuilder()
                        .setSellerId(result.sellerId())
                        .setSellerName(result.sellerName())
                        .setProfileImageUrl(result.profileImageUrl())
                        .setStatus(result.status())
                        .build())
                .toList();

        SellerBatchQueryResponse response = SellerBatchQueryResponse.newBuilder()
                .addAllSellers(sellers)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
