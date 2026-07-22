package com.prompthub.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementApplicationTest {

    @Test
    @DisplayName("cronjob과 restart 실행 모드만 one-shot 프로세스로 종료한다")
    void isOneShotMode_onlyCronjobAndRestart() {
        assertThat(SettlementApplication.isOneShotMode("cronjob")).isTrue();
        assertThat(SettlementApplication.isOneShotMode("CRONJOB")).isTrue();
        assertThat(SettlementApplication.isOneShotMode("restart")).isTrue();
        assertThat(SettlementApplication.isOneShotMode("RESTART")).isTrue();
        assertThat(SettlementApplication.isOneShotMode("service")).isFalse();
        assertThat(SettlementApplication.isOneShotMode(null)).isFalse();
    }
}
