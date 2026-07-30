package com.prompthub.user.auth.domain.model;

import java.time.Instant;

public record RejoinToken(String value, Instant expiresAt) {
}
