package com.prompthub.order.presentation;

import com.prompthub.order.global.web.AuthHeaderRoles;
import com.prompthub.order.global.web.AuthHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthHeaderRolesTest {

	@Test
	@DisplayName("단일 권한 헤더에 필요한 권한이 있으면 true를 반환한다")
	void hasRole_singleRole_returnsTrue() {
		assertThat(AuthHeaderRoles.hasRole(AuthHeaders.BUYER, AuthHeaders.BUYER)).isTrue();
	}

	@Test
	@DisplayName("쉼표로 구분된 복수 권한 헤더에 필요한 권한이 있으면 true를 반환한다")
	void hasRole_commaSeparatedRoles_returnsTrue() {
		assertThat(AuthHeaderRoles.hasRole("USER,SELLER", AuthHeaders.SELLER)).isTrue();
	}

	@Test
	@DisplayName("관리자 권한도 복수 권한 헤더에 포함되어 있으면 true를 반환한다")
	void hasRole_adminRoleInCommaSeparatedRoles_returnsTrue() {
		assertThat(AuthHeaderRoles.hasRole("USER,ADMIN", AuthHeaders.ADMIN)).isTrue();
	}

	@Test
	@DisplayName("공백으로 구분된 복수 권한 헤더에 필요한 권한이 있으면 true를 반환한다")
	void hasRole_spaceSeparatedRoles_returnsTrue() {
		assertThat(AuthHeaderRoles.hasRole("USER SELLER", AuthHeaders.SELLER)).isTrue();
	}

	@Test
	@DisplayName("필요한 권한이 없으면 false를 반환한다")
	void hasRole_missingRequiredRole_returnsFalse() {
		assertThat(AuthHeaderRoles.hasRole(AuthHeaders.SELLER, AuthHeaders.BUYER)).isFalse();
	}

	@Test
	@DisplayName("권한 값은 대소문자를 엄격히 구분한다")
	void hasRole_roleIsCaseSensitive() {
		assertThat(AuthHeaderRoles.hasRole("user,SELLER", AuthHeaders.BUYER)).isFalse();
	}
}
