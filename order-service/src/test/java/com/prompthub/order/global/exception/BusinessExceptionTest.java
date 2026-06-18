package com.prompthub.order.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BusinessExceptionTest {

    @Test
    void defaultMessageComesFromErrorCode() {
        BusinessException exception = new BusinessException(ErrorCode.CART_EMPTY);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CART_EMPTY);
        assertThat(exception.getMessage()).isEqualTo("장바구니가 비어있습니다.");
    }

    @Test
    void customMessageOverridesErrorCodeMessage() {
        BusinessException exception = new BusinessException(
                ErrorCode.ORDER_PRICE_CHANGED,
                "상품 가격이 변경되었습니다. 장바구니를 다시 확인해주세요."
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ORDER_PRICE_CHANGED);
        assertThat(exception.getMessage()).isEqualTo("상품 가격이 변경되었습니다. 장바구니를 다시 확인해주세요.");
    }
}
