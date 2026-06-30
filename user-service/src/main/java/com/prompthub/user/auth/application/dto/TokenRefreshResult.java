package com.prompthub.user.auth.application.dto;

import java.time.Instant;

public record TokenRefreshResult(String accessToken, Instant expiresAt) {}
