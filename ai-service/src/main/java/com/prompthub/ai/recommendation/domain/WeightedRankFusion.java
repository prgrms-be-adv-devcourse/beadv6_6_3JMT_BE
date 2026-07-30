package com.prompthub.ai.recommendation.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 여러 기준 상품에서 나온 추천 순위를 <b>가중치를 실어</b> 하나로 합친다.
 *
 * <p><b>왜 평균이 아닌가</b> — 여러 상품의 좌표를 평균 내면 그 중간 지점에 착지하는데, 이
 * 카탈로그는 임베딩 공간이 좁게 뭉쳐 있어(실측: 중심성 상위 15건이 0.568~0.610 구간) 그 중간이
 * 곧 <b>모든 것과 어중간하게 가까운 구역</b>이다. 평균은 취향을 잡는 게 아니라 지운다.
 * 순위 합산은 각 기준의 실제 위치에서 뽑은 등수만 쓰므로 중간 지점을 만들지 않는다.
 *
 * <p><b>부수 효과</b> — 여러 목록에서 공통으로 상위인 상품이 올라간다. 한 목록에서만 우연히
 * 상위인 상품(아무것과도 어중간하게 가까운 것)은 밀린다.
 *
 * <p>점수는 {@code Σ ( 가중치 × 1 / (K + 등수) )}다. 점수를 버리고 등수만 쓰기 때문에 목록마다
 * 유사도 스케일이 달라도 보정 상수를 잡을 필요가 없다.
 */
public final class WeightedRankFusion {

    /**
     * RRF 논문(Cormack et al., 2009)이 제시한 값. 등수 차이의 영향을 완만하게 만든다 —
     * K가 작으면 1·2등 차이가 과도하게 벌어지고, 크면 순위 정보가 뭉개진다.
     */
    private static final int K = 60;

    private WeightedRankFusion() {
    }

    /** 가중치를 실은 순위 목록 하나. {@code ranked}는 상위부터 정렬돼 있어야 한다. */
    public record WeightedRanking(double weight, List<UUID> ranked) {
    }

    /**
     * @return 모든 목록의 합집합을 합산 점수 내림차순으로 정렬한 결과. 중복은 없다
     */
    public static List<UUID> fuse(List<WeightedRanking> rankings) {
        Map<UUID, Double> scores = new HashMap<>();
        Set<UUID> candidates = new LinkedHashSet<>();

        for (WeightedRanking ranking : rankings) {
            List<UUID> ranked = ranking.ranked();
            for (int i = 0; i < ranked.size(); i++) {
                int rank = i + 1;
                scores.merge(ranked.get(i), ranking.weight() / (K + rank), Double::sum);
            }
            candidates.addAll(ranked);
        }

        List<UUID> fused = new ArrayList<>(candidates);
        fused.sort(Comparator
                .comparingDouble((UUID id) -> scores.get(id)).reversed()
                // 동점이면 id로 순서를 고정한다. 같은 입력이면 같은 결과가 나와야 화면이 흔들리지 않는다.
                .thenComparing(UUID::toString));
        return fused;
    }
}
