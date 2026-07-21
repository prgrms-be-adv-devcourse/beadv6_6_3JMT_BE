package com.prompthub.admin.user.infrastructure.persistence;

import com.prompthub.admin.user.domain.model.User;
import com.prompthub.admin.user.domain.model.UserRole;
import com.prompthub.admin.user.domain.model.UserStatus;
import com.prompthub.admin.user.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(UserRepositoryAdapter.class)
@ActiveProfiles("test")
@Sql("/sql/users.sql")
class UserRepositoryAdapterTest {

	private static final UUID USER_1 = UUID.fromString("11111111-0000-0000-0000-000000000001");

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TestEntityManager entityManager;

	@Test
	void 상태와_역할로_필터링해서_목록을_조회한다() {
		List<User> result = userRepository.findUsers(UserStatus.ACTIVE, UserRole.SELLER, null, 0, 20);

		assertThat(result).hasSize(1);
		assertThat(result.getFirst().getUserId()).isEqualTo(UUID.fromString("11111111-0000-0000-0000-000000000002"));
	}

	@Test
	void 키워드로_이름_이메일을_검색한다() {
		long count = userRepository.countUsers(null, null, "김도윤");

		assertThat(count).isEqualTo(1);
	}

	@Test
	void 생성일_구간으로_신규가입자_수를_센다() {
		long count = userRepository.countCreatedBetween(
			LocalDateTime.of(2026, 7, 1, 0, 0),
			LocalDateTime.of(2026, 7, 3, 0, 0)
		);

		assertThat(count).isEqualTo(2);
	}

	@Test
	void 상태변경_저장_후_재조회하면_바뀐_상태가_유지된다() {
		User user = userRepository.findById(USER_1).orElseThrow();

		user.block();
		userRepository.save(user);

		entityManager.flush();
		entityManager.clear();

		Optional<User> reloaded = userRepository.findById(USER_1);
		assertThat(reloaded).isPresent();
		assertThat(reloaded.get().getStatus()).isEqualTo(UserStatus.BLOCKED);
	}

	@Test
	void id_목록으로_여러_유저를_한번에_조회한다() {
		List<User> result = userRepository.findAllByIds(List.of(
			USER_1,
			UUID.fromString("11111111-0000-0000-0000-000000000002"),
			UUID.fromString("11111111-0000-0000-0000-000000000999")
		));

		assertThat(result).hasSize(2);
	}
}
