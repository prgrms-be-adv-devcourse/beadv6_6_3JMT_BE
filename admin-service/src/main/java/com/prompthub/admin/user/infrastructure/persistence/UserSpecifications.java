package com.prompthub.admin.user.infrastructure.persistence;

import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;
import org.springframework.data.jpa.domain.Specification;

public class UserSpecifications {

	private UserSpecifications() {
	}

	public static Specification<User> withStatus(UserStatus status) {
		if (status == null) return (root, query, cb) -> cb.conjunction();
		return (root, query, cb) -> cb.equal(root.get("status"), status);
	}

	public static Specification<User> withRole(UserRole role) {
		if (role == null) return (root, query, cb) -> cb.conjunction();
		return (root, query, cb) -> cb.isMember(role, root.get("roles"));
	}

	public static Specification<User> withKeyword(String keyword) {
		if (keyword == null || keyword.isBlank()) return (root, query, cb) -> cb.conjunction();
		return (root, query, cb) -> {
			String pattern = "%" + keyword.toLowerCase() + "%";
			return cb.or(
				cb.like(cb.lower(root.get("name")), pattern),
				cb.like(cb.lower(root.get("email")), pattern)
			);
		};
	}
}
