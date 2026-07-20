package com.prompthub.admin.seller.infrastructure.persistence;

import com.prompthub.admin.seller.domain.model.SellerRegister;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import com.prompthub.admin.seller.domain.repository.SellerRegisterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SellerRegisterRepositoryAdapter implements SellerRegisterRepository {

	private final SellerRegisterJpaRepository jpaRepository;

	@Override
	public Optional<SellerRegister> findById(UUID registerId) {
		return jpaRepository.findById(registerId);
	}

	@Override
	public SellerRegister save(SellerRegister sellerRegister) {
		return jpaRepository.save(sellerRegister);
	}

	@Override
	public List<SellerRegister> findAll(SellerRegisterStatus status, int page, int size) {
		PageRequest pageable = PageRequest.of(page, size, Sort.by("submittedAt").descending());
		if (status == null) {
			return jpaRepository.findAll(pageable).getContent();
		}
		return jpaRepository.findAllByStatus(status, pageable).getContent();
	}

	@Override
	public long count(SellerRegisterStatus status) {
		if (status == null) {
			return jpaRepository.count();
		}
		return jpaRepository.countByStatus(status);
	}
}
