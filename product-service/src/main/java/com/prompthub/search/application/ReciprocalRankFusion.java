package com.prompthub.search.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 두 검색 결과의 <b>순위</b>를 섞는다 (Reciprocal Rank Fusion).
 *
 * <p>글자 기반(BM25) 점수는 상한이 없고 코사인 유사도는 0~1이라 그대로 더하면 스케일이
 * 맞지 않는다. 데이터가 바뀔 때마다 보정 상수를 다시 잡아야 해서 유지되지 않는다.
 * RRF는 점수를 버리고 <b>등수만</b> 쓰기 때문에 그 문제가 없다.
 *
 * <p>문서 하나의 점수는 각 목록에서의 등수로부터 {@code 1 / (K + 등수)}를 더한 값이다.
 * 한쪽에서 1등이어도 다른 쪽에 없으면, 양쪽에서 고르게 상위인 문서에 밀린다 —
 * "두 방식이 모두 관련 있다고 본 문서"를 위로 올리는 것이 이 방식의 목적이다.
 */
public final class ReciprocalRankFusion {

	/**
	 * RRF 논문(Cormack et al., 2009)이 제시한 값. 등수 차이의 영향을 완만하게 만든다 —
	 * K가 작으면 1·2등 차이가 과도하게 벌어지고, 크면 순위 정보가 뭉개진다.
	 */
	private static final int K = 60;

	private ReciprocalRankFusion() {
	}

	/**
	 * @param lexical 글자 기반 검색 결과 (앞이 상위)
	 * @param semantic 의미 기반 검색 결과 (앞이 상위)
	 * @return 두 목록의 합집합을 병합 순위로 정렬한 결과. 중복은 없다
	 */
	public static List<UUID> fuse(List<UUID> lexical, List<UUID> semantic) {
		Map<UUID, Double> scores = new HashMap<>();
		accumulate(scores, lexical);
		accumulate(scores, semantic);

		Map<UUID, Integer> lexicalRanks = ranksOf(lexical);

		Set<UUID> candidates = new LinkedHashSet<>(lexical);
		candidates.addAll(semantic);

		List<UUID> fused = new ArrayList<>(candidates);
		fused.sort(Comparator
			.comparingDouble((UUID id) -> scores.get(id)).reversed()
			// 동점이면 글자 기반 순위를 앞세운다. 사용자가 친 단어가 실제로 들어 있는 문서를
			// 우선하는 편이 덜 놀랍다. 양쪽 다 없으면 id로 순서를 고정해 결과를 재현 가능하게 둔다.
			.thenComparingInt(id -> lexicalRanks.getOrDefault(id, Integer.MAX_VALUE))
			.thenComparing(UUID::toString));
		return fused;
	}

	private static void accumulate(Map<UUID, Double> scores, List<UUID> ranked) {
		for (int i = 0; i < ranked.size(); i++) {
			int rank = i + 1;
			scores.merge(ranked.get(i), 1.0 / (K + rank), Double::sum);
		}
	}

	private static Map<UUID, Integer> ranksOf(List<UUID> ranked) {
		Map<UUID, Integer> ranks = new HashMap<>();
		for (int i = 0; i < ranked.size(); i++) {
			ranks.putIfAbsent(ranked.get(i), i + 1);
		}
		return ranks;
	}
}
