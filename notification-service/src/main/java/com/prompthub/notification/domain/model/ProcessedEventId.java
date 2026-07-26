package com.prompthub.notification.domain.model;

import java.io.Serializable;
import java.util.UUID;

public record ProcessedEventId(UUID eventId, String consumerGroup) implements Serializable {
}
