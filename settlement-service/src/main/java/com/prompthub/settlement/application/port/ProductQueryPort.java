package com.prompthub.settlement.application.port;

import java.util.UUID;

/**
 * 판매자의 등록 상품 수를 타 서비스(Product)에서 동기 조회하는 아웃바운드 포트.
 * 정산 요약의 registeredPromptCount 를 채운다. 영속성이 아니므로 application/port 에 둔다.
 */
public interface ProductQueryPort {

    /**
     * 판매자가 등록한 상품 수를 조회한다.
     * 조회에 실패하면 0 을 반환해 정산 요약 조회 자체는 막지 않는다.
     */
    int countBySeller(UUID sellerId);
}
