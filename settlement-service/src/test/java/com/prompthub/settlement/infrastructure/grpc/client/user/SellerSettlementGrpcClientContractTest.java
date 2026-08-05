package com.prompthub.settlement.infrastructure.grpc.client.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.prompthub.settlement.application.client.user.SellerSettlementClientException;
import com.prompthub.settlement.application.dto.delivery.SellerSettlementRegistrationCommand;
import com.prompthub.settlement.infrastructure.grpc.client.user.config.SellerSettlementGrpcClientProperties;
import com.prompthub.user.grpc.sellersettlement.command.RegisterSellerSettlementRequest;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementCommandServiceGrpc;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementSnapshot;
import io.grpc.ClientInterceptor;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SellerSettlementGrpcClientContractTest {

    @Test
    @DisplayName("판매자 정산 Command 서비스는 등록 RPC 하나만 제공한다")
    void commandServiceExposesOnlyRegisterSellerSettlement() {
        ServiceDescriptor descriptor =
                SellerSettlementCommandServiceGrpc.getServiceDescriptor();

        assertThat(descriptor.getMethods())
                .extracting(MethodDescriptor::getBareMethodName)
                .containsExactly("RegisterSellerSettlement");
    }

    @Test
    @DisplayName("판매 원금 필드는 gross_sales_amount 이름과 7번 필드를 유지한다")
    void grossSalesAmountUsesStableFieldNumber() {
        FieldDescriptor field = SellerSettlementSnapshot.getDescriptor()
                .findFieldByName("gross_sales_amount");

        assertThat(field).isNotNull();
        assertThat(field.getNumber()).isEqualTo(7);
        assertThat(field.getJavaType()).isEqualTo(FieldDescriptor.JavaType.STRING);
    }

    @Test
    @DisplayName("UNAVAILABLE gRPC 실패를 재시도 가능한 Client 실패로 변환한다")
    void unavailableFailureIsRetryable() {
        assertGrpcFailure(Status.Code.UNAVAILABLE, true);
    }

    @Test
    @DisplayName("INVALID_ARGUMENT gRPC 실패를 재시도 불가능한 Client 실패로 변환한다")
    void invalidArgumentFailureIsNotRetryable() {
        assertGrpcFailure(Status.Code.INVALID_ARGUMENT, false);
    }

    private void assertGrpcFailure(Status.Code code, boolean retryable) {
        SellerSettlementCommandServiceGrpc.SellerSettlementCommandServiceBlockingStub stub =
                mock(SellerSettlementCommandServiceGrpc
                        .SellerSettlementCommandServiceBlockingStub.class);
        SellerSettlementGrpcMapper mapper = mock(SellerSettlementGrpcMapper.class);
        SellerSettlementRegistrationCommand command =
                mock(SellerSettlementRegistrationCommand.class);
        RegisterSellerSettlementRequest request =
                RegisterSellerSettlementRequest.getDefaultInstance();
        SellerSettlementGrpcClientProperties properties =
                new SellerSettlementGrpcClientProperties(
                        "internal-token",
                        Duration.ofSeconds(1));
        SellerSettlementGrpcClientAdapter adapter =
                new SellerSettlementGrpcClientAdapter(stub, properties, mapper);
        StatusRuntimeException cause = Status.fromCode(code).asRuntimeException();
        given(mapper.toRequest(command)).willReturn(request);
        given(stub.withInterceptors(any(ClientInterceptor.class))).willReturn(stub);
        given(stub.withDeadlineAfter(1_000L, TimeUnit.MILLISECONDS)).willReturn(stub);
        given(stub.registerSellerSettlement(request)).willThrow(cause);

        assertThatThrownBy(() -> adapter.register(command))
                .isInstanceOfSatisfying(SellerSettlementClientException.class, exception -> {
                    assertThat(exception.getFailure().code()).isEqualTo(code.name());
                    assertThat(exception.getFailure().retryable()).isEqualTo(retryable);
                    assertThat(exception.getCause()).isSameAs(cause);
                });
    }
}
