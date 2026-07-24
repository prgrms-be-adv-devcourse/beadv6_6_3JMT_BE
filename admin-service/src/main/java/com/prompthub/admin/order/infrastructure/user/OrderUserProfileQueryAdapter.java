package com.prompthub.admin.order.infrastructure.user;

import com.prompthub.admin.order.application.dto.OrderUserProfile;
import com.prompthub.admin.order.application.port.OrderUserProfileQueryPort;
import com.prompthub.admin.user.domain.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OrderUserProfileQueryAdapter implements OrderUserProfileQueryPort {

    private final UserRepository userRepository;

    @Override
    public Map<UUID, OrderUserProfile> findProfilesByUserIds(List<UUID> userIds) {
        List<UUID> distinctIds = userIds.stream().distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findProfilesByIds(distinctIds).stream()
                .map(profile -> new OrderUserProfile(
                        profile.userId(), profile.name(), profile.profileImageUrl()))
                .collect(Collectors.toUnmodifiableMap(OrderUserProfile::userId, Function.identity()));
    }
}
