package com.prompthub.admin.order.infrastructure.persistence;

import com.prompthub.admin.order.domain.model.SellerNickname;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SellerNicknameRepository extends JpaRepository<SellerNickname, UUID> {
}
