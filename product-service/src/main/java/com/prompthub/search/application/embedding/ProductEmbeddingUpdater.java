package com.prompthub.search.application.embedding;


import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 판매 중인 상품의 임베딩을 최신 상태로 유지한다.
 *
 * <p>생성 경로는 재조정 배치 하나다. 쓰기 흐름에 붙이지 않은 이유는 MAJOR 업데이트로 새
 * 버전이 생길 때와 admin이 승인해 ON_SALE이 될 때 {@code product-events}가 발행되지 않아
 * 그 두 경우의 임베딩이 영원히 생기지 않기 때문이다. 대신 최대 20초 지연을 받아들인다.
 *
 * <p>배치는 {@code updated_at} 기준으로 대상을 고르므로 조회수 증가만으로도 상품이 대상에
 * 걸린다. 그래서 원문 해시로 한 번 더 거른다 — 이게 없으면 상품을 열어보기만 해도 OpenAI를
 * 호출한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEmbeddingUpdater {

	private final EmbeddingClient embeddingClient;
	private final ProductRepository productRepository;

	public void refresh(List<Product> products) {
		if (products.isEmpty()) {
			return;
		}

		Map<UUID, String> storedHashes = productRepository.findEmbeddingSourceHashes(
			products.stream().map(Product::getId).toList());

		for (Product product : products) {
			EmbeddingSource source = EmbeddingSource.of(product);
			String hash = source.hash();
			if (hash.equals(storedHashes.get(product.getId()))) {
				continue;
			}

			float[] embedding = embeddingClient.embed(source.text());
			if (embedding == null) {
				// 해시를 저장하지 않으므로 다음 사이클에 다시 대상이 된다. 여기서 재시도하지 않는다.
				continue;
			}

			productRepository.updateEmbedding(product.getId(), embedding, hash);
			log.debug("임베딩을 갱신했습니다. productId={}", product.getId());
		}
	}
}
