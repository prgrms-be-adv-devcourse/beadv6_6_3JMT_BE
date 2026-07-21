package com.prompthub.admin.user.domain.model;

import com.prompthub.admin.global.common.BaseEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * user-service 소유 "user" 테이블(+ user_role)의 읽기+쓰기 재매핑. 어드민
 * 액션(목록/통계/상태변경/판매자 승인 시 역할 부여)이 실제로 참조하는
 * 컬럼만 매핑한다 — profile_image_url·terms_agreed는 이 엔드포인트들이
 * 안 써서 매핑하지 않았다. PK 컬럼명은 실제 DDL 기준 "id"(user_role의
 * FK 컬럼명은 "user_id"라 헷갈리기 쉬우니 주의).
 * 상태·역할 규칙의 소유자는 user-service User — 불변식이 바뀌면 같이 맞춘다.
 */
@Entity
@Table(name = "\"user\"")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

	@Id
	@Column(name = "id", columnDefinition = "uuid")
	private UUID userId;

	@Column(name = "name", nullable = false, length = 100)
	private String name;

	@Column(name = "email", nullable = false, length = 255, unique = true)
	private String email;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private UserStatus status;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
	@Enumerated(EnumType.STRING)
	@Column(name = "role", nullable = false, length = 20)
	@Getter(AccessLevel.NONE)
	private Set<UserRole> roles = new HashSet<>();

	public Set<UserRole> getRoles() {
		return Collections.unmodifiableSet(roles);
	}

	public UserRole getPrimaryRole() {
		if (roles.contains(UserRole.ADMIN)) return UserRole.ADMIN;
		if (roles.contains(UserRole.SELLER)) return UserRole.SELLER;
		return UserRole.BUYER;
	}

	public void addRole(UserRole role) {
		this.roles.add(role);
	}

	// buyer<->seller 전환 전용 — seller 지정 시 SELLER를 추가(BUYER는 유지),
	// buyer 지정 시 SELLER만 회수한다. ADMIN은 이 API의 대상이 아니라 건드리지 않는다.
	public void changeRole(UserRole role) {
		if (role == UserRole.SELLER) {
			roles.add(UserRole.SELLER);
		} else {
			roles.remove(UserRole.SELLER);
		}
	}

	public void activate() {
		this.status = UserStatus.ACTIVE;
	}

	public void block() {
		this.status = UserStatus.BLOCKED;
	}

	public void withdraw() {
		this.status = UserStatus.WITHDRAWN;
	}
}
