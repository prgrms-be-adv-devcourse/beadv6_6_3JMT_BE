package com.prompthub.user.user.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void create_모든_필드_정상_설정() {
        User user = User.create("홍길동", "hong@example.com", "https://img.example.com/profile.jpg", UserRole.BUYER, true);

        assertThat(user.getName()).isEqualTo("홍길동");
        assertThat(user.getEmail()).isEqualTo("hong@example.com");
        assertThat(user.getProfileImageUrl()).isEqualTo("https://img.example.com/profile.jpg");
        assertThat(user.getRole()).isEqualTo(UserRole.BUYER);
        assertThat(user.isTermsAgreed()).isTrue();
    }

    @Test
    void create_기본_상태는_ACTIVE() {
        User user = User.create("홍길동", "hong@example.com", null, UserRole.BUYER, true);

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void create_userId_자동_생성() {
        User user = User.create("홍길동", "hong@example.com", null, UserRole.BUYER, true);

        assertThat(user.getUserId()).isNotNull();
    }

    @Test
    void create_호출마다_서로_다른_userId() {
        User user1 = User.create("유저1", "user1@example.com", null, UserRole.BUYER, true);
        User user2 = User.create("유저2", "user2@example.com", null, UserRole.BUYER, true);

        assertThat(user1.getUserId()).isNotEqualTo(user2.getUserId());
    }

    @Test
    void create_profileImage_null_허용() {
        User user = User.create("홍길동", "hong@example.com", null, UserRole.BUYER, true);

        assertThat(user.getProfileImageUrl()).isNull();
    }

    @Test
    void withdraw_상태가_WITHDRAWN으로_변경() {
        User user = User.create("홍길동", "hong@example.com", null, UserRole.BUYER, true);

        user.withdraw();

        assertThat(user.getStatus()).isEqualTo(UserStatus.WITHDRAWN);
    }

    @Test
    void updateName_이름_변경() {
        User user = User.create("홍길동", "hong@example.com", null, UserRole.BUYER, true);

        user.updateName("김철수");

        assertThat(user.getName()).isEqualTo("김철수");
    }

    @Test
    void updateEmail_이메일_변경() {
        User user = User.create("홍길동", "hong@example.com", null, UserRole.BUYER, true);

        user.updateEmail("new@example.com");

        assertThat(user.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void userRole_DB_저장값_검증() {
        // DB의 user_role_type enum 라벨과 Java enum name()이 일치해야 한다.
        // 불일치 시 PostgreSQL에서 "invalid input value for enum" 오류 발생.
        assertThat(UserRole.BUYER.name()).isEqualTo("BUYER");
        assertThat(UserRole.SELLER.name()).isEqualTo("SELLER");
        assertThat(UserRole.ADMIN.name()).isEqualTo("ADMIN");
    }

    @Test
    void userStatus_DB_저장값_검증() {
        assertThat(UserStatus.ACTIVE.name()).isEqualTo("ACTIVE");
        assertThat(UserStatus.BLOCKED.name()).isEqualTo("BLOCKED");
        assertThat(UserStatus.WITHDRAWN.name()).isEqualTo("WITHDRAWN");
    }
}
