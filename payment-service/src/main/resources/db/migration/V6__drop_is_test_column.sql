-- is_test는 결제 요청마다 달라지는 값이 아니라 애플리케이션 전역 설정(payment.toss.test-mode)이
-- 매 row에 그대로 복제 저장되던 컬럼이라 정보 가치가 없어 제거한다.
ALTER TABLE payment DROP COLUMN is_test;
