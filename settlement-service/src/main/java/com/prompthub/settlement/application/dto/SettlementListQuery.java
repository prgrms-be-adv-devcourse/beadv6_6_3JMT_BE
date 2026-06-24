package com.prompthub.settlement.application.dto;

import com.prompthub.settlement.domain.model.enums.SettlementDisplayStatus;

/**
 * 정산 목록 조회 조건.
 *
 * <p>씨앗(seed) 형태다. 구체 필터·정렬 필드는 목록 세션이 확정한다. (공유 골격 설계 문서 §5 / §6)
 *
 * @param status 표시 상태 필터. null 이면 전체.
 * @param page   0-base 페이지 번호.
 * @param size   페이지 크기.
 */
public record SettlementListQuery(SettlementDisplayStatus status, int page, int size) {
}
