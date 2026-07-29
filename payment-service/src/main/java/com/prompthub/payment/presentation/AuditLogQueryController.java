package com.prompthub.payment.presentation;

import com.prompthub.payment.application.usecase.GetAuditLogsSinceUseCase;
import com.prompthub.payment.presentation.dto.response.AuditLogResponse;
import com.prompthub.presentation.dto.ApiResult;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Logstash http_poller 재조정 전용 내부 엔드포인트.
 * Gateway 라우팅 표에 등록하지 않아 클러스터 밖에서 호출할 수 없다 — 인증 헤더를 두지 않는 이유.
 */
@RestController
@RequiredArgsConstructor
public class AuditLogQueryController {

    private static final int RECONCILIATION_WINDOW_MINUTES = 15;

    private final GetAuditLogsSinceUseCase getAuditLogsSinceUseCase;

    @GetMapping("/internal/audit-logs")
    public ResponseEntity<ApiResult<List<AuditLogResponse>>> getRecentAuditLogs(
        @RequestParam(required = false) OffsetDateTime since
    ) {
        OffsetDateTime effectiveSince = since != null
            ? since
            : OffsetDateTime.now().minusMinutes(RECONCILIATION_WINDOW_MINUTES);
        List<AuditLogResponse> responses = getAuditLogsSinceUseCase.getSince(effectiveSince).stream()
            .map(AuditLogResponse::from)
            .toList();
        return ResponseEntity.ok(ApiResult.success(responses));
    }
}
