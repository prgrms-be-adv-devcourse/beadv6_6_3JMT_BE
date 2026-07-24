package com.prompthub.admin.order.repository;

import com.prompthub.admin.order.entity.SellerNickname;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SellerNicknameRepository extends JpaRepository<SellerNickname, UUID> {

	List<SellerNickname> findByNicknameContainingIgnoreCase(String nickname);
}
