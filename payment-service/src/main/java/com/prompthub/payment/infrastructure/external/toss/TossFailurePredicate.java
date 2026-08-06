package com.prompthub.payment.infrastructure.external.toss;

import com.prompthub.payment.application.exception.PaymentErrorCode;
import com.prompthub.payment.application.gateway.external.PaymentGatewayException;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.web.client.ResourceAccessException;

public class TossFailurePredicate implements Predicate<Throwable> {

    private static final Set<PaymentErrorCode> SYSTEM_FAILURE_CODES = Set.of(
        PaymentErrorCode.PG_SERVER_ERROR
    );

    @Override
    public boolean test(Throwable throwable) {
        if (throwable instanceof ResourceAccessException) {
            return true;
        }
        return throwable instanceof PaymentGatewayException exception
            && SYSTEM_FAILURE_CODES.contains(exception.getErrorCode());
    }
}
