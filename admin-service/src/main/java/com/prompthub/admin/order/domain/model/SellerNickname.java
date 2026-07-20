package com.prompthub.admin.order.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * user-service 소유 "user" 테이블의 읽기 전용 재매핑. 판매자 닉네임(user.name)만 조회한다.
 * 판매자 식별자는 user-service 도메인 컨벤션에 따라 user.user_id 를 그대로 쓴다
 * (별도 seller 테이블 없음 — docs/domain-glossary/user.md 참고).
 */
@Entity
@Table(name = "\"user\"")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerNickname {

	@Id
	@Column(name = "user_id", columnDefinition = "uuid")
	private UUID sellerId;

	@Column(name = "name", length = 100, nullable = false)
	private String nickname;
}
