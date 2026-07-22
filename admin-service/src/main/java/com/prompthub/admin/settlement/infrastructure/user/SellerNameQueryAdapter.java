package com.prompthub.admin.settlement.infrastructure.user;

import com.prompthub.admin.settlement.application.port.SellerNameQueryPort;
import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SellerNameQueryAdapter implements SellerNameQueryPort {

    private final UserRepository userRepository;

    @Override
    public Map<UUID, String> findNamesBySellerIds(List<UUID> sellerIds) {
        List<UUID> distinctIds = sellerIds.stream().distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        return userRepository.findAllByIds(distinctIds).stream()
                .collect(Collectors.toUnmodifiableMap(User::getUserId, User::getName));
    }
}
