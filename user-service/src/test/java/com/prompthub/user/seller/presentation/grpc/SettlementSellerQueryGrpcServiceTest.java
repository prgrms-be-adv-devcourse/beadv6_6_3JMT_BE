package com.prompthub.user.seller.presentation.grpc;

import com.prompthub.user.grpc.seller.GetSellersRequest;
import com.prompthub.user.grpc.seller.GetSellersResponse;
import com.prompthub.user.grpc.seller.SellerInfo;
import com.prompthub.user.seller.application.dto.SellerInfoResult;
import com.prompthub.user.seller.application.usecase.SellerQueryUseCase;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class SettlementSellerQueryGrpcServiceTest {

    @Mock
    private SellerQueryUseCase sellerQueryUseCase;

    @Mock
    private StreamObserver<GetSellersResponse> responseObserver;

    @InjectMocks
    private SettlementSellerQueryGrpcService settlementSellerQueryGrpcService;

    @Test
    void findSellers_정상_요청_onNext와_onCompleted_호출() {
        String sellerId = UUID.randomUUID().toString();
        GetSellersRequest request = GetSellersRequest.newBuilder()
                .addSellerIds(sellerId)
                .build();
        given(sellerQueryUseCase.findSellers(List.of(sellerId)))
                .willReturn(List.of(new SellerInfoResult(sellerId, "판매자A", "", "ACTIVE")));

        settlementSellerQueryGrpcService.getSellers(request, responseObserver);

        then(responseObserver).should().onNext(any(GetSellersResponse.class));
        then(responseObserver).should().onCompleted();
    }

    @Test
    void findSellers_응답_sellers에_올바른_필드_포함() {
        String sellerId = UUID.randomUUID().toString();
        GetSellersRequest request = GetSellersRequest.newBuilder()
                .addSellerIds(sellerId)
                .build();
        given(sellerQueryUseCase.findSellers(List.of(sellerId)))
                .willReturn(List.of(new SellerInfoResult(sellerId, "판매자A", "https://cdn.example.com/a.jpg", "ACTIVE")));
        ArgumentCaptor<GetSellersResponse> captor = ArgumentCaptor.forClass(GetSellersResponse.class);

        settlementSellerQueryGrpcService.getSellers(request, responseObserver);

        then(responseObserver).should().onNext(captor.capture());
        SellerInfo seller = captor.getValue().getSellers(0);
        assertThat(seller.getSellerId()).isEqualTo(sellerId);
        assertThat(seller.getSellerName()).isEqualTo("판매자A");
        assertThat(seller.getProfileImageUrl()).isEqualTo("https://cdn.example.com/a.jpg");
        assertThat(seller.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void findSellers_빈_요청_빈_sellers_응답() {
        GetSellersRequest request = GetSellersRequest.newBuilder().build();
        given(sellerQueryUseCase.findSellers(List.of())).willReturn(List.of());
        ArgumentCaptor<GetSellersResponse> captor = ArgumentCaptor.forClass(GetSellersResponse.class);

        settlementSellerQueryGrpcService.getSellers(request, responseObserver);

        then(responseObserver).should().onNext(captor.capture());
        assertThat(captor.getValue().getSellersList()).isEmpty();
        then(responseObserver).should().onCompleted();
    }

    @Test
    void findSellers_복수_sellers_모두_응답에_포함() {
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();
        GetSellersRequest request = GetSellersRequest.newBuilder()
                .addSellerIds(id1)
                .addSellerIds(id2)
                .build();
        given(sellerQueryUseCase.findSellers(List.of(id1, id2)))
                .willReturn(List.of(
                        new SellerInfoResult(id1, "판매자A", "", "ACTIVE"),
                        new SellerInfoResult(id2, "판매자B", "", "ACTIVE")
                ));
        ArgumentCaptor<GetSellersResponse> captor = ArgumentCaptor.forClass(GetSellersResponse.class);

        settlementSellerQueryGrpcService.getSellers(request, responseObserver);

        then(responseObserver).should().onNext(captor.capture());
        assertThat(captor.getValue().getSellersList()).hasSize(2);
    }

    @Test
    void findSellers_UseCase에_seller_ids_그대로_전달() {
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();
        GetSellersRequest request = GetSellersRequest.newBuilder()
                .addSellerIds(id1)
                .addSellerIds(id2)
                .build();
        given(sellerQueryUseCase.findSellers(List.of(id1, id2))).willReturn(List.of());

        settlementSellerQueryGrpcService.getSellers(request, responseObserver);

        then(sellerQueryUseCase).should().findSellers(List.of(id1, id2));
    }

    @Test
    void findSellers_onError_호출_안됨() {
        GetSellersRequest request = GetSellersRequest.newBuilder().build();
        given(sellerQueryUseCase.findSellers(List.of())).willReturn(List.of());

        settlementSellerQueryGrpcService.getSellers(request, responseObserver);

        then(responseObserver).should(never()).onError(any());
    }
}
