package com.prompthub.admin.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * user-service 소유 refresh_token 테이블의 읽기+삭제 재매핑. 어드민 세션
 * 폐기(삭제) 전용이라 id·user_id만 매핑한다 — token/epoch/expires_at은
 * 발급·회전 전용 컬럼이라 admin-service가 쓰지 않는다.
 */
@Entity
@Table(name = "refresh_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

	@Id
	@Column(name = "id", columnDefinition = "uuid")
	private UUID id;

	@Column(name = "user_id", nullable = false, columnDefinition = "uuid")
	private UUID userId;
}
