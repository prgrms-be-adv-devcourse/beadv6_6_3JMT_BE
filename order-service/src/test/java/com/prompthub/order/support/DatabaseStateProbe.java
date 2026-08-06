package com.prompthub.order.support;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.prompthub.order.domain.model.CartProduct;
import com.prompthub.order.domain.model.Order;
import com.prompthub.order.infra.persistence.cart.CartPersistence;
import com.prompthub.order.infra.persistence.order.OrderPersistence;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class DatabaseStateProbe {

	private final OrderPersistence orderPersistence;
	private final CartPersistence cartPersistence;
	private final JdbcTemplate jdbcTemplate;
	private final TransactionTemplate readTransaction;

	public DatabaseStateProbe(
		OrderPersistence orderPersistence,
		CartPersistence cartPersistence,
		JdbcTemplate jdbcTemplate,
		PlatformTransactionManager transactionManager
	) {
		this.orderPersistence = orderPersistence;
		this.cartPersistence = cartPersistence;
		this.jdbcTemplate = jdbcTemplate;
		this.readTransaction = new TransactionTemplate(transactionManager);
		this.readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.readTransaction.setReadOnly(true);
	}

	public Order loadOrder(UUID orderId) {
		return read(() -> orderPersistence.findByIdWithOrderProducts(orderId).orElseThrow());
	}

	public List<UUID> cartProductIds(UUID buyerId) {
		return read(() -> cartPersistence.findByBuyerIdWithCartProducts(buyerId).orElseThrow()
			.getCartProducts().stream()
			.map(CartProduct::getProductId)
			.toList());
	}

	public long countCartProductRows(UUID productId) {
		return read(() -> {
			Long count = jdbcTemplate.queryForObject(
				"select count(*) from cart_product where product_id = ?",
				Long.class,
				productId
			);
			return count == null ? 0 : count;
		});
	}

	private <T> T read(Supplier<T> query) {
		return readTransaction.execute(status -> query.get());
	}
}
