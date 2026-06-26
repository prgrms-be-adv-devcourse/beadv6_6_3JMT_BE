package com.prompthub.user.seller.presentation.grpc;

import com.prompthub.product.grpc.seller.SellerInfo;
import com.prompthub.product.grpc.seller.SellerQueryRequest;
import com.prompthub.user.seller.application.dto.SellerInfoResult;
import com.prompthub.user.seller.application.usecase.SellerQueryUseCase;
import com.prompthub.user.user.domain.exception.UserNotFoundException;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class ProductSettlementSellerQueryGrpcServiceTest {

    @Mock
    private SellerQueryUseCase sellerQueryUseCase;

    @Mock
    private StreamObserver<SellerInfo> responseObserver;

    @InjectMocks
    private ProductSellerQueryGrpcService productSellerQueryGrpcService;

    @Test
    void getSeller_정상_응답_onNext_onCompleted_호출() {
        String sellerId = UUID.randomUUID().toString();
        SellerQueryRequest request = SellerQueryRequest.newBuilder().setSellerId(sellerId).build();
        given(sellerQueryUseCase.findSeller(sellerId))
                .willReturn(new SellerInfoResult(sellerId, "판매자A", "", "ACTIVE"));

        productSellerQueryGrpcService.getSeller(request, responseObserver);

        then(responseObserver).should().onNext(any(SellerInfo.class));
        then(responseObserver).should().onCompleted();
        then(responseObserver).should(never()).onError(any());
    }

    @Test
    void getSeller_응답_필드_검증() {
        String sellerId = UUID.randomUUID().toString();
        SellerQueryRequest request = SellerQueryRequest.newBuilder().setSellerId(sellerId).build();
        given(sellerQueryUseCase.findSeller(sellerId))
                .willReturn(new SellerInfoResult(sellerId, "판매자A", "https://cdn.example.com/a.jpg", "ACTIVE"));
        ArgumentCaptor<SellerInfo> captor = ArgumentCaptor.forClass(SellerInfo.class);

        productSellerQueryGrpcService.getSeller(request, responseObserver);

        then(responseObserver).should().onNext(captor.capture());
        SellerInfo seller = captor.getValue();
        assertThat(seller.getSellerId()).isEqualTo(sellerId);
        assertThat(seller.getSellerName()).isEqualTo("판매자A");
        assertThat(seller.getProfileImageUrl()).isEqualTo("https://cdn.example.com/a.jpg");
        assertThat(seller.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void getSeller_존재하지_않는_seller_onError_NOT_FOUND_호출() {
        String sellerId = UUID.randomUUID().toString();
        SellerQueryRequest request = SellerQueryRequest.newBuilder().setSellerId(sellerId).build();
        given(sellerQueryUseCase.findSeller(sellerId)).willThrow(new UserNotFoundException());
        ArgumentCaptor<Throwable> captor = ArgumentCaptor.forClass(Throwable.class);

        productSellerQueryGrpcService.getSeller(request, responseObserver);

        then(responseObserver).should().onError(captor.capture());
        then(responseObserver).should(never()).onNext(any());
        then(responseObserver).should(never()).onCompleted();
        StatusRuntimeException exception = (StatusRuntimeException) captor.getValue();
        assertThat(exception.getStatus().getCode()).isEqualTo(Status.NOT_FOUND.getCode());
    }
}
