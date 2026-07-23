package com.prompthub.search.infra.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.search.application.ProductSearchIndexer;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * reviewCount는 아직 집계 쿼리가 없어 0으로 둔다 — 필요해지면
 * ProductRepository에 countActiveReviews(familyRootId) 추가 후 채운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ElasticsearchProductSearchIndexer implements ProductSearchIndexer {

	private final ElasticsearchClient client;

	@Override
	public void upsert(Product onSale, long familySalesCount, double averageRating, LocalDateTime firstPublishedAt) {
		ProductSearchDocument document = new ProductSearchDocument(
			onSale.familyRootId(),
			onSale.getId(),
			onSale.getSellerId(),
			onSale.getName(),
			onSale.getDescription(),
			onSale.getContent(),
			onSale.getTags(),
			onSale.getProductType().name(),
			onSale.getModel(),
			onSale.getAmount(),
			onSale.getAmountType().name(),
			onSale.getThumbnailUrl(),
			onSale.getBadge(),
			(int) familySalesCount,
			onSale.getViewCount(),
			0,
			averageRating,
			firstPublishedAt,
			onSale.getUpdatedAt()
		);
		try {
			client.index(i -> i.index(ProductIndexBootstrap.ALIAS).id(document.familyRootId().toString()).document(document));
		} catch (IOException e) {
			throw new IllegalStateException("ES 색인에 실패했습니다. familyRootId=" + document.familyRootId(), e);
		}
	}

	@Override
	public void delete(UUID familyRootId) {
		try {
			client.delete(d -> d.index(ProductIndexBootstrap.ALIAS).id(familyRootId.toString()));
		} catch (IOException e) {
			throw new IllegalStateException("ES 문서 삭제에 실패했습니다. familyRootId=" + familyRootId, e);
		}
	}

	@Override
	public void updatePrice(UUID familyRootId, int changedPrice) {
		try {
			client.update(
				u -> u.index(ProductIndexBootstrap.ALIAS).id(familyRootId.toString()).doc(Map.of("amount", changedPrice)),
				Map.class
			);
		} catch (IOException e) {
			throw new IllegalStateException("ES 가격 부분 갱신에 실패했습니다. familyRootId=" + familyRootId, e);
		}
	}

	/**
	 * 10분 주기 카운트 동기화 전용 — 실패해도 예외를 던지지 않고 로그만 남긴다.
	 * 이 배치는 "바뀐 것만 골라서" 도는 최선의 노력(best-effort) 성격이라, 실패한
	 * family는 다음 변경이 생길 때 다시 잡히거나 온디맨드 풀 리인덱스로 복구하면
	 * 된다 — 실패한 것만 골라 재시도하는 로직은 지금 범위에서 과함(YAGNI).
	 */
	@Override
	public void updateCounts(UUID familyRootId, long salesCount, int viewCount, double ratingAvg) {
		try {
			client.update(
				u -> u.index(ProductIndexBootstrap.ALIAS).id(familyRootId.toString()).doc(Map.of(
					"salesCount", (int) salesCount,
					"viewCount", viewCount,
					"ratingAvg", ratingAvg
				)),
				Map.class
			);
		} catch (IOException e) {
			log.warn("ES 카운트 부분 갱신에 실패했습니다(다음 주기에 재시도됨). familyRootId={}", familyRootId, e);
		}
	}
}
