package com.prompthub.recommendation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.prompthub.product.domain.model.projection.SimilarProductProjection;
import com.prompthub.product.domain.repository.ProductRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductRecommenderTest {

	private final UUID baseId = UUID.randomUUID();
	private final UUID familyRootId = UUID.randomUUID();

	@Mock
	private ProductRepository productRepository;

	private ProductRecommender recommender;

	@BeforeEach
	void setUp() {
		recommender = new ProductRecommender(productRepository);
	}

	@Test
	@DisplayName("유사도가 같으면 같은 유형이 앞선다")
	void sameTypeWinsOnTie() {
		UUID sameType = UUID.randomUUID();
		UUID otherType = UUID.randomUUID();
		givenCandidates(
			new SimilarProductProjection(otherType, "NOTION", 0.20),
			new SimilarProductProjection(sameType, "PROMPT", 0.20));

		assertThat(recommend("PROMPT", 2)).containsExactly(sameType, otherType);
	}

	@Test
	@DisplayName("유사도 차이가 가산점보다 크면 다른 유형이 앞선다 — 하드 필터가 아니다")
	void similarityCanBeatTypeBonus() {
		// 이 검증이 이 클래스의 존재 이유다. 유형으로 거르거나 1차 정렬 키로 두면 FE가
		// limit=4로 요청하므로 같은 유형 후보가 4개만 있어도 타 유형이 절대 노출되지 않는다.
		UUID muchCloserOtherType = UUID.randomUUID();
		UUID sameType = UUID.randomUUID();
		givenCandidates(
			new SimilarProductProjection(sameType, "PROMPT", 0.30),
			new SimilarProductProjection(muchCloserOtherType, "NOTION", 0.10));

		assertThat(recommend("PROMPT", 2)).containsExactly(muchCloserOtherType, sameType);
	}

	@Test
	@DisplayName("유사도 차이가 가산점보다 작으면 같은 유형이 앞선다")
	void typeBonusWinsOnNarrowGap() {
		UUID slightlyCloserOtherType = UUID.randomUUID();
		UUID sameType = UUID.randomUUID();
		givenCandidates(
			new SimilarProductProjection(sameType, "PROMPT", 0.20),
			new SimilarProductProjection(slightlyCloserOtherType, "NOTION", 0.18));

		assertThat(recommend("PROMPT", 2)).containsExactly(sameType, slightlyCloserOtherType);
	}

	@Test
	@DisplayName("요청한 개수까지만 돌려준다")
	void respectsLimit() {
		givenCandidates(
			new SimilarProductProjection(UUID.randomUUID(), "PROMPT", 0.10),
			new SimilarProductProjection(UUID.randomUUID(), "PROMPT", 0.20),
			new SimilarProductProjection(UUID.randomUUID(), "PROMPT", 0.30));

		assertThat(recommend("PROMPT", 2)).hasSize(2);
	}

	@Test
	@DisplayName("후보가 요청보다 적으면 있는 만큼만 돌려준다")
	void returnsFewerWhenCandidatesAreScarce() {
		givenCandidates(new SimilarProductProjection(UUID.randomUUID(), "PROMPT", 0.10));

		assertThat(recommend("PROMPT", 4)).hasSize(1);
	}

	@Test
	@DisplayName("후보가 없으면 빈 결과를 준다")
	void emptyWhenNoCandidates() {
		givenCandidates();

		assertThat(recommend("PROMPT", 4)).isEmpty();
	}

	@Test
	@DisplayName("요청 개수보다 넉넉히 후보를 가져온다 — 가산점이 순서를 뒤집을 여지를 준다")
	void fetchesMoreCandidatesThanRequested() {
		givenCandidates();

		recommender.recommend(baseId, familyRootId, "PROMPT", 4);

		// limit만큼만 가져오면 같은 유형이 그 자리를 다 채웠을 때 타 유형이 후보에도 못 든다.
		org.mockito.BDDMockito.then(productRepository).should()
			.findSimilarProducts(baseId, familyRootId, 4 * ProductRecommender.CANDIDATE_MULTIPLIER);
	}

	private void givenCandidates(SimilarProductProjection... candidates) {
		given(productRepository.findSimilarProducts(
			org.mockito.ArgumentMatchers.eq(baseId),
			org.mockito.ArgumentMatchers.eq(familyRootId),
			org.mockito.ArgumentMatchers.anyInt()))
			.willReturn(List.of(candidates));
	}

	private List<UUID> recommend(String baseType, int limit) {
		return recommender.recommend(baseId, familyRootId, baseType, limit);
	}
}
