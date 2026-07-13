package com.prompthub.user.seller.presentation.grpc;

import java.util.List;

import org.springframework.grpc.server.service.GrpcService;

import com.prompthub.user.grpc.seller.GetSellersRequest;
import com.prompthub.user.grpc.seller.GetSellersResponse;
import com.prompthub.user.grpc.seller.SellerInfo;
import com.prompthub.user.grpc.seller.SellerQueryServiceGrpc;
import com.prompthub.user.seller.application.usecase.SellerQueryUseCase;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;

@GrpcService
@RequiredArgsConstructor
public class SettlementSellerQueryGrpcService extends SellerQueryServiceGrpc.SellerQueryServiceImplBase {

    private final SellerQueryUseCase sellerQueryUseCase;

    @Override
    public void getSellers(GetSellersRequest request, StreamObserver<GetSellersResponse> responseObserver) {
        List<SellerInfo> sellers = sellerQueryUseCase.findSellers(request.getSellerIdsList())
                .stream()
                .map(result -> SellerInfo.newBuilder()
                        .setSellerId(result.sellerId())
                        .setSellerName(result.sellerName())
                        .setProfileImageUrl(result.profileImageUrl())
                        .setStatus(result.status())
                        .build())
                .toList();

        GetSellersResponse response = GetSellersResponse.newBuilder()
                .addAllSellers(sellers)
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
