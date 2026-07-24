package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.web.client.ResourceAccessException;

public class TossRetryPredicate implements Predicate<Throwable> {

    private static final Set<PaymentErrorCode> RETRYABLE_CODES = Set.of(
        PaymentErrorCode.PG_SERVER_ERROR
    );

    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof ResourceAccessException exception) {
            return !(exception.getCause() instanceof SocketTimeoutException);
        }
        return throwable instanceof PaymentGatewayException exception
            && RETRYABLE_CODES.contains(exception.getErrorCode());
    }
}
