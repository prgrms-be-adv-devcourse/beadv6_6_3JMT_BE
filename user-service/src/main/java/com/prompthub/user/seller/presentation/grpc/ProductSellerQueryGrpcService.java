package com.prompthub.user.seller.presentation.grpc;

import com.prompthub.product.grpc.seller.SellerBatchQueryRequest;
import com.prompthub.product.grpc.seller.SellerBatchQueryResponse;
import com.prompthub.product.grpc.seller.SellerInfo;
import com.prompthub.product.grpc.seller.SellerQueryRequest;
import com.prompthub.product.grpc.seller.SellerQueryServiceGrpc;
import com.prompthub.user.seller.application.usecase.SellerQueryUseCase;
import com.prompthub.user.user.domain.exception.UserNotFoundException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import org.springframework.grpc.server.service.GrpcService;

import java.util.List;

@GrpcService
@RequiredArgsConstructor
public class ProductSellerQueryGrpcService extends SellerQueryServiceGrpc.SellerQueryServiceImplBase {

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

    @Override
    public void getSeller(SellerQueryRequest request, StreamObserver<SellerInfo> responseObserver) {
        try {
            var result = sellerQueryUseCase.findSeller(request.getSellerId());
            responseObserver.onNext(SellerInfo.newBuilder()
                    .setSellerId(result.sellerId())
                    .setSellerName(result.sellerName())
                    .setProfileImageUrl(result.profileImageUrl())
                    .setStatus(result.status())
                    .build());
            responseObserver.onCompleted();
        } catch (UserNotFoundException e) {
            responseObserver.onError(
                    Status.NOT_FOUND.withDescription("판매자를 찾을 수 없습니다.").asRuntimeException()
            );
        }
    }
}
