package com.prompthub.order.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PageResponseTest {

    @Test
    @DisplayName("성공 응답은 목록 데이터와 메타 정보를 반환한다")
    void successReturnsListDataAndMeta() {
        PageResponse<String> response = PageResponse.success(List.of("order-1"), 0, 20, 1L, false);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).containsExactly("order-1");
        assertThat(response.message()).isEqualTo("success");
        assertThat(response.meta().page()).isZero();
        assertThat(response.meta().size()).isEqualTo(20);
        assertThat(response.meta().total()).isEqualTo(1L);
        assertThat(response.meta().hasNext()).isFalse();
    }
}
