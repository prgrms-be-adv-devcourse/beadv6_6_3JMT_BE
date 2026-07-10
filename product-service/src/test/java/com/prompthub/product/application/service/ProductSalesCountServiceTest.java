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

	private static final UUID ROOT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID CHILD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	@Mock
	private ProductRepository productRepository;

	@InjectMocks
	private ProductSalesCountService productSalesCountService;

	@Nested
	@DisplayName("판매 수량 증가")
	class IncrementSalesCount {

		@Test
		@DisplayName("단일 상품(자기 자신이 family)의 현재 ON_SALE에 1 증가한다")
		void increment_singleProduct() {
			Product onSale = product(ROOT_ID, null, ProductStatus.ON_SALE, 5);
			given(productRepository.findAllByIdIn(List.of(ROOT_ID))).willReturn(List.of(onSale));
			given(productRepository.findAllByFamilyRootIds(List.of(ROOT_ID))).willReturn(List.of(onSale));

			productSalesCountService.incrementSalesCount(List.of(ROOT_ID));

			assertThat(onSale.getSalesCount()).isEqualTo(6);
			then(productRepository).should().save(onSale);
		}

		@Test
		@DisplayName("옛 버전 id로 와도 family의 현재 ON_SALE row에 증가한다")
		void increment_versionChain_appliesToCurrentOnSale() {
			Product old = product(ROOT_ID, null, ProductStatus.SUPERSEDED, 5);
			Product current = product(CHILD_ID, ROOT_ID, ProductStatus.ON_SALE, 0);
			given(productRepository.findAllByIdIn(List.of(ROOT_ID))).willReturn(List.of(old));
			given(productRepository.findAllByFamilyRootIds(List.of(ROOT_ID))).willReturn(List.of(old, current));

			productSalesCountService.incrementSalesCount(List.of(ROOT_ID));

			assertThat(current.getSalesCount()).isEqualTo(1);
			assertThat(old.getSalesCount()).isEqualTo(5);
			then(productRepository).should().save(current);
		}
	}

	@Nested
	@DisplayName("판매 수량 감소")
	class DecrementSalesCount {

		@Test
		@DisplayName("현재 ON_SALE에 판매수가 있으면 그 row를 1 감소한다")
		void decrement_currentOnSaleHasCount() {
			Product current = product(CHILD_ID, ROOT_ID, ProductStatus.ON_SALE, 3);
			given(productRepository.findAllByIdIn(List.of(CHILD_ID))).willReturn(List.of(current));
			given(productRepository.findAllByFamilyRootIds(List.of(ROOT_ID))).willReturn(List.of(current));

			productSalesCountService.decrementSalesCount(List.of(CHILD_ID));

			assertThat(current.getSalesCount()).isEqualTo(2);
			then(productRepository).should().save(current);
		}

		@Test
		@DisplayName("현재 ON_SALE 판매수가 0이면 family 내 판매수>0 버전을 감소시켜 family 합이 정확히 줄어든다")
		void decrement_floorEdge_reducesPositiveFamilyMember() {
			Product old = product(ROOT_ID, null, ProductStatus.SUPERSEDED, 5);
			Product current = product(CHILD_ID, ROOT_ID, ProductStatus.ON_SALE, 0);
			given(productRepository.findAllByIdIn(List.of(CHILD_ID))).willReturn(List.of(current));
			given(productRepository.findAllByFamilyRootIds(List.of(ROOT_ID))).willReturn(List.of(old, current));

			productSalesCountService.decrementSalesCount(List.of(CHILD_ID));

			assertThat(old.getSalesCount()).isEqualTo(4);
			assertThat(current.getSalesCount()).isEqualTo(0);
			then(productRepository).should().save(old);
		}
	}

	private Product product(UUID id, UUID parentId, ProductStatus status, int salesCount) {
		Product product = instantiate(Product.class);
		ReflectionTestUtils.setField(product, "id", id);
		ReflectionTestUtils.setField(product, "parentId", parentId);
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
