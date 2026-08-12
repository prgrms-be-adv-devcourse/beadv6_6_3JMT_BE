package com.prompthub.search.application.indexing;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.search.application.embedding.ProductEmbeddingUpdater;
import com.prompthub.product.domain.model.entity.ProductFamily;
import com.prompthub.product.domain.model.enums.ProductStatus;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * RDB와 ES 색인을 맞추는 재조정. 실시간 Kafka 색인 경로 없이 이 두 경로만으로 RDB→ES를
 * scheduler-only eventual consistency로 유지한다(PR5 로드맵 I-2).
 *
 * <p><b>증분({@link #reconcileChanged})</b> — 짧은 주기로 돈다. 마지막 실행 이후 변경된
 * family만 골라 반영하므로 변경이 없으면 조회 2건으로 끝나고 ES에 아무것도 쓰지 않는다.
 * product-service 자체 변경(생성·수정·판매중단·삭제)과 admin-service발 승인/승인취소를
 * 구분하지 않고 이 경로 하나로 잡는다 — 검색 결과는 최대 이 주기만큼 늦게 수렴한다.
 *
 * <p><b>전체({@link #reconcileAll})</b> — 기동 시 1회와 하루 1회 돈다. 증분이 잡지 못하는
 * ES 고아 문서(드리프트, 수동 DB 편집, ES측 유실)를 정리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductReindexService {

	private final ProductRepository productRepository;
	private final ProductSearchIndexPort productSearchIndexPort;
	private final FamilyStatsResolver familyStatsResolver;
	private final ProductEmbeddingUpdater productEmbeddingUpdater;

	/**
	 * 마지막 실행 이후 변경된 family만 재조정한다.
	 *
	 * @return 실제로 수행했으면 {@code true}, 인덱스가 없어 건너뛰었으면 {@code false}
	 */
	public boolean reconcileChanged(LocalDateTime since) {
		if (!productSearchIndexPort.indexExists()) {
			log.info("ES 인덱스가 아직 없어 이번 증분 재조정을 건너뜁니다.");
			return false;
		}

		List<UUID> changedFamilyRootIds = productRepository.findChangedFamilyRootIds(since);
		if (changedFamilyRootIds.isEmpty()) {
			return true;
		}

		Reconciliation reconciliation = buildReconciliation(changedFamilyRootIds);
		productSearchIndexPort.bulkReconcile(reconciliation.toUpsert(), reconciliation.toDelete());
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
		if (!productSearchIndexPort.indexExists()) {
			log.info("ES 인덱스가 아직 없어 이번 전체 재조정을 건너뜁니다.");
			return false;
		}

		Set<UUID> onSaleFamilyRootIds = productRepository.findAllByStatus(ProductStatus.ON_SALE).stream()
			.map(Product::familyRootId)
			.collect(Collectors.toSet());

		Reconciliation reconciliation = buildReconciliation(onSaleFamilyRootIds);

		List<UUID> orphaned = productSearchIndexPort.findAllIndexedFamilyRootIds().stream()
			.filter(indexed -> !onSaleFamilyRootIds.contains(indexed))
			.toList();

		List<UUID> toDelete = new ArrayList<>(reconciliation.toDelete());
		toDelete.addAll(orphaned);

		productSearchIndexPort.bulkReconcile(reconciliation.toUpsert(), toDelete);
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

		Map<UUID, Product> onSaleByFamily = new LinkedHashMap<>();
		List<UUID> toDelete = new ArrayList<>();
		for (UUID familyRootId : targets) {
			List<Product> members = membersByFamily.getOrDefault(familyRootId, List.of());
			ProductFamily.of(familyRootId, members).currentOnSale().ifPresentOrElse(
				onSale -> onSaleByFamily.put(familyRootId, onSale),
				() -> toDelete.add(familyRootId)
			);
		}

		List<Product> onSaleProducts = List.copyOf(onSaleByFamily.values());
		Map<UUID, float[]> storedEmbeddings = productRepository.findEmbeddings(
			onSaleProducts.stream().map(Product::getId).toList());
		// refreshAndGet을 upsert 조립보다 먼저 실행해, 이번 사이클에 새로 계산된 임베딩이
		// 다음 사이클까지 밀리지 않고 같은 bulk 요청에 실리게 한다.
		Map<UUID, float[]> refreshedEmbeddings = productEmbeddingUpdater.refreshAndGet(onSaleProducts);

		List<FamilyUpsertInput> toUpsert = new ArrayList<>();
		for (Map.Entry<UUID, Product> entry : onSaleByFamily.entrySet()) {
			UUID familyRootId = entry.getKey();
			Product onSale = entry.getValue();
			List<Product> members = membersByFamily.getOrDefault(familyRootId, List.of());
			float[] embedding = refreshedEmbeddings.getOrDefault(onSale.getId(), storedEmbeddings.get(onSale.getId()));
			toUpsert.add(familyStatsResolver.buildFamilyUpsertInput(
				members, onSale, averageRatings.getOrDefault(familyRootId, 0.0), embedding));
		}

		return new Reconciliation(toUpsert, toDelete);
	}

	private record Reconciliation(List<FamilyUpsertInput> toUpsert, List<UUID> toDelete) {
	}
}
