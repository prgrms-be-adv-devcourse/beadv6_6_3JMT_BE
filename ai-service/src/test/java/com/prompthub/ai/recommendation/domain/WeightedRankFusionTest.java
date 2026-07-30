package com.prompthub.ai.recommendation.domain;

import com.prompthub.ai.recommendation.domain.WeightedRankFusion.WeightedRanking;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeightedRankFusionTest {

    private static final UUID X = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID Y = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID Z = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    @DisplayName("여러 목록에 공통으로 있는 상품이 한 목록에서만 1등인 상품을 앞선다")
    void commonlyRankedProductBeatsSingleListWinner() {
        // Y는 첫 목록 2등이지만 두 목록 모두에 있고, X는 첫 목록 1등이지만 한 목록에만 있다.
        // "두 기준이 모두 관련 있다고 본 상품"을 위로 올리는 것이 이 방식의 목적이다.
        List<UUID> fused = WeightedRankFusion.fuse(List.of(
                new WeightedRanking(1.0, List.of(X, Y)),
                new WeightedRanking(0.7, List.of(Y, Z))));

        assertThat(fused).containsExactly(Y, X, Z);
    }

    @Test
    @DisplayName("같은 등수면 가중치가 높은 목록의 상품이 앞선다")
    void higherWeightWinsAtSameRank() {
        List<UUID> fused = WeightedRankFusion.fuse(List.of(
                new WeightedRanking(0.7, List.of(Y)),
                new WeightedRanking(1.0, List.of(X))));

        assertThat(fused).containsExactly(X, Y);
    }

    @Test
    @DisplayName("같은 상품이 여러 목록에 있어도 결과에는 한 번만 나온다")
    void deduplicatesAcrossLists() {
        List<UUID> fused = WeightedRankFusion.fuse(List.of(
                new WeightedRanking(1.0, List.of(X, Y)),
                new WeightedRanking(1.0, List.of(Y, X))));

        assertThat(fused).containsExactlyInAnyOrder(X, Y);
    }

    @Test
    @DisplayName("입력이 없거나 목록이 비어 있으면 빈 결과다")
    void emptyInputProducesEmptyResult() {
        assertThat(WeightedRankFusion.fuse(List.of())).isEmpty();
        assertThat(WeightedRankFusion.fuse(List.of(new WeightedRanking(1.0, List.of())))).isEmpty();
    }

    @Test
    @DisplayName("점수가 같으면 항상 같은 순서를 낸다 — 화면이 새로고침마다 흔들리지 않아야 한다")
    void tieBreakIsStable() {
        List<WeightedRanking> rankings = List.of(
                new WeightedRanking(1.0, List.of(Z)),
                new WeightedRanking(1.0, List.of(X)),
                new WeightedRanking(1.0, List.of(Y)));

        assertThat(WeightedRankFusion.fuse(rankings))
                .isEqualTo(WeightedRankFusion.fuse(rankings))
                .containsExactly(X, Y, Z);
    }
}
