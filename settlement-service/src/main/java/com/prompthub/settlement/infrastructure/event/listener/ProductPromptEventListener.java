package com.prompthub.settlement.infrastructure.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * product 서비스의 프롬프트 등록/삭제 이벤트를 수신해 판매자별 등록 프롬프트 수를 집계하기 위한 어댑터.
 *
 * <p>지금은 골격만 둔다. 실제 이벤트 수신 인프라(Kafka 등)와 멱등 처리·집계 저장은 별도 이슈에서 구현한다.
 * 집계 결과는 판매자 정산 요약 응답의 registeredPromptCount(현재 0 고정)에 반영한다.
 */
@Slf4j
@Component
public class ProductPromptEventListener {

	// TODO(별도 이슈): product 프롬프트 등록 이벤트 수신 → 판매자별 등록 수 +1
	//  - 이벤트 수신 채널(Kafka 등) 연결, event_id 기반 멱등 처리
	//  - 판매자별 프롬프트 수 집계 저장소 추가
	//  - SellerSettlementApplicationService.getMySummary의 registeredPromptCount=0 자리를 집계값으로 대체
	public void handlePromptRegistered() {
		log.warn("product 프롬프트 등록 이벤트 수신 미구현(골격). 별도 이슈에서 처리한다.");
	}

	// TODO(별도 이슈): product 프롬프트 삭제 이벤트 수신 → 판매자별 등록 수 -1
	public void handlePromptDeleted() {
		log.warn("product 프롬프트 삭제 이벤트 수신 미구현(골격). 별도 이슈에서 처리한다.");
	}
}
