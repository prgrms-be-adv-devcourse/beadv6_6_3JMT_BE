package com.prompthub.product.application.usecase;

import com.prompthub.product.presentation.dto.response.ProductCartSnapshotResponse;
import com.prompthub.product.presentation.dto.response.ProductContentResponse;
import com.prompthub.product.presentation.dto.response.ProductListItemResponse;
import com.prompthub.product.presentation.dto.response.ProductOrderSnapshotResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ProductGrpcUseCase {

	List<ProductOrderSnapshotResponse> getOrderSnapshots(List<UUID> productIds);

	List<ProductCartSnapshotResponse> getCartSnapshots(List<UUID> productIds);

	ProductContentResponse getProductContent(UUID productId);

	/**
	 * 기준 상품마다 비슷한 상품 순위를 매겨 돌려준다. (ai-service 소비)
	 *
	 * <p>여러 기준의 순위를 <b>합치지 않는다.</b> 어떤 기준에 얼마나 무게를 둘지, 무엇을 빼고
	 * 몇 개를 보여줄지는 호출자가 정한다 — 여기서 합치면 "누구에게 무엇을 추천할지"라는 판단이
	 * 이 서비스로 넘어온다.
	 *
	 * <p>기준 상품이 판매 중이 아니거나 임베딩이 아직 없으면 <b>그 기준만</b> 빈 목록이 된다.
	 * 기준 하나가 실패해도 나머지 순위는 그대로 돌려준다.
	 *
	 * @return 기준 상품 id → 가까운 순으로 정렬된 추천 목록
	 */
	Map<UUID, List<ProductListItemResponse>> getSimilarProducts(List<UUID> seedProductIds, int limitPerSeed);
}
