package com.prompthub.settlement.application.service;

import com.prompthub.settlement.application.dto.SettlementCalculationReconciliationReport;
import com.prompthub.settlement.application.usecase.ReconcileSettlementCalculationUseCase;
import com.prompthub.settlement.domain.model.Settlement;
import com.prompthub.settlement.domain.model.SettlementCalculationReconciliation;
import com.prompthub.settlement.domain.model.SettlementCalculationSummary;
import com.prompthub.settlement.domain.model.SettlementSourceLine;
import com.prompthub.settlement.domain.repository.SettlementCalculationReconciliationRepository;
import com.prompthub.settlement.domain.repository.SettlementDeliveryRepository;
import com.prompthub.settlement.domain.repository.SettlementRepository;
import com.prompthub.settlement.domain.repository.SettlementSourceRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettlementCalculationReconciliationApplicationService
        implements ReconcileSettlementCalculationUseCase {

    private final SettlementRepository settlementRepository;
    private final SettlementSourceRepository sourceRepository;
    private final SettlementCalculationReconciliationRepository reconciliationRepository;
    private final SettlementDeliveryRepository deliveryRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettlementCalculationReconciliationReport reconcile(
            UUID settlementBatchId) {
        LocalDateTime verifiedAt = LocalDateTime.now();
        List<Verification> verifications =
                settlementRepository.findBySettlementBatchId(settlementBatchId)
                        .stream()
                        .map(settlement -> verify(settlement, verifiedAt))
                        .toList();
        List<SettlementCalculationReconciliation> reconciliations =
                verifications.stream()
                        .map(Verification::reconciliation)
                        .toList();
        reconciliationRepository.saveAll(reconciliations);
        cleanupMismatched(verifications);
        return SettlementCalculationReconciliationReport.from(reconciliations);
    }

    private Verification verify(
            Settlement settlement,
            LocalDateTime verifiedAt) {
        List<SettlementSourceLine> sourceLines =
                sourceRepository.findBySettlementId(settlement.getId());
        SettlementCalculationReconciliation reconciliation =
                SettlementCalculationReconciliation.verify(
                        settlement.getSettlementBatchId(),
                        settlement.getId(),
                        SettlementCalculationSummary.from(settlement),
                        settlement.getDetails(),
                        sourceLines.stream()
                                .map(SettlementSourceLine::getId)
                                .toList(),
                        verifiedAt);
        return new Verification(settlement, sourceLines, reconciliation);
    }

    private void cleanupMismatched(List<Verification> verifications) {
        List<Verification> mismatched = verifications.stream()
                .filter(verification -> !verification.reconciliation().isMatched())
                .toList();
        if (mismatched.isEmpty()) {
            return;
        }

        List<UUID> settlementIds = mismatched.stream()
                .map(verification -> verification.settlement().getId())
                .toList();
        deliveryRepository.deleteBySettlementIds(settlementIds);
        mismatched.forEach(verification ->
                verification.sourceLines().forEach(sourceLine ->
                        sourceLine.release(verification.settlement().getId())));
        settlementRepository.deleteAll(mismatched.stream()
                .map(Verification::settlement)
                .toList());
    }

    private record Verification(
            Settlement settlement,
            List<SettlementSourceLine> sourceLines,
            SettlementCalculationReconciliation reconciliation) {
    }
}
