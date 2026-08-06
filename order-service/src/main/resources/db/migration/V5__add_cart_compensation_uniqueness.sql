ALTER TABLE ONLY cart
    ADD CONSTRAINT uk_cart_buyer_id UNIQUE (buyer_id);

ALTER TABLE ONLY cart_product
    ADD CONSTRAINT uk_cart_product_cart_product UNIQUE (cart_id, product_id);
