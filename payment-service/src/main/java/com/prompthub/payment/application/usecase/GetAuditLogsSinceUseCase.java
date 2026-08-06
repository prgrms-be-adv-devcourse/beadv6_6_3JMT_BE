package com.prompthub.payment.application.usecase;

import com.prompthub.payment.application.dto.result.AuditLogResult;
import java.time.OffsetDateTime;
import java.util.List;

public interface GetAuditLogsSinceUseCase {
    List<AuditLogResult> getSince(OffsetDateTime since);
}
