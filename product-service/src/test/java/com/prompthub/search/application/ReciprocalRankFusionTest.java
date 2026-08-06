package com.prompthub.search.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {

	private final UUID a = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private final UUID b = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
	private final UUID c = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
	private final UUID d = UUID.fromString("00000000-0000-0000-0000-0000000000d4");

	@Test
	@DisplayName("두 목록 모두 상위인 문서가 한쪽에만 1등인 문서보다 앞선다")
	void bothListsBeatSingleListTop() {
		// b는 양쪽 2등(1/62 + 1/62), a는 lexical 1등이지만 semantic에 없다(1/61).
		List<UUID> fused = ReciprocalRankFusion.fuse(List.of(a, b), List.of(c, b));

		assertThat(fused).startsWith(b);
	}

	@Test
	@DisplayName("한쪽에만 있는 문서도 결과에 포함된다")
	void keepsDocumentsFoundInOneListOnly() {
		List<UUID> fused = ReciprocalRankFusion.fuse(List.of(a, b), List.of(c, d));

		assertThat(fused).containsExactlyInAnyOrder(a, b, c, d);
	}

	@Test
	@DisplayName("한쪽이 비어 있으면 나머지 목록의 순서를 그대로 유지한다")
	void preservesOrderWhenOneListIsEmpty() {
		assertThat(ReciprocalRankFusion.fuse(List.of(a, b, c), List.of())).containsExactly(a, b, c);
		assertThat(ReciprocalRankFusion.fuse(List.of(), List.of(c, b, a))).containsExactly(c, b, a);
	}

	@Test
	@DisplayName("두 목록이 같으면 그 순서를 그대로 유지한다")
	void preservesOrderWhenListsAreIdentical() {
		assertThat(ReciprocalRankFusion.fuse(List.of(a, b, c), List.of(a, b, c))).containsExactly(a, b, c);
	}

	@Test
	@DisplayName("점수가 같으면 lexical 순위가 앞선 쪽을 먼저 둔다")
	void breaksTieByLexicalRank() {
		// a는 lexical 1등·semantic 2등, b는 lexical 2등·semantic 1등 — 점수가 같다.
		List<UUID> fused = ReciprocalRankFusion.fuse(List.of(a, b), List.of(b, a));

		assertThat(fused).containsExactly(a, b);
	}

	@Test
	@DisplayName("중복 없이 문서당 한 번만 나온다")
	void containsNoDuplicates() {
		List<UUID> fused = ReciprocalRankFusion.fuse(List.of(a, b, c), List.of(c, a, b));

		assertThat(fused).hasSize(3).doesNotHaveDuplicates();
	}

	@Test
	@DisplayName("둘 다 비어 있으면 빈 결과를 준다")
	void emptyWhenBothEmpty() {
		assertThat(ReciprocalRankFusion.fuse(List.of(), List.of())).isEmpty();
	}
}
