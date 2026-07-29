package com.prompthub.user.sellersettlement.application.service;

import com.prompthub.user.sellersettlement.application.dto.RegisterSellerSettlementCommand;
import com.prompthub.user.sellersettlement.application.dto.RegisteredSellerSettlementSnapshot;
import com.prompthub.user.sellersettlement.application.usecase.RegisterSellerSettlementUseCase;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SellerSettlementRegistrationFacade
        implements RegisterSellerSettlementUseCase {

    private final SellerSettlementRegistrationApplicationService writer;
    private final SellerSettlementReadBackApplicationService reader;

    @Override
    public RegisteredSellerSettlementSnapshot register(
            RegisterSellerSettlementCommand command) {
        try {
            UUID settlementId = writer.register(command);
            return reader.readBySettlementId(settlementId);
        } catch (DataIntegrityViolationException exception) {
            return recoverKnownRegistrationConflict(command, exception);
        }
    }

    private RegisteredSellerSettlementSnapshot recoverKnownRegistrationConflict(
            RegisterSellerSettlementCommand command,
            DataIntegrityViolationException exception) {
        return reader.findByDeliveryRequestId(command.deliveryRequestId())
                .or(() -> reader.findBySettlementId(command.settlementId()))
                .orElseThrow(() -> exception);
    }
}
