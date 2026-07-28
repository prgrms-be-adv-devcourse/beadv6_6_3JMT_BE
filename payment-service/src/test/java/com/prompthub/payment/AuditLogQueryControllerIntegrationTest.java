package com.prompthub.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.payment.domain.model.AuditLog;
import com.prompthub.payment.domain.model.Payment;
import com.prompthub.payment.infrastructure.persistence.AuditLogJpaRepository;
import com.prompthub.payment.support.AbstractIntegrationTest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

class AuditLogQueryControllerIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    RestTemplate restTemplate = new RestTemplate();

    @Autowired
    AuditLogJpaRepository auditLogJpaRepository;

    @Test
    void since_파라미터로_그_이후_감사로그만_반환한다() {
        Payment payment = Payment.create(
            UUID.randomUUID(), UUID.randomUUID(), "pgTx-query-1", "TOSS_PAYMENTS", "CARD", 10_000);
        payment.markRequested(OffsetDateTime.now());
        payment.approve(10_000, "CARD", "{}", "{}", OffsetDateTime.now());
        AuditLog before = auditLogJpaRepository.saveAndFlush(AuditLog.forPaymentApproved(payment));
        OffsetDateTime since = before.getCreatedAt().plusNanos(1_000);
        AuditLog after = auditLogJpaRepository.saveAndFlush(AuditLog.forPaymentApproved(payment));

        // OffsetDateTime.toString()은 "+09:00"처럼 쿼리스트링에서 문제되는 문자를 포함한다.
        // UriComponentsBuilder.encode()는 RFC 3986 기준 "+"를 인코딩 대상으로 보지 않아 그대로 두므로,
        // 서블릿 컨테이너가 application/x-www-form-urlencoded 관례로 이를 공백으로 디코딩해 파싱이 깨진다.
        // URLEncoder로 값을 먼저 인코딩("+"→"%2B")한 뒤 build(true)(이미 인코딩된 값으로 처리)를 쓴다.
        String encodedSince = URLEncoder.encode(since.toString(), StandardCharsets.UTF_8);
        URI uri = UriComponentsBuilder.fromUriString("http://localhost:" + port + "/internal/audit-logs")
            .queryParam("since", encodedSince)
            .build(true)
            .toUri();
        ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains(after.getId().toString());
        assertThat(response.getBody()).doesNotContain(before.getId().toString());
    }

    @Test
    void since_파라미터_생략_시_기본_15분_윈도우로_동작한다() {
        String url = "http://localhost:" + port + "/internal/audit-logs";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("\"success\":true");
    }
}
