package com.prompthub.ai.settlement.infrastructure.client.openai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("판매자 정산 prompt harness")
class SettlementPromptFactoryTest {

    @Test
    @DisplayName("판매자 전용·서버 계산값·후속 질문 금지와 서울 기준일을 고정한다")
    void buildsSellerOnlyPrompt() {
        SettlementPromptFactory factory = new SettlementPromptFactory(Clock.fixed(
                Instant.parse("2026-07-23T00:30:00Z"),
                ZoneId.of("Asia/Seoul")));

        String prompt = factory.systemPrompt();

        assertThat(prompt)
                .contains(
                        "판매자 전용",
                        "네 개의 읽기 전용 정산 도구",
                        "대한민국 원화",
                        "직접 계산하거나 추정하지 않는다",
                        "후속 질문",
                        "물음표")
                .doesNotContain("admin", "role", "x-user-id", "x-internal-service-token");
        assertThat(factory.currentDatePrompt())
                .contains("2026-07-23", "Asia/Seoul");
    }
}
