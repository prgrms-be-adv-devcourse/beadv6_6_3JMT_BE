package com.prompthub.order.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiResponseTest {

    @Test
    void successWithDataReturnsDocumentedShape() {
        ApiResponse<String> response = ApiResponse.success("order");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo("order");
        assertThat(response.getMessage()).isEqualTo("success");
    }

    @Test
    void successWithoutDataReturnsNullData() {
        ApiResponse<Void> response = ApiResponse.success();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isNull();
        assertThat(response.getMessage()).isEqualTo("success");
    }
}
