package com.prompthub.search.application;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * RDB와 ES 색인을 맞추는 재조정. 두 경로가 있다.
 *
 * <p><b>증분({@link #reconcileChanged})</b> — 짧은 주기로 돈다. 마지막 실행 이후 변경된
 * family만 골라 반영하므로 변경이 없으면 조회 2건으로 끝나고 ES에 아무것도 쓰지 않는다.
 * admin-service발 승인/승인취소처럼 실시간 이벤트가 없는 변화를 잡는 것이 주 목적이다
 * (product-service 자체 발 변화는 ProductSearchEventHandler가 실시간 반영한다).
 *
 * <p><b>전체({@link #reconcileAll})</b> — 기동 시 1회와 하루 1회 돈다. 증분이 잡지 못하는
 * ES 고아 문서(드리프트, 수동 DB 편집, ES측 유실)를 정리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductReindexService {

	private final ProductRepository productRepository;
	private final ProductSearchIndexer productSearchIndexer;
	private final FamilyStatsResolver familyStatsResolver;
	private final ProductEmbeddingUpdater productEmbeddingUpdater;

	/**
	 * 마지막 실행 이후 변경된 family만 재조정한다.
	 *
	 * @return 실제로 수행했으면 {@code true}, 인덱스가 없어 건너뛰었으면 {@code false}
	 */
	public boolean reconcileChanged(LocalDateTime since) {
		if (!productSearchIndexer.indexExists()) {
			log.info("ES 인덱스가 아직 없어 이번 증분 재조정을 건너뜁니다.");
			return false;
		}

		List<UUID> changedFamilyRootIds = productRepository.findChangedFamilyRootIds(since);
		if (changedFamilyRootIds.isEmpty()) {
			return true;
		}

		Reconciliation reconciliation = buildReconciliation(changedFamilyRootIds);
		productSearchIndexer.bulkReconcile(reconciliation.toUpsert(), reconciliation.toDelete());
		log.info("증분 재조정 완료. since={}, upsert={}, delete={}",
			since, reconciliation.toUpsert().size(), reconciliation.toDelete().size());
		return true;
	}

	/**
	 * RDB의 ON_SALE 전체와 ES 색인 전체를 대조해 맞춘다. 증분이 놓치는 고아 문서를 정리한다.
	 *
	 * @return 실제로 수행했으면 {@code true}, 인덱스가 없어 건너뛰었으면 {@code false}
	 */
	public boolean reconcileAll() {
		if (!productSearchIndexer.indexExists()) {
			log.info("ES 인덱스가 아직 없어 이번 전체 재조정을 건너뜁니다.");
			return false;
		}

		Set<UUID> onSaleFamilyRootIds = productRepository.findAllByStatus(ProductStatus.ON_SALE).stream()
			.map(Product::familyRootId)
			.collect(Collectors.toSet());

		Reconciliation reconciliation = buildReconciliation(onSaleFamilyRootIds);

		List<UUID> orphaned = productSearchIndexer.findAllIndexedFamilyRootIds().stream()
			.filter(indexed -> !onSaleFamilyRootIds.contains(indexed))
			.toList();

		List<UUID> toDelete = new ArrayList<>(reconciliation.toDelete());
		toDelete.addAll(orphaned);

		productSearchIndexer.bulkReconcile(reconciliation.toUpsert(), toDelete);
		log.info("전체 재조정 완료. upsert={}, delete={}", reconciliation.toUpsert().size(), toDelete.size());
		return true;
	}

	/**
	 * 대상 family들의 멤버·평점을 배치로 조회해 upsert/delete 목록을 만든다.
	 * family 수와 무관하게 쿼리는 2건(멤버 일괄 조회 + 평점 일괄 조회)이다.
	 *
	 * <p>임베딩 갱신도 여기서 함께 돈다. 이 서비스에 {@code @Transactional}이 없어 OpenAI
	 * 호출이 트랜잭션을 물고 늘어지지 않으므로, 배치가 임베딩을 채우기에 적합한 자리다.
	 */
	private Reconciliation buildReconciliation(Collection<UUID> familyRootIds) {
		List<UUID> targets = List.copyOf(familyRootIds);
		if (targets.isEmpty()) {
			return new Reconciliation(List.of(), List.of());
		}

		Map<UUID, List<Product>> membersByFamily = productRepository.findAllByFamilyRootIds(targets).stream()
			.collect(Collectors.groupingBy(Product::familyRootId));
		Map<UUID, Double> averageRatings = productRepository.getAverageRatings(targets);

		List<FamilyUpsertInput> toUpsert = new ArrayList<>();
		List<UUID> toDelete = new ArrayList<>();
		for (UUID familyRootId : targets) {
			List<Product> members = membersByFamily.getOrDefault(familyRootId, List.of());
			ProductFamily family = ProductFamily.of(familyRootId, members);
			family.currentOnSale().ifPresentOrElse(
				onSale -> toUpsert.add(familyStatsResolver.resolve(
					members, onSale, averageRatings.getOrDefault(familyRootId, 0.0))),
				() -> toDelete.add(familyRootId)
			);
		}

		productEmbeddingUpdater.refresh(toUpsert.stream().map(FamilyUpsertInput::onSale).toList());
		return new Reconciliation(toUpsert, toDelete);
	}

	private record Reconciliation(List<FamilyUpsertInput> toUpsert, List<UUID> toDelete) {
	}
}
