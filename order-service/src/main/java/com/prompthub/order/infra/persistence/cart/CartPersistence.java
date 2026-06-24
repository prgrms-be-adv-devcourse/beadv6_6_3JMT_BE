package com.prompthub.order.infra.persistence.cart;

import com.prompthub.order.domain.model.Cart;
import com.prompthub.order.domain.model.CartProduct;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CartPersistence extends JpaRepository<Cart, UUID> {

    @Query("""
        select distinct c
        from Cart c
        left join fetch c.cartProducts
        where c.buyerId = :buyerId
    """)
    Optional<Cart> findByBuyerIdWithCartProducts(
            @Param("buyerId") UUID buyerId
    );

    @Query("""
        select cp
        from CartProduct cp
        join fetch cp.cart c
        where cp.id = :cartProductId
    """)
    Optional<CartProduct> findCartProductWithCart(
            @Param("cartProductId") UUID cartProductId
    );
}
