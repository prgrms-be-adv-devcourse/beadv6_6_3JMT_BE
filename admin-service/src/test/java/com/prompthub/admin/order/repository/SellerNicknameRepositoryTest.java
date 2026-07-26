package com.prompthub.admin.order.repository;

import com.prompthub.admin.order.entity.SellerNickname;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Sql("/sql/seller_nicknames.sql")
class SellerNicknameRepositoryTest {

	@Autowired
	private SellerNicknameRepository repository;

	@Test
	void 존재하는_id만_닉네임과_함께_반환하고_없는_id는_조용히_빠진다() {
		List<SellerNickname> result = repository.findAllById(List.of(
			UUID.fromString("cccccccc-0000-0000-0000-000000000001"),
			UUID.fromString("cccccccc-0000-0000-0000-000000000002"),
			UUID.fromString("cccccccc-0000-0000-0000-000000000999")
		));

		assertThat(result).hasSize(2);
		assertThat(result).extracting(SellerNickname::getNickname)
			.containsExactlyInAnyOrder("판매자A", "판매자B");
	}
}
