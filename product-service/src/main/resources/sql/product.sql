-- product-service DB에서 실행
-- category.sql 먼저 실행 후 실행
-- seller_id: user.sql의 사용자id와 일치
INSERT INTO product (
    id, seller_id, category_id,
    major_version, patch_version, change_reason,
    name, description, product_type,
    amount_type, amount, thumbnail_url, content,
    badge, status, rejection_reason,
    sales_count, view_count, wish_count,
    created_at, updated_at, deleted_at
)
VALUES
    -- 프롬프트마스터 (aaaa...)
    (
        'aaaaaaaa-0001-0000-0000-000000000001',
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        '11111111-0000-0000-0000-000000000003',
        1, 3, '타입 개선 및 예제 보강',
        '리액트 컴포넌트 리팩터링 도우미',
        'React 컴포넌트 분리, 상태 관리 정리, TypeScript 타입 개선까지 한 번에 처리하는 프롬프트입니다.',
        'Claude 3.5', 'PAID', 7900, NULL, NULL,
        NULL, 'ON_SALE', NULL,
        760, 3200, 145,
        NOW() - INTERVAL '30 days', NOW() - INTERVAL '1 day', NULL
    ),
    (
        'aaaaaaaa-0002-0000-0000-000000000002',
        'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
        '11111111-0000-0000-0000-000000000002',
        1, 1, 'SEO 가이드 강화',
        '블로그 포스팅 자동 완성기',
        '주제와 키워드만 입력하면 SEO 최적화된 블로그 글을 자동으로 완성해주는 프롬프트입니다.',
        'GPT-4o', 'PAID', 5900, NULL, NULL,
        '신규', 'ON_SALE', NULL,
        430, 1800, 87,
        NOW() - INTERVAL '25 days', NOW() - INTERVAL '2 days', NULL
    ),
    -- AI크리에이터 (bbbb...)
    (
        'aaaaaaaa-0003-0000-0000-000000000003',
        'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
        '11111111-0000-0000-0000-000000000004',
        2, 0, '광고 카피 템플릿 추가',
        '인스타그램 마케팅 카피 생성기',
        '제품 정보를 입력하면 인스타그램에 최적화된 마케팅 카피와 해시태그를 자동 생성합니다.',
        'GPT-4o', 'PAID', 4900, NULL, NULL,
        NULL, 'ON_SALE', NULL,
        920, 4100, 210,
        NOW() - INTERVAL '45 days', NOW() - INTERVAL '3 days', NULL
    ),
    (
        'aaaaaaaa-0004-0000-0000-000000000004',
        'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
        '11111111-0000-0000-0000-000000000001',
        1, 0, NULL,
        'Midjourney 프롬프트 생성기 (상업용)',
        '원하는 이미지 스타일과 분위기를 입력하면 Midjourney에 바로 사용 가능한 최적 프롬프트를 만들어줍니다.',
        'Midjourney v6', 'PAID', 9900, NULL, NULL,
        NULL, 'ON_SALE', NULL,
        1250, 5600, 320,
        NOW() - INTERVAL '60 days', NOW() - INTERVAL '5 days', NULL
    ),
    -- 코드위즈 (cccc...)
    (
        'aaaaaaaa-0005-0000-0000-000000000005',
        'cccccccc-cccc-cccc-cccc-cccccccccccc',
        '11111111-0000-0000-0000-000000000005',
        1, 2, '다국어 응대 개선',
        '고객 응대 챗봇 시스템 프롬프트',
        '쇼핑몰, SaaS, 서비스업 등 다양한 업종에 맞게 커스터마이징 가능한 고객 응대 챗봇 시스템 프롬프트입니다.',
        'Claude 3.5', 'PAID', 14900, NULL, NULL,
        NULL, 'ON_SALE', NULL,
        380, 2200, 95,
        NOW() - INTERVAL '20 days', NOW() - INTERVAL '1 day', NULL
    ),
    (
        'aaaaaaaa-0006-0000-0000-000000000006',
        'cccccccc-cccc-cccc-cccc-cccccccccccc',
        '11111111-0000-0000-0000-000000000006',
        1, 0, NULL,
        'SQL 쿼리 최적화 도우미',
        '느린 SQL 쿼리를 붙여넣으면 실행 계획을 분석하고 인덱스 전략과 함께 최적화된 쿼리를 제안합니다.',
        'GPT-4o', 'PAID', 6900, NULL, NULL,
        '신규', 'ON_SALE', NULL,
        540, 2800, 113,
        NOW() - INTERVAL '15 days', NOW(), NULL
    ),
    (
        'aaaaaaaa-0007-0000-0000-000000000007',
        'cccccccc-cccc-cccc-cccc-cccccccccccc',
        '11111111-0000-0000-0000-000000000003',
        1, 1, '보안 취약점 검사 항목 추가',
        'Python 코드 리뷰어',
        'Python 코드를 입력하면 가독성, 성능, 보안 관점에서 상세한 리뷰와 개선안을 제공합니다.',
        'Claude 3.5', 'FREE', 0, NULL, NULL,
        NULL, 'ON_SALE', NULL,
        1100, 4500, 250,
        NOW() - INTERVAL '50 days', NOW() - INTERVAL '7 days', NULL
    ),
    -- 마케팅킹 (dddd...)
    (
        'aaaaaaaa-0008-0000-0000-000000000008',
        'dddddddd-dddd-dddd-dddd-dddddddddddd',
        '11111111-0000-0000-0000-000000000002',
        1, 0, NULL,
        '이메일 영문 교정 + 비즈니스 어조 변환',
        '한국어로 쓴 이메일 내용을 입력하면 비즈니스 영어로 교정·번역하고 격식체 어조로 변환합니다.',
        'GPT-4o', 'PAID', 3900, NULL, NULL,
        '신규', 'ON_SALE', NULL,
        290, 1500, 62,
        NOW() - INTERVAL '10 days', NOW(), NULL
    ),
    (
        'aaaaaaaa-0009-0000-0000-000000000009',
        'dddddddd-dddd-dddd-dddd-dddddddddddd',
        '11111111-0000-0000-0000-000000000004',
        1, 0, NULL,
        '유튜브 썸네일 카피 생성기',
        '영상 주제를 입력하면 클릭률을 높이는 유튜브 썸네일 카피와 제목을 자동 생성합니다.',
        'GPT-4o', 'PAID', 4900, NULL, NULL,
        NULL, 'ON_SALE', NULL,
        610, 2700, 130,
        NOW() - INTERVAL '18 days', NOW() - INTERVAL '2 days', NULL
    ),
    (
        'aaaaaaaa-0010-0000-0000-000000000010',
        'dddddddd-dddd-dddd-dddd-dddddddddddd',
        '11111111-0000-0000-0000-000000000004',
        1, 1, '스토리텔링 구조 보강',
        '브랜드 스토리 작성 도우미',
        '브랜드의 핵심 가치와 타깃 고객을 입력하면 감성적인 브랜드 스토리를 완성해줍니다.',
        'Claude 3.5', 'PAID', 6900, NULL, NULL,
        NULL, 'ON_SALE', NULL,
        475, 2100, 98,
        NOW() - INTERVAL '35 days', NOW() - INTERVAL '4 days', NULL
    ),
    -- 데이터닥터 (eeee...)
    (
        'aaaaaaaa-0011-0000-0000-000000000011',
        'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee',
        '11111111-0000-0000-0000-000000000006',
        1, 0, NULL,
        '데이터 분석 보고서 자동 생성기',
        '원시 데이터를 붙여넣으면 인사이트 요약, 시각화 추천, 의사결정 포인트를 자동으로 도출합니다.',
        'GPT-4o', 'PAID', 11900, NULL, NULL,
        '신규', 'ON_SALE', NULL,
        320, 1600, 74,
        NOW() - INTERVAL '8 days', NOW(), NULL
    ),
    (
        'aaaaaaaa-0012-0000-0000-000000000012',
        'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee',
        '11111111-0000-0000-0000-000000000001',
        1, 2, '네거티브 프롬프트 개선',
        'Stable Diffusion 인물 사진 프롬프트 팩',
        '자연스러운 인물 사진 생성을 위한 고퀄리티 SD 프롬프트 10종 세트입니다.',
        'Stable Diffusion', 'PAID', 8900, NULL, NULL,
        NULL, 'ON_SALE', NULL,
        870, 3800, 195,
        NOW() - INTERVAL '42 days', NOW() - INTERVAL '6 days', NULL
    ),
    (
        'aaaaaaaa-0013-0000-0000-000000000013',
        'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee',
        '11111111-0000-0000-0000-000000000006',
        1, 0, NULL,
        'A/B 테스트 설계 도우미',
        '가설과 목표 지표를 입력하면 A/B 테스트 설계, 샘플 사이즈 계산, 결과 해석 방법을 제안합니다.',
        'Claude 3.5', 'FREE', 0, NULL, NULL,
        NULL, 'ON_SALE', NULL,
        680, 3100, 142,
        NOW() - INTERVAL '55 days', NOW() - INTERVAL '8 days', NULL
    )
ON CONFLICT (id) DO NOTHING;
