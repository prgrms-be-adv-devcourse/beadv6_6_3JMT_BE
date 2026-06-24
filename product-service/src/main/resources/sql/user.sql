-- user-service DB에서 실행
-- product.sql의 seller_id와 일치하는 테스트 판매자 계정
INSERT INTO "user" ("사용자id", "이름", "이메일", "상태", "서비스 약관 동의", "역할", "생성일", "수정일")
VALUES
    (
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        '프롬프트마스터',
        'promptmaster@prompthub.com',
        'ACTIVE', true, 'SELLER', NOW(), NOW()
    ),
    (
        'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
        'AI크리에이터',
        'aicreator@prompthub.com',
        'ACTIVE', true, 'SELLER', NOW(), NOW()
    ),
    (
        'cccccccc-cccc-cccc-cccc-cccccccccccc',
        '코드위즈',
        'codewiz@prompthub.com',
        'ACTIVE', true, 'SELLER', NOW(), NOW()
    ),
    (
        'dddddddd-dddd-dddd-dddd-dddddddddddd',
        '마케팅킹',
        'marketingking@prompthub.com',
        'ACTIVE', true, 'SELLER', NOW(), NOW()
    ),
    (
        'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee',
        '데이터닥터',
        'datadoctor@prompthub.com',
        'ACTIVE', true, 'SELLER', NOW(), NOW()
    )
ON CONFLICT ("사용자id") DO NOTHING;
