package com.prompthub.order.global.web;

import java.util.Arrays;

public final class AuthHeaderRoles {

	private AuthHeaderRoles() {
	}

	public static boolean hasRole(String rawRoles, String requiredRole) {
		if (rawRoles == null || rawRoles.isBlank() || requiredRole == null || requiredRole.isBlank()) {
			return false;
		}

		return Arrays.stream(rawRoles.split("[,\\s]+"))
			.map(String::trim)
			.anyMatch(requiredRole::equals);
	}
}
