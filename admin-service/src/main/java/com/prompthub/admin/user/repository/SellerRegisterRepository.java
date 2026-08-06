package com.prompthub.admin.user.repository;

import com.prompthub.admin.user.entity.SellerRegister;
import com.prompthub.admin.user.entity.enums.SellerRegisterStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SellerRegisterRepository {

	private final SellerRegisterJpaRepository jpaRepository;

	public Optional<SellerRegister> findById(UUID registerId) {
		return jpaRepository.findById(registerId);
	}

	public SellerRegister save(SellerRegister sellerRegister) {
		return jpaRepository.save(sellerRegister);
	}

	public List<SellerRegister> findAll(SellerRegisterStatus status, int page, int size) {
		PageRequest pageable = PageRequest.of(page, size, Sort.by("submittedAt").descending());
		if (status == null) {
			return jpaRepository.findAll(pageable).getContent();
		}
		return jpaRepository.findAllByStatus(status, pageable).getContent();
	}

	public long count(SellerRegisterStatus status) {
		if (status == null) {
			return jpaRepository.count();
		}
		return jpaRepository.countByStatus(status);
	}
}
