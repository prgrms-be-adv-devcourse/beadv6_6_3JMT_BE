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
        UUID settlementId = registerSafely(command);
        return reader.readBySettlementId(settlementId);
    }

    private UUID registerSafely(RegisterSellerSettlementCommand command) {
        try {
            return writer.register(command);
        } catch (DataIntegrityViolationException exception) {
            return command.settlementId();
        }
    }
}
