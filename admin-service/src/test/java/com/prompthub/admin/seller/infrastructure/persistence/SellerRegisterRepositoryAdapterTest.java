package com.prompthub.admin.seller.infrastructure.persistence;

import com.prompthub.admin.seller.domain.model.SellerRegister;
import com.prompthub.admin.seller.domain.model.SellerRegisterStatus;
import com.prompthub.admin.seller.domain.repository.SellerRegisterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(SellerRegisterRepositoryAdapter.class)
@ActiveProfiles("test")
@Sql("/sql/seller_registers.sql")
class SellerRegisterRepositoryAdapterTest {

	private static final UUID PENDING_ID = UUID.fromString("22222222-0000-0000-0000-000000000001");

	@Autowired
	private SellerRegisterRepository sellerRegisterRepository;

	@Autowired
	private TestEntityManager entityManager;

	@Test
	void 상태로_필터링해서_목록을_조회한다() {
		List<SellerRegister> result = sellerRegisterRepository.findAll(SellerRegisterStatus.PENDING, 0, 20);

		assertThat(result).hasSize(1);
		assertThat(result.getFirst().getSellerRegisterId()).isEqualTo(PENDING_ID);
		assertThat(result.getFirst().getCategories()).containsExactly("마케팅");
	}

	@Test
	void 상태별_건수를_센다() {
		assertThat(sellerRegisterRepository.count(SellerRegisterStatus.APPROVED)).isEqualTo(1);
		assertThat(sellerRegisterRepository.count(null)).isEqualTo(2);
	}

	@Test
	void 승인_후_저장하면_재조회시_승인상태가_유지된다() {
		SellerRegister register = sellerRegisterRepository.findById(PENDING_ID).orElseThrow();

		register.approve();
		sellerRegisterRepository.save(register);

		entityManager.flush();
		entityManager.clear();

		SellerRegister reloaded = sellerRegisterRepository.findById(PENDING_ID).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(SellerRegisterStatus.APPROVED);
		assertThat(reloaded.getReviewedAt()).isNotNull();
	}

	@Test
	void 반려_후_저장하면_사유가_유지된다() {
		SellerRegister register = sellerRegisterRepository.findById(PENDING_ID).orElseThrow();

		register.reject("포트폴리오 미확인");
		sellerRegisterRepository.save(register);

		entityManager.flush();
		entityManager.clear();

		SellerRegister reloaded = sellerRegisterRepository.findById(PENDING_ID).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(SellerRegisterStatus.REJECTED);
		assertThat(reloaded.getRejectReason()).isEqualTo("포트폴리오 미확인");
	}
}
