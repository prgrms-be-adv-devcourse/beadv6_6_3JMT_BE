package com.prompthub.user.user.application.dto;

import java.util.UUID;

public record UpdateProfileCommand(
        UUID userId,
        String name,
        String email
) {}
