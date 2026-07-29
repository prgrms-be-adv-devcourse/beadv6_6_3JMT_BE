package com.prompthub.settlement.infrastructure.client.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementCommandServiceGrpc;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementSnapshot;
import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SellerSettlementCommandGrpcContractTest {

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
}
