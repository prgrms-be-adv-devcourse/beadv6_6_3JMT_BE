package com.prompthub.user.seller.application.service;

import com.prompthub.user.seller.application.dto.RegisterSellerCommand;
import com.prompthub.user.seller.application.dto.RegisterSellerResult;
import com.prompthub.user.seller.domain.exception.SellerAlreadyAppliedException;
import com.prompthub.user.seller.domain.model.SellerRegisterStatus;
import com.prompthub.user.seller.domain.repository.SellerRegisterRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class SellerApplicationServiceTest {

    @Mock
    private SellerRegisterRepository sellerRegisterRepository;

    @InjectMocks
    private SellerApplicationService sellerApplicationService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final List<SellerRegisterStatus> ACTIVE_STATUSES =
            List.of(SellerRegisterStatus.PENDING, SellerRegisterStatus.APPROVED);

    private RegisterSellerCommand defaultCommand() {
        return new RegisterSellerCommand(USER_ID, List.of("마케팅", "코딩"), "소개글", "https://blog.example.com", true);
    }

    @Test
    void register_정상_신청_PENDING_상태로_반환() {
        given(sellerRegisterRepository.existsByUserIdAndStatusIn(USER_ID, ACTIVE_STATUSES)).willReturn(false);
        given(sellerRegisterRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        RegisterSellerResult result = sellerApplicationService.register(defaultCommand());

        assertThat(result.status()).isEqualTo(SellerRegisterStatus.PENDING);
        assertThat(result.sellerRequestId()).isNotNull();
        assertThat(result.submittedAt()).isNotNull();
    }

    @Test
    void register_저장소에_저장_호출됨() {
        given(sellerRegisterRepository.existsByUserIdAndStatusIn(USER_ID, ACTIVE_STATUSES)).willReturn(false);
        given(sellerRegisterRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        sellerApplicationService.register(defaultCommand());

        then(sellerRegisterRepository).should().save(any());
    }

    @Test
    void register_PENDING_신청_존재하면_SellerAlreadyAppliedException() {
        given(sellerRegisterRepository.existsByUserIdAndStatusIn(USER_ID, ACTIVE_STATUSES)).willReturn(true);

        assertThatThrownBy(() -> sellerApplicationService.register(defaultCommand()))
                .isInstanceOf(SellerAlreadyAppliedException.class);

        then(sellerRegisterRepository).should().existsByUserIdAndStatusIn(USER_ID, ACTIVE_STATUSES);
        then(sellerRegisterRepository).shouldHaveNoMoreInteractions();
    }

    @Test
    void register_APPROVED_신청_존재하면_SellerAlreadyAppliedException() {
        given(sellerRegisterRepository.existsByUserIdAndStatusIn(USER_ID, ACTIVE_STATUSES)).willReturn(true);

        assertThatThrownBy(() -> sellerApplicationService.register(defaultCommand()))
                .isInstanceOf(SellerAlreadyAppliedException.class);
    }

    @Test
    void register_REJECTED_후_재신청_가능() {
        given(sellerRegisterRepository.existsByUserIdAndStatusIn(USER_ID, ACTIVE_STATUSES)).willReturn(false);
        given(sellerRegisterRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        RegisterSellerResult result = sellerApplicationService.register(defaultCommand());

        assertThat(result.status()).isEqualTo(SellerRegisterStatus.PENDING);
    }
}
