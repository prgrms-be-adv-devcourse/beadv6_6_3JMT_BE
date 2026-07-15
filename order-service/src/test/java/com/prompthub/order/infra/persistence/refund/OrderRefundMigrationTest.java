package com.prompthub.order.infra.persistence.refund;

import com.prompthub.order.config.TestJpaConfig;
import com.prompthub.order.domain.model.OrderRefund;
import com.prompthub.order.infra.persistence.config.QuerydslConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
	"spring.flyway.enabled=true",
	"spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({QuerydslConfig.class, TestJpaConfig.class})
@Testcontainers(disabledWithoutDocker = true)
class OrderRefundMigrationTest {

	@Container
	private static final PostgreSQLContainer<?> POSTGRES =
		new PostgreSQLContainer<>("postgres:18.4-alpine");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OrderRefundPersistence orderRefundPersistence;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
	}

	@Test
	@DisplayName("V2는 환불 테이블과 낙관적 락 버전 컬럼을 생성한다")
	void migrate_createsRefundTablesAndVersionColumns() {
		Integer refundTableCount = jdbcTemplate.queryForObject("""
			select count(*)
			from information_schema.tables
			where table_schema = current_schema()
			  and table_name in ('order_refund', 'order_refund_product')
			""", Integer.class);
		Integer versionColumnCount = jdbcTemplate.queryForObject("""
			select count(*)
			from information_schema.columns
			where table_schema = current_schema()
			  and column_name = 'version'
			  and table_name in ('order', 'order_product', 'order_refund')
			  and is_nullable = 'NO'
			""", Integer.class);

		assertThat(refundTableCount).isEqualTo(2);
		assertThat(versionColumnCount).isEqualTo(3);
	}

	@Test
	@DisplayName("V2는 환불 상태와 단건 상세 및 참조 무결성 제약을 생성한다")
	void migrate_createsRefundConstraints() {
		Integer constraintCount = jdbcTemplate.queryForObject("""
			select count(*)
			from information_schema.table_constraints
			where table_schema = current_schema()
			  and constraint_name in (
			    'order_refund_status_check',
			    'ck_order_refund_positive_values',
			    'ck_order_refund_product_positive_amount',
			    'uk_order_refund_id_amount',
			    'uk_order_refund_product_refund',
			    'uk_order_refund_product_order_product',
			    'fk_order_refund_order',
			    'fk_order_refund_payment',
			    'fk_order_refund_product_refund',
			    'fk_order_refund_product_order_product'
			  )
			""", Integer.class);
		Integer indexCount = jdbcTemplate.queryForObject("""
			select count(*)
			from pg_indexes
			where schemaname = current_schema()
			  and indexname in (
			    'idx_order_refund_status_next_check',
			    'idx_order_refund_order_status',
			    'idx_order_refund_payment',
			    'uk_order_refund_product_order_product'
			  )
			""", Integer.class);

		assertThat(constraintCount).isEqualTo(10);
		assertThat(indexCount).isEqualTo(4);
	}

	@Test
	@DisplayName("상세 환불 금액은 환불 요청 총액과 달리 저장할 수 없다")
	void insert_productAmountDifferentFromTotal_throwsException() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 15, 12, 0);
		UUID orderId = UUID.randomUUID();
		UUID paymentId = UUID.randomUUID();
		UUID buyerId = UUID.randomUUID();
		UUID orderProductId = UUID.randomUUID();
		insertPaidOrder(orderId, paymentId, buyerId, orderProductId, UUID.randomUUID(), now);
		UUID refundId = UUID.randomUUID();
		jdbcTemplate.update("""
			insert into order_refund (
			  id, version, order_id, payment_id, buyer_id, status,
			  total_refund_amount, check_count, requested_at
			) values (?, 0, ?, ?, ?, 'REQUESTED', 10000, 0, ?)
			""", refundId, orderId, paymentId, buyerId, now);

		assertThatThrownBy(() -> jdbcTemplate.update("""
			insert into order_refund_product (
			  id, order_refund_id, order_product_id, refund_amount, created_at
			) values (?, ?, ?, 5000, ?)
			""", UUID.randomUUID(), refundId, orderProductId, now))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("두 트랜잭션은 SKIP LOCKED로 서로 다른 만기 환불 요청을 선점한다")
	void findDueRequestedForUpdate_concurrentlyClaimsDifferentRefunds() throws Exception {
		LocalDateTime now = LocalDateTime.of(2026, 7, 15, 12, 0);
		seedDueRefunds(now);

		CountDownLatch firstClaimed = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		Future<UUID> first = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
			List<OrderRefund> claimed = orderRefundPersistence.findDueRequestedForUpdate(now, 1);
			UUID refundId = claimed.getFirst().getId();
			firstClaimed.countDown();
			await(releaseFirst);
			return refundId;
		}));

		try {
			assertThat(firstClaimed.await(5, TimeUnit.SECONDS)).isTrue();
			Future<UUID> second = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status ->
				orderRefundPersistence.findDueRequestedForUpdate(now, 1).getFirst().getId()
			));

			UUID secondId = second.get(5, TimeUnit.SECONDS);
			releaseFirst.countDown();
			UUID firstId = first.get(5, TimeUnit.SECONDS);

			assertThat(firstId).isNotEqualTo(secondId);
		} finally {
			releaseFirst.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DisplayName("만기 환불 선점은 외부 트랜잭션 없이 호출할 수 없다")
	void findDueRequestedForUpdate_withoutTransaction_throwsException() {
		assertThatThrownBy(() -> orderRefundPersistence.findDueRequestedForUpdate(
			LocalDateTime.of(2026, 7, 15, 12, 0),
			1
		)).isInstanceOf(IllegalTransactionStateException.class);
	}

	private void seedDueRefunds(LocalDateTime now) {
		UUID orderId = UUID.randomUUID();
		UUID paymentId = UUID.randomUUID();
		UUID buyerId = UUID.randomUUID();
		UUID firstProductId = UUID.randomUUID();
		UUID secondProductId = UUID.randomUUID();
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.executeWithoutResult(status -> {
			insertPaidOrder(orderId, paymentId, buyerId, firstProductId, secondProductId, now);
			orderRefundPersistence.saveAllAndFlush(List.of(
				OrderRefund.request(orderId, paymentId, buyerId, firstProductId, 10_000,
					now.minusMinutes(10), now.minusMinutes(2)),
				OrderRefund.request(orderId, paymentId, buyerId, secondProductId, 20_000,
					now.minusMinutes(9), now.minusMinutes(1))
			));
		});
	}

	private void insertPaidOrder(
		UUID orderId,
		UUID paymentId,
		UUID buyerId,
		UUID firstProductId,
		UUID secondProductId,
		LocalDateTime now
	) {
		jdbcTemplate.update("""
			insert into "order" (
			  id, buyer_id, order_number, order_status,
			  total_order_amount, total_product_count, version
			) values (?, ?, ?, 'PAID', 30000, 2, 0)
			""", orderId, buyerId, "ORD-" + orderId.toString().substring(0, 8));
		jdbcTemplate.update("""
			insert into order_payment (
			  id, approved_amount, approved_at, buyer_id, order_id, payment_id,
			  payment_method, pg_tx_id, provider
			) values (?, 30000, ?, ?, ?, ?, 'CARD', ?, 'TOSS')
			""", UUID.randomUUID(), now, buyerId, orderId, paymentId, "tx-" + paymentId);
		insertPaidProduct(firstProductId, orderId, 10_000, now);
		insertPaidProduct(secondProductId, orderId, 20_000, now);
	}

	private void insertPaidProduct(UUID orderProductId, UUID orderId, int amount, LocalDateTime now) {
		jdbcTemplate.update("""
			insert into order_product (
			  id, created_at, downloaded, order_product_status, product_amount_snapshot,
			  product_id, product_title_snapshot, product_type_snapshot, seller_id,
			  updated_at, order_id, version
			) values (?, ?, false, 'PAID', ?, ?, '상품', 'PROMPT', ?, ?, ?, 0)
			""",
			orderProductId, now, amount, UUID.randomUUID(), UUID.randomUUID(), now, orderId);
	}

	private void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("timed out waiting to release row lock");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting to release row lock", exception);
		}
	}
}
