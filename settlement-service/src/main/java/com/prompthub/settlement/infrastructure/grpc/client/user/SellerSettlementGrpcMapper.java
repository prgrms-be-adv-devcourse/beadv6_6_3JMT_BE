package com.prompthub.settlement.infrastructure.grpc.client.user;

import com.prompthub.settlement.application.dto.delivery.SellerSettlementRegistrationCommand;
import com.prompthub.settlement.application.dto.delivery.SellerSettlementStoredSnapshot;
import com.prompthub.user.grpc.sellersettlement.command.RegisterSellerSettlementRequest;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementDetailSnapshot;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementLineType;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SellerSettlementGrpcMapper {

    public RegisterSellerSettlementRequest toRequest(
            SellerSettlementRegistrationCommand command) {
        SellerSettlementSnapshot snapshot = SellerSettlementSnapshot.newBuilder()
                .setDeliveryRequestId(command.deliveryRequestId().toString())
                .setSettlementId(command.settlementId().toString())
                .setSellerId(command.sellerId().toString())
                .setPeriodStart(command.periodStart().toString())
                .setPeriodEnd(command.periodEnd().toString())
                .setProductCount(command.productCount())
                .setGrossSalesAmount(command.grossSalesAmount().toPlainString())
                .setRefundAmount(command.refundAmount().toPlainString())
                .setFeeTotalAmount(command.feeTotalAmount().toPlainString())
                .setSettlementTotalAmount(command.settlementTotalAmount().toPlainString())
                .setCalculatedAt(command.calculatedAt().toString())
                .addAllDetails(command.details().stream().map(detail ->
                        SellerSettlementDetailSnapshot.newBuilder()
                                .setSettlementDetailId(detail.settlementDetailId().toString())
                                .setSettlementSourceLineId(
                                        detail.settlementSourceLineId().toString())
                                .setOrderProductId(detail.orderProductId().toString())
                                .setLineType(SellerSettlementLineType.valueOf(detail.lineType().name()))
                                .setLineAmount(detail.lineAmount().toPlainString())
                                .setFeeRate(detail.feeRate().toPlainString())
                                .setFeeAmount(detail.feeAmount().toPlainString())
                                .setLineSettlementAmount(
                                        detail.lineSettlementAmount().toPlainString())
                                .setOccurredAt(detail.occurredAt().toString())
                                .build()).toList())
                .build();
        return RegisterSellerSettlementRequest.newBuilder()
                .setDeliveryRequestId(command.deliveryRequestId().toString())
                .setSettlement(snapshot)
                .build();
    }

    public SellerSettlementStoredSnapshot toSnapshot(SellerSettlementSnapshot stored) {
        return new SellerSettlementStoredSnapshot(
                stored.hasDeliveryRequestId()
                        ? UUID.fromString(stored.getDeliveryRequestId()) : null,
                UUID.fromString(stored.getSettlementId()),
                UUID.fromString(stored.getSellerId()),
                LocalDate.parse(stored.getPeriodStart()),
                LocalDate.parse(stored.getPeriodEnd()),
                stored.getProductCount(),
                new BigDecimal(stored.getGrossSalesAmount()),
                new BigDecimal(stored.getRefundAmount()),
                new BigDecimal(stored.getFeeTotalAmount()),
                new BigDecimal(stored.getSettlementTotalAmount()),
                LocalDateTime.parse(stored.getCalculatedAt()),
                stored.getDetailsList().stream().map(detail ->
                        new SellerSettlementStoredSnapshot.Detail(
                                UUID.fromString(detail.getSettlementDetailId()),
                                detail.hasSettlementSourceLineId()
                                        ? UUID.fromString(detail.getSettlementSourceLineId())
                                        : null,
                                UUID.fromString(detail.getOrderProductId()),
                                com.prompthub.settlement.domain.model.calculation.SettlementLineType
                                        .valueOf(detail.getLineType().name()),
                                new BigDecimal(detail.getLineAmount()),
                                new BigDecimal(detail.getFeeRate()),
                                new BigDecimal(detail.getFeeAmount()),
                                new BigDecimal(detail.getLineSettlementAmount()),
                                LocalDateTime.parse(detail.getOccurredAt())))
                        .toList());
    }
}
