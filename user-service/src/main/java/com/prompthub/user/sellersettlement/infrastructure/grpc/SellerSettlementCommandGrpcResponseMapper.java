package com.prompthub.user.sellersettlement.infrastructure.grpc;

import com.prompthub.user.grpc.sellersettlement.command.RegisterSellerSettlementResponse;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementDetailSnapshot;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementLineType;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementSnapshot;
import com.prompthub.user.sellersettlement.application.dto.RegisteredSellerSettlementSnapshot;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class SellerSettlementCommandGrpcResponseMapper {

    public RegisterSellerSettlementResponse toResponse(
            RegisteredSellerSettlementSnapshot stored) {
        SellerSettlementSnapshot.Builder snapshot = SellerSettlementSnapshot.newBuilder()
                .setSettlementId(stored.settlementId().toString())
                .setSellerId(stored.sellerId().toString())
                .setPeriodStart(stored.periodStart().toString())
                .setPeriodEnd(stored.periodEnd().toString())
                .setProductCount(stored.productCount())
                .setGrossSalesAmount(decimal(stored.grossSalesAmount()))
                .setRefundAmount(decimal(stored.refundAmount()))
                .setFeeTotalAmount(decimal(stored.feeTotalAmount()))
                .setSettlementTotalAmount(decimal(stored.settlementTotalAmount()))
                .setCalculatedAt(stored.calculatedAt().toString())
                .addAllDetails(stored.details().stream().map(detail ->
                        SellerSettlementDetailSnapshot.newBuilder()
                                .setSettlementDetailId(
                                        detail.settlementDetailId().toString())
                                .setOrderProductId(detail.orderProductId().toString())
                                .setLineType(SellerSettlementLineType.valueOf(
                                        detail.lineType().name()))
                                .setLineAmount(decimal(detail.lineAmount()))
                                .setFeeRate(decimal(detail.feeRate()))
                                .setFeeAmount(decimal(detail.feeAmount()))
                                .setLineSettlementAmount(
                                        decimal(detail.lineSettlementAmount()))
                                .setOccurredAt(detail.occurredAt().toString())
                                .build()).toList());
        if (stored.deliveryRequestId() != null) {
            snapshot.setDeliveryRequestId(stored.deliveryRequestId().toString());
        }
        return RegisterSellerSettlementResponse.newBuilder()
                .setStoredSettlement(snapshot)
                .build();
    }

    private String decimal(BigDecimal value) {
        return value.toPlainString();
    }
}
