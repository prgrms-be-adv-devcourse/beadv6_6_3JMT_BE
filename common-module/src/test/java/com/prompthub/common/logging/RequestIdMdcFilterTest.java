package com.prompthub.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdMdcFilterTest {

    private final RequestIdMdcFilter filter = new RequestIdMdcFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void 유효한_요청_ID를_MDC에_유지하고_요청_후_제거한다() throws Exception {
        String requestId = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdMdcFilter.REQUEST_ID_HEADER, requestId);
        AtomicReference<String> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> captured.set(MDC.get(RequestIdMdcFilter.MDC_KEY)));

        assertThat(captured.get()).isEqualTo(requestId);
        assertThat(MDC.get(RequestIdMdcFilter.MDC_KEY)).isNull();
    }

    @Test
    void 요청_ID가_없으면_UUID를_생성한다() throws Exception {
        assertGeneratedUuid(new MockHttpServletRequest());
    }

    @Test
    void 요청_ID가_UUID가_아니면_새_UUID로_교체한다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdMdcFilter.REQUEST_ID_HEADER, "forged-request-id");

        assertGeneratedUuid(request);
    }

    @Test
    void 필터_체인이_실패해도_MDC를_제거한다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdMdcFilter.REQUEST_ID_HEADER, UUID.randomUUID().toString());

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> {
                    throw new ServletException("failure");
                })).isInstanceOf(ServletException.class);

        assertThat(MDC.get(RequestIdMdcFilter.MDC_KEY)).isNull();
    }

    private void assertGeneratedUuid(MockHttpServletRequest request) throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> captured.set(MDC.get(RequestIdMdcFilter.MDC_KEY)));

        assertThat(captured.get()).isNotBlank();
        assertThat(UUID.fromString(captured.get())).isNotNull();
        assertThat(MDC.get(RequestIdMdcFilter.MDC_KEY)).isNull();
    }
}
