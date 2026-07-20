package com.prompthub.admin.seller.domain.repository;

import com.prompthub.admin.seller.domain.model.SellerRegister;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SellerRegisterRepository {
	Optional<SellerRegister> findById(UUID registerId);
	SellerRegister save(SellerRegister sellerRegister);
	List<SellerRegister> findAll(SellerRegisterStatus status, int page, int size);
	long count(SellerRegisterStatus status);
}
