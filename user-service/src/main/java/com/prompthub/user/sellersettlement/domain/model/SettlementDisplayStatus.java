package com.prompthub.user.sellersettlement.domain.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SettlementDisplayStatus {

    WAITING("대기"),
    APPROVAL_ON_HOLD("승인 보류"),
    APPROVED("승인"),
    PAYOUT_REQUESTED("지급 신청"),
    PAYOUT_ON_HOLD("지급 보류"),
    PAID("지급 완료"),
    CANCELLED("취소");

    private final String label;
}
