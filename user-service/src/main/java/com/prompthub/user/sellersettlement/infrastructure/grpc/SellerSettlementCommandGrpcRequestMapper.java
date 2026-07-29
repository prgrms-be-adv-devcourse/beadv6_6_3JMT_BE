package com.prompthub.user.sellersettlement.infrastructure.grpc;

import com.prompthub.user.grpc.sellersettlement.command.RegisterSellerSettlementRequest;
import com.prompthub.user.grpc.sellersettlement.command.SellerSettlementDetailSnapshot;
import com.prompthub.user.sellersettlement.application.dto.RegisterSellerSettlementCommand;
import com.prompthub.user.sellersettlement.application.dto.RegisterSellerSettlementCommand.Detail;
import com.prompthub.user.sellersettlement.domain.model.enums.SellerSettlementLineType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SellerSettlementCommandGrpcRequestMapper {

    public RegisterSellerSettlementCommand toCommand(
            RegisterSellerSettlementRequest request) {
        if (!request.hasSettlement()) {
            throw new IllegalArgumentException("settlement는 필수입니다.");
        }
        var settlement = request.getSettlement();
        return new RegisterSellerSettlementCommand(
                UUID.fromString(request.getDeliveryRequestId()),
                UUID.fromString(settlement.getSettlementId()),
                UUID.fromString(settlement.getSellerId()),
                LocalDate.parse(settlement.getPeriodStart()),
                LocalDate.parse(settlement.getPeriodEnd()),
                settlement.getProductCount(),
                decimal(settlement.getGrossSalesAmount()),
                decimal(settlement.getRefundAmount()),
                decimal(settlement.getFeeTotalAmount()),
                decimal(settlement.getSettlementTotalAmount()),
                LocalDateTime.parse(settlement.getCalculatedAt()),
                settlement.getDetailsList().stream().map(this::toDetail).toList());
    }

    private Detail toDetail(SellerSettlementDetailSnapshot detail) {
        if (detail.getLineType()
                == com.prompthub.user.grpc.sellersettlement.command
                        .SellerSettlementLineType.SELLER_SETTLEMENT_LINE_TYPE_UNSPECIFIED) {
            throw new IllegalArgumentException("lineType은 필수입니다.");
        }
        if (!detail.hasSettlementSourceLineId()) {
            throw new IllegalArgumentException("settlementSourceLineId는 필수입니다.");
        }
        return new Detail(
                UUID.fromString(detail.getSettlementDetailId()),
                UUID.fromString(detail.getSettlementSourceLineId()),
                UUID.fromString(detail.getOrderProductId()),
                SellerSettlementLineType.valueOf(detail.getLineType().name()),
                decimal(detail.getLineAmount()),
                decimal(detail.getFeeRate()),
                decimal(detail.getFeeAmount()),
                decimal(detail.getLineSettlementAmount()),
                LocalDateTime.parse(detail.getOccurredAt()));
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("금액 문자열은 필수입니다.");
        }
        return new BigDecimal(value);
    }
}
