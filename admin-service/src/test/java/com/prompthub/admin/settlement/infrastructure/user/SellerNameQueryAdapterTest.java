package com.prompthub.admin.settlement.infrastructure.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import com.prompthub.admin.user.domain.model.User;
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
class SellerNameQueryAdapterTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SellerNameQueryAdapter adapter;

    @Test
    void 판매자ID를_중복제거해_한번에_조회한다() {
        UUID sellerId = UUID.randomUUID();
        User user = mock(User.class);
        given(user.getUserId()).willReturn(sellerId);
        given(user.getName()).willReturn("프롬프트 상점");
        given(userRepository.findAllByIds(List.of(sellerId))).willReturn(List.of(user));

        Map<UUID, String> result =
                adapter.findNamesBySellerIds(List.of(sellerId, sellerId));

        assertThat(result).containsEntry(sellerId, "프롬프트 상점");
        then(userRepository).should().findAllByIds(List.of(sellerId));
    }

    @Test
    void 빈ID목록이면_사용자저장소를_호출하지_않는다() {
        assertThat(adapter.findNamesBySellerIds(List.of())).isEmpty();
        then(userRepository).shouldHaveNoInteractions();
    }
}
