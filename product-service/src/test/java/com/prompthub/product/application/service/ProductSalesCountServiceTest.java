package com.prompthub.product.application.service;

import com.prompthub.product.domain.model.entity.Product;
import com.prompthub.product.domain.model.enums.ProductStatus;
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

	private static final UUID PRODUCT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	@Mock
	private ProductRepository productRepository;

	@InjectMocks
	private ProductSalesCountService productSalesCountService;

	@Nested
	@DisplayName("판매 수량 증가")
	class IncrementSalesCount {

		@Test
		@DisplayName("주문에 담긴 그 버전(row) 자신의 salesCount를 1 증가한다")
		void increment_incrementsAnchorItself() {
			Product product = product(PRODUCT_ID, ProductStatus.ON_SALE, 5);
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));

			productSalesCountService.incrementSalesCount(List.of(PRODUCT_ID));

			assertThat(product.getSalesCount()).isEqualTo(6);
			then(productRepository).should().save(product);
		}

		@Test
		@DisplayName("결제 이벤트 처리 시점에 이미 판매중단된 버전이어도 유실 없이 그 row에 증가한다")
		void increment_stoppedVersion_stillIncrementsWithoutLoss() {
			Product stopped = product(PRODUCT_ID, ProductStatus.STOPPED, 5);
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(stopped));

			productSalesCountService.incrementSalesCount(List.of(PRODUCT_ID));

			assertThat(stopped.getSalesCount()).isEqualTo(6);
			then(productRepository).should().save(stopped);
		}
	}

	@Nested
	@DisplayName("판매 수량 감소")
	class DecrementSalesCount {

		@Test
		@DisplayName("주문에 담긴 그 버전(row) 자신의 salesCount를 1 감소한다")
		void decrement_decrementsAnchorItself() {
			Product product = product(PRODUCT_ID, ProductStatus.ON_SALE, 3);
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));

			productSalesCountService.decrementSalesCount(List.of(PRODUCT_ID));

			assertThat(product.getSalesCount()).isEqualTo(2);
			then(productRepository).should().save(product);
		}

		@Test
		@DisplayName("salesCount가 이미 0이면 더 내려가지 않는다")
		void decrement_floorsAtZero() {
			Product product = product(PRODUCT_ID, ProductStatus.ON_SALE, 0);
			given(productRepository.findAllByIdIn(List.of(PRODUCT_ID))).willReturn(List.of(product));

			productSalesCountService.decrementSalesCount(List.of(PRODUCT_ID));

			assertThat(product.getSalesCount()).isEqualTo(0);
			then(productRepository).should().save(product);
		}
	}

	private Product product(UUID id, ProductStatus status, int salesCount) {
		Product product = instantiate(Product.class);
		ReflectionTestUtils.setField(product, "id", id);
		ReflectionTestUtils.setField(product, "status", status);
		ReflectionTestUtils.setField(product, "salesCount", salesCount);
		ReflectionTestUtils.setField(product, "majorVersion", (short) 1);
		ReflectionTestUtils.setField(product, "patchVersion", (short) 0);
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
