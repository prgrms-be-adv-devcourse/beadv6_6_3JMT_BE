package com.prompthub.product.domain.model.enums;

import static org.assertj.core.api.Assertions.assertThat;

import com.prompthub.product.domain.model.vo.ProductDeliverable;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ProductTypeTest {

	@ParameterizedTest
	@MethodSource("deliverables")
	void resolvesDeliverable(
		ProductType productType,
		String content,
		String fileObjectKey,
		String externalUrl,
		ProductDeliverable.Type expectedType,
		String expectedValue
	) {
		ProductDeliverable deliverable = productType.resolveDeliverable(content, fileObjectKey, externalUrl);

		assertThat(deliverable.type()).isEqualTo(expectedType);
		assertThat(deliverable.value()).isEqualTo(expectedValue);
	}

	private static Stream<Arguments> deliverables() {
		return Stream.of(
			Arguments.of(ProductType.PROMPT, "본문", null, null,
				ProductDeliverable.Type.INLINE_CONTENT, "본문"),
			Arguments.of(ProductType.PPT, null, "files/deck.pptx", null,
				ProductDeliverable.Type.FILE_OBJECT_KEY, "files/deck.pptx"),
			Arguments.of(ProductType.EXCEL, null, "files/sheet.xlsx", null,
				ProductDeliverable.Type.FILE_OBJECT_KEY, "files/sheet.xlsx"),
			Arguments.of(ProductType.NOTION, null, null, "https://notion.so/page",
				ProductDeliverable.Type.EXTERNAL_URL, "https://notion.so/page")
		);
	}
}
