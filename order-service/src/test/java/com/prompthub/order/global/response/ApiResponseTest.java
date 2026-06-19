package com.prompthub.order.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    @DisplayName("데이터가 있는 성공 응답은 문서화된 형태를 반환한다")
    void successWithDataReturnsDocumentedShape() {
        ApiResponse<String> response = ApiResponse.success("order");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo("order");
        assertThat(response.getMessage()).isEqualTo("success");
    }

    @Test
    @DisplayName("데이터가 없는 성공 응답은 null 데이터를 반환한다")
    void successWithoutDataReturnsNullData() {
        ApiResponse<Void> response = ApiResponse.success();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isNull();
        assertThat(response.getMessage()).isEqualTo("success");
    }
}
