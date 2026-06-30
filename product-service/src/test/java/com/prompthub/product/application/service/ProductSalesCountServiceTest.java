package com.prompthub.product.application.service;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.repository.ProductRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class ProductSalesCountServiceTest {

	private static final UUID PRODUCT_ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID PRODUCT_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Mock
	private ProductRepository productRepository;

	@InjectMocks
	private ProductSalesCountService productSalesCountService;

	@Nested
	@DisplayName("판매 수량 증가")
	class IncrementSalesCount {

		@Test
		@DisplayName("ORDER_PAID 이벤트로 상품 salesCount를 1 증가시킨다")
		void incrementSalesCount_success() {
			Product product = product(PRODUCT_ID_1, 5);
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID_1))).willReturn(List.of(product));

			productSalesCountService.incrementSalesCount(List.of(PRODUCT_ID_1));

			assertThat(product.getSalesCount()).isEqualTo(6);
			then(productRepository).should().save(product);
		}

		@Test
		@DisplayName("여러 상품의 salesCount를 각각 증가시킨다")
		void incrementSalesCount_multipleProducts() {
			Product product1 = product(PRODUCT_ID_1, 3);
			Product product2 = product(PRODUCT_ID_2, 7);
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID_1, PRODUCT_ID_2)))
				.willReturn(List.of(product1, product2));

			productSalesCountService.incrementSalesCount(List.of(PRODUCT_ID_1, PRODUCT_ID_2));

			assertThat(product1.getSalesCount()).isEqualTo(4);
			assertThat(product2.getSalesCount()).isEqualTo(8);
			then(productRepository).should().save(product1);
			then(productRepository).should().save(product2);
		}
	}

	@Nested
	@DisplayName("판매 수량 감소")
	class DecrementSalesCount {

		@Test
		@DisplayName("ORDER_REFUND 이벤트로 상품 salesCount를 1 감소시킨다")
		void decrementSalesCount_success() {
			Product product = product(PRODUCT_ID_1, 5);
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID_1))).willReturn(List.of(product));

			productSalesCountService.decrementSalesCount(List.of(PRODUCT_ID_1));

			assertThat(product.getSalesCount()).isEqualTo(4);
			then(productRepository).should().save(product);
		}

		@Test
		@DisplayName("salesCount가 0이면 감소시키지 않는다")
		void decrementSalesCount_doesNotGoBelowZero() {
			Product product = product(PRODUCT_ID_1, 0);
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID_1))).willReturn(List.of(product));

			productSalesCountService.decrementSalesCount(List.of(PRODUCT_ID_1));

			assertThat(product.getSalesCount()).isEqualTo(0);
			then(productRepository).should().save(product);
		}
	}

	private Product product(UUID productId, int salesCount) {
		Product product = instantiate(Product.class);
		ReflectionTestUtils.setField(product, "id", productId);
		ReflectionTestUtils.setField(product, "salesCount", salesCount);
		ReflectionTestUtils.setField(product, "updatedAt", LocalDateTime.now());
		return product;
	}

	private <T> T instantiate(Class<T> type) {
		try {
			java.lang.reflect.Constructor<T> constructor = type.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("테스트 fixture 생성에 실패했습니다.", exception);
		}
	}
}
