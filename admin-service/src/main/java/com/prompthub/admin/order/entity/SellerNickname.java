package com.prompthub.admin.order.entity;

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
 * PK 컬럼명은 실제 DDL 기준 "id"(user-service V1__baseline.sql 확인).
 */
@Entity
@Table(name = "\"user\"")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerNickname {

	@Id
	@Column(name = "id", columnDefinition = "uuid")
	private UUID sellerId;

	@Column(name = "name", length = 100, nullable = false)
	private String nickname;
}
