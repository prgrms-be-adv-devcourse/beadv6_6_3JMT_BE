package com.prompthub.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdMdcFilterTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void storesIncomingRequestIdDuringFilterChainAndRemovesItAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdMdcFilter.REQUEST_ID_HEADER, "request-600");
        AtomicReference<String> requestIdDuringChain = new AtomicReference<>();

        new RequestIdMdcFilter().doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> requestIdDuringChain.set(MDC.get(RequestIdMdcFilter.MDC_KEY)));

        assertThat(requestIdDuringChain).hasValue("request-600");
        assertThat(MDC.get(RequestIdMdcFilter.MDC_KEY)).isNull();
    }

    @Test
    void generatesUuidWhenRequestIdHeaderIsMissing() throws Exception {
        AtomicReference<String> requestIdDuringChain = new AtomicReference<>();

        new RequestIdMdcFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> requestIdDuringChain.set(MDC.get(RequestIdMdcFilter.MDC_KEY)));

        assertThat(requestIdDuringChain).isNotNull();
        assertThatCode(() -> UUID.fromString(requestIdDuringChain.get())).doesNotThrowAnyException();
        assertThat(MDC.get(RequestIdMdcFilter.MDC_KEY)).isNull();
    }

    @Test
    void removesRequestIdWhenFilterChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdMdcFilter.REQUEST_ID_HEADER, "request-600");

        assertThatThrownBy(() -> new RequestIdMdcFilter().doFilter(request, new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> {
                    throw new IllegalStateException("boom");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");

        assertThat(MDC.get(RequestIdMdcFilter.MDC_KEY)).isNull();
    }
}
