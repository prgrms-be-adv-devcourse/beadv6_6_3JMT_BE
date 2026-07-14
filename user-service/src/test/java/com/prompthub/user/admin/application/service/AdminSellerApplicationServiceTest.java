package com.prompthub.user.admin.application.service;

import com.prompthub.user.admin.application.dto.ApproveSellerCommand;
import com.prompthub.user.auth.domain.repository.AuthorizationCacheRepository;
import com.prompthub.user.seller.domain.model.SellerRegister;
import com.prompthub.user.seller.domain.repository.SellerRegisterRepository;
import com.prompthub.user.user.domain.model.User;
import com.prompthub.user.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class AdminSellerApplicationServiceTest {

    @Mock
    private SellerRegisterRepository sellerRegisterRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthorizationCacheRepository authorizationCacheRepository;

    @InjectMocks
    private AdminSellerApplicationService adminSellerApplicationService;

    @Test
    void approve_성공_시_authorize_캐시_무효화() {
        UUID userId = UUID.randomUUID();
        SellerRegister register = SellerRegister.create(userId, List.of("marketing"), null, null, true);
        User user = mock(User.class);
        given(user.getUserId()).willReturn(userId);

        given(sellerRegisterRepository.findById(register.getSellerRegisterId()))
                .willReturn(Optional.of(register));
        given(userRepository.findById(userId)).willReturn(Optional.of(user));
        given(userRepository.save(user)).willReturn(user);

        adminSellerApplicationService.approve(new ApproveSellerCommand(register.getSellerRegisterId()));

        then(authorizationCacheRepository).should().evict(userId);
    }
}
