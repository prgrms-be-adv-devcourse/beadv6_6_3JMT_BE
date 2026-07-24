package com.prompthub.admin.user.domain.model;

import java.util.UUID;

/**
 * User profile fields used by read-only cross-domain lookups.
 * This projection intentionally excludes roles and other User entity state.
 */
public record UserProfile(UUID userId, String name, String profileImageUrl) {
}
