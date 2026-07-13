package com.prompthub.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import com.prompthub.user.admin.presentation.controller.AdminSellerController;
import com.prompthub.user.admin.presentation.controller.AdminUserController;
import com.prompthub.user.auth.presentation.controller.AuthController;
import com.prompthub.user.seller.presentation.controller.SellerController;
import com.prompthub.user.sellersettlement.presentation.controller.SellerSettlementController;
import com.prompthub.user.user.presentation.controller.UserController;
import com.prompthub.user.wishlist.presentation.controller.WishlistController;

class ApiVersionMappingTest {

    @ParameterizedTest(name = "{0} 매핑은 {2}이다")
    @MethodSource("controllerMappings")
    @DisplayName("컨트롤러별 API 버전 매핑을 유지한다")
    void mapsControllerToExpectedApiVersion(String name, Class<?> controllerType, String expectedPath) {
        RequestMapping requestMapping = AnnotatedElementUtils.findMergedAnnotation(
                controllerType, RequestMapping.class);

        assertThat(requestMapping).isNotNull();
        assertThat(requestMapping.value()).containsExactly(expectedPath);
    }

    private static Stream<Arguments> controllerMappings() {
        return Stream.of(
                arguments("인증", AuthController.class, "/api/v1/auth"),
                arguments("사용자", UserController.class, "/api/v2/users"),
                arguments("판매자", SellerController.class, "/api/v2/seller"),
                arguments("판매자 정산", SellerSettlementController.class, "/api/v2/sellers/me/settlements"),
                arguments("찜", WishlistController.class, "/api/v2/wishlists"),
                arguments("관리자 사용자", AdminUserController.class, "/api/v2/admin"),
                arguments("관리자 판매자", AdminSellerController.class, "/api/v2/admin")
        );
    }
}
