package com.prompthub.order.presentation.dto.request;

import com.prompthub.order.application.dto.CreateOrderCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

@Schema(description = "주문 생성 요청")
public record CreateOrderRequest(
	@Schema(description = "주문할 상품 목록", requiredMode = Schema.RequiredMode.REQUIRED)
	@NotEmpty(message = "주문 상품 목록은 비어 있을 수 없습니다.")
	List<@NotNull(message = "주문 상품은 null일 수 없습니다.") @Valid Product> products
) {

	public CreateOrderCommand toCommand() {
		return new CreateOrderCommand(products.stream()
			.map(product -> new CreateOrderCommand.Product(product.productId(), product.productTitle()))
			.toList());
	}

	public record Product(
		@Schema(description = "상품 ID", example = "00000000-0000-0000-0000-000000000201")
		@NotNull(message = "상품 ID는 null일 수 없습니다.")
		UUID productId,
		@Schema(description = "주문 시점 상품 제목", example = "면접 준비 프롬프트")
		@NotBlank(message = "상품 제목은 비어 있을 수 없습니다.")
		@Size(max = 200, message = "상품 제목은 200자를 초과할 수 없습니다.")
		String productTitle
	) {
	}
}
