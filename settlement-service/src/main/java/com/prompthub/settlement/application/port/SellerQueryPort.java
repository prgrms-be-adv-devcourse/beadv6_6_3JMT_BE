package com.prompthub.settlement.application.port;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 판매자 정보를 타 서비스(User)에서 동기 조회하는 아웃바운드 포트.
 * 정산 목록·요약 표시에 필요한 판매자명을 가져온다. 영속성이 아니므로 application/port 에 둔다.
 */
public interface SellerQueryPort {

    /**
     * 판매자 ID 목록으로 판매자명을 조회한다(다건, N+1 회피).
     * 존재하지 않거나 조회에 실패한 판매자는 결과 맵에서 빠진다(부분 결과 허용).
     *
     * @return sellerId → sellerName 매핑
     */
    Map<UUID, String> findSellerNames(List<UUID> sellerIds);
}
