package com.prompthub.order.infra.redis;

import com.prompthub.order.application.client.ProductClient;
import com.prompthub.order.application.service.order.OrderCommandHandler;
import com.prompthub.order.application.service.order.OrderExpirationStore;
import com.prompthub.order.application.service.order.OrderNumberGenerator;
import com.prompthub.order.application.service.order.OrderProductIdempotencyStore;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.infra.persistence.order.OrderPersistence;
import com.prompthub.order.infra.persistence.outbox.OutboxEventPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.prompthub.order.fixture.OrderV2Fixture.BUYER_ID;
import static com.prompthub.order.fixture.OrderV2Fixture.command;
import static com.prompthub.order.fixture.OrderV2Fixture.requestedProductIds;
import static com.prompthub.order.fixture.OrderV2Fixture.shuffledSnapshots;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;

@SpringBootTest(properties = "prompthub.order.payment-timeout-minutes=20")
@ActiveProfiles("test")
class OrderExpirationAfterCommitIntegrationTest {

	@Autowired
	private OrderCommandHandler orderCommandHandler;

	@Autowired
	private OrderPersistence orderPersistence;

	@Autowired
	private OutboxEventPersistence outboxEventPersistence;

	@MockitoBean
	private ProductClient productClient;

	@MockitoBean
	private OrderNumberGenerator orderNumberGenerator;

	@MockitoBean
	private OrderExpirationStore orderExpirationStore;

	@MockitoBean
	private OrderProductIdempotencyStore orderProductIdempotencyStore;

	@BeforeEach
	void setUp() {
		given(productClient.getOrderSnapshots(requestedProductIds())).willReturn(shuffledSnapshots());
		given(orderNumberGenerator.generate()).willReturn("ORD-A");
		given(orderProductIdempotencyStore.acquire(
			any(UUID.class), anyCollection(), any(UUID.class), any(Duration.class)
		)).willReturn(true);
	}

	@AfterEach
	void tearDown() {
		outboxEventPersistence.deleteAll();
		orderPersistence.deleteAll();
		reset(productClient, orderNumberGenerator, orderExpirationStore, orderProductIdempotencyStore);
	}

	@Test
	@DisplayName("commit 이후 생성된 단일 주문을 만료 대상으로 한 번 등록한다")
	void commitRegistersCreatedOrder() {
		orderCommandHandler.createOrder(BUYER_ID, command());

		ArgumentCaptor<UUID> orderIdCaptor = ArgumentCaptor.forClass(UUID.class);
		ArgumentCaptor<LocalDateTime> createdAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
		then(orderExpirationStore).should(times(1))
			.registerExpiration(orderIdCaptor.capture(), createdAtCaptor.capture(),
				org.mockito.ArgumentMatchers.eq(20));

		assertThat(orderIdCaptor.getAllValues()).hasSize(1).doesNotContainNull();
		assertThat(createdAtCaptor.getAllValues()).hasSize(1).doesNotContainNull();
		assertThat(orderPersistence.count()).isEqualTo(1);
		assertThat(outboxEventPersistence.count()).isZero();
	}

	@Test
	@DisplayName("주문 트랜잭션 커밋이 실패하면 주문 만료 등록을 수행하지 않는다")
	void rollbackDoesNotRegisterExpirations() {
		Order existing = Order.create(BUYER_ID, "ORD-A", 1_000);
		orderPersistence.saveAndFlush(existing);

		assertThatThrownBy(() -> orderCommandHandler.createOrder(BUYER_ID, command()))
			.isInstanceOfAny(DataIntegrityViolationException.class, RuntimeException.class);

		then(orderExpirationStore).shouldHaveNoInteractions();
		assertThat(orderPersistence.count()).isEqualTo(1);
		assertThat(outboxEventPersistence.count()).isZero();
	}
}
