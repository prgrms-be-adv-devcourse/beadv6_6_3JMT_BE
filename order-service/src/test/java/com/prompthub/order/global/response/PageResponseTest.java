package com.prompthub.order.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PageResponseTest {

    @Test
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
