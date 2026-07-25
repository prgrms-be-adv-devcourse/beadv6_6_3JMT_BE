package com.prompthub.admin.order.infrastructure.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.prompthub.admin.order.application.dto.OrderUserProfile;
import com.prompthub.admin.user.domain.model.UserProfile;
import com.prompthub.admin.user.domain.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderUserProfileQueryAdapterTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private OrderUserProfileQueryAdapter adapter;

    @Test
    void 사용자ID를_중복제거해_이름과_프로필사진을_한번에_조회한다() {
        UUID userId = UUID.randomUUID();
        given(userRepository.findProfilesByIds(List.of(userId))).willReturn(List.of(
                new UserProfile(userId, "구매자A", "https://cdn.example.com/a.png")));

        Map<UUID, OrderUserProfile> result =
                adapter.findProfilesByUserIds(List.of(userId, userId));

        assertThat(result).containsEntry(
                userId, new OrderUserProfile(userId, "구매자A", "https://cdn.example.com/a.png"));
        then(userRepository).should().findProfilesByIds(List.of(userId));
    }

    @Test
    void 빈_사용자ID면_저장소를_호출하지_않는다() {
        assertThat(adapter.findProfilesByUserIds(List.of())).isEmpty();
        then(userRepository).shouldHaveNoInteractions();
    }
}
