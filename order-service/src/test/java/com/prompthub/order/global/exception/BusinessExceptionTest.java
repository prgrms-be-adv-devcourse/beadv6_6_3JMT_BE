package com.prompthub.order.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BusinessExceptionTest {

    @Test
    @DisplayName("기본 메시지는 ErrorCode 메시지를 사용한다")
    void defaultMessageComesFromErrorCode() {
        BusinessException exception = new BusinessException(ErrorCode.CART_EMPTY);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CART_EMPTY);
        assertThat(exception.getMessage()).isEqualTo("장바구니가 비어 있습니다.");
    }

    @Test
    @DisplayName("사용자 지정 메시지는 ErrorCode 메시지보다 우선한다")
    void customMessageOverridesErrorCodeMessage() {
        BusinessException exception = new BusinessException(
                ErrorCode.ORDER_PRICE_CHANGED,
                "상품 가격이 변경되었습니다. 장바구니를 다시 확인해주세요."
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ORDER_PRICE_CHANGED);
        assertThat(exception.getMessage()).isEqualTo("상품 가격이 변경되었습니다. 장바구니를 다시 확인해주세요.");
    }

    @Test
    @DisplayName("OrderException은 전달받은 주문 에러 코드를 사용한다")
    void orderExceptionUsesGivenErrorCode() {
        OrderException exception = new OrderException(ErrorCode.ORDER_NOT_FOUND);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_FOUND);
        assertThat(exception.getMessage()).isEqualTo("주문을 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("OrderException은 사용자 지정 메시지를 사용할 수 있다")
    void orderExceptionCanUseCustomMessage() {
        OrderException exception = new OrderException(
                ErrorCode.INVALID_INPUT_VALUE,
                "조회되지 않은 상품이 포함되어 있습니다."
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
        assertThat(exception.getMessage()).isEqualTo("조회되지 않은 상품이 포함되어 있습니다.");
    }

    @Test
    @DisplayName("CartException은 전달받은 장바구니 에러 코드를 사용한다")
    void cartExceptionUsesGivenErrorCode() {
        CartException exception = new CartException(ErrorCode.CART_EMPTY);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CART_EMPTY);
        assertThat(exception.getMessage()).isEqualTo("장바구니가 비어 있습니다.");
    }

    @Test
    @DisplayName("CartException은 사용자 지정 메시지를 사용할 수 있다")
    void cartExceptionCanUseCustomMessage() {
        CartException exception = new CartException(
                ErrorCode.CART_PRODUCT_ACCESS_DENIED,
                "장바구니 상품 접근 권한이 없습니다."
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CART_PRODUCT_ACCESS_DENIED);
        assertThat(exception.getMessage()).isEqualTo("장바구니 상품 접근 권한이 없습니다.");
    }
}
