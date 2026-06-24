package com.prompthub.product.infra.persistence;

import com.prompthub.product.domain.model.entity.Category;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryJpaRepository extends JpaRepository<Category, UUID> {

	Optional<Category> findByCode(String code);
}
