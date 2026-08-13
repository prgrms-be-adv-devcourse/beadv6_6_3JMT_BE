package com.prompthub.ai.recommendation.presentation.rest;

import com.prompthub.ai.recommendation.application.port.PersonalizedProductQuery.RecommendedProduct;
import com.prompthub.ai.recommendation.application.usecase.RecommendationUseCase;
import com.prompthub.presentation.dto.ApiResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/ai/recommendations")
@Tag(name = "AI Recommendation", description = "사용자 활동 기반 맞춤 상품 추천 API")
public class RecommendationController {

    private static final int DEFAULT_LIMIT = 4;
    private static final int MAX_LIMIT = 20;

    private final RecommendationUseCase recommendationUseCase;

    public RecommendationController(RecommendationUseCase recommendationUseCase) {
        this.recommendationUseCase = recommendationUseCase;
    }

    /**
     * 활동 내역(장바구니·구매)은 order-service가 소유하므로 호출자가 실어 보낸다. 이 서비스는
     * 활동을 저장하지 않고 받은 것만으로 판단한다.
     *
     * <p>활동이 없거나 추천할 상품이 없으면 <b>빈 배열</b>을 200으로 돌려준다. 에러가 아니라
     * "보여줄 게 없음"이 맞는 상태이고, 화면은 섹션을 숨기면 된다.
     */
    @GetMapping
    @Operation(
            summary = "맞춤 상품 추천",
            description = "장바구니·구매 상품을 기준으로 비슷한 상품을 추천합니다. "
                    + "장바구니를 더 무겁게 반영하며, 이미 담았거나 구매한 상품은 제외합니다. "
                    + "활동 내역이 없으면 빈 배열을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "추천 목록 조회 성공(추천할 게 없으면 빈 배열)")
    })
    public ApiResult<List<RecommendedProduct>> recommend(
            @Parameter(description = "장바구니에 담은 상품 ID 목록") @RequestParam(required = false) List<UUID> cartProductIds,
            @Parameter(description = "구매한 상품 ID 목록") @RequestParam(required = false) List<UUID> purchasedProductIds,
            @Parameter(description = "받아올 추천 개수(기본 4, 최대 20)") @RequestParam(required = false) Integer limit
    ) {
        return ApiResult.success(
                recommendationUseCase.recommend(cartProductIds, purchasedProductIds, normalizeLimit(limit)));
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
