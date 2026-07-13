package com.prompthub.user.auth.domain.model;

import com.prompthub.user.user.domain.model.UserRole;
import com.prompthub.user.user.domain.model.UserStatus;

public record AuthzSnapshot(UserStatus status, UserRole role) {
}
