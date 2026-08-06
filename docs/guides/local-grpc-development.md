# 로컬 개발 환경: gRPC 서비스 접근 가이드

> **관련 ADR:** [ADR-0006: 로컬 개발용 gRPC 접근은 docker-compose 포트 노출로 해결한다](../adr/0006-local-grpc-dev-access-via-docker-compose.md)

## 개요

로컬 PC에서 개발 AWS 인스턴스의 gRPC 서비스(user, product, order, payment, settlement)에 접근하려면 SSH 포트포워딩을 사용합니다. 

**전제조건:**
- AWS EC2 인스턴스에 SSH 접근 가능 (PEM 키)
- 로컬에서 docker-compose 기동 시 `application-local.yml` 프로파일 사용

---

## SSH 포트포워딩 설정

### 1. 모든 gRPC 포트를 한번에 포워딩

```bash
ssh -L 9081:127.0.0.1:9081 \
    -L 9082:127.0.0.1:9082 \
    -L 9083:127.0.0.1:9083 \
    -L 9084:127.0.0.1:9084 \
    -L 9085:127.0.0.1:9085 \
    -i ~/.ssh/your-key.pem ec2-user@<인스턴스-IP>
```

### 2. 개별 포트만 포워딩

특정 서비스만 작업할 때는 필요한 포트만 포워딩:

```bash
# user-service gRPC만
ssh -L 9081:127.0.0.1:9081 -i ~/.ssh/your-key.pem ec2-user@<인스턴스-IP>

# product-service gRPC만
ssh -L 9082:127.0.0.1:9082 -i ~/.ssh/your-key.pem ec2-user@<인스턴스-IP>
```

### 3. 백그라운드 실행 (선택사항)

포트포워딩을 백그라운드에서 실행:

```bash
ssh -L 9081:127.0.0.1:9081 \
    -L 9082:127.0.0.1:9082 \
    -L 9083:127.0.0.1:9083 \
    -L 9084:127.0.0.1:9084 \
    -L 9085:127.0.0.1:9085 \
    -i ~/.ssh/your-key.pem ec2-user@<인스턴스-IP> -N
```

(`-N` 플래그는 원격 명령을 실행하지 않고 포트포워딩만 수행)

---

## gRPC 포트 맵핑

| 서비스 | 로컬 포트 | 원격 포트 | 설명 |
|--------|---------|---------|------|
| user-service | 9081 | 9081 | 사용자 정보 조회/수정 |
| product-service | 9082 | 9082 | 상품 정보 조회 |
| order-service | 9083 | 9083 | 주문 처리 |
| payment-service | 9084 | 9084 | 결제 처리 |
| settlement-service | 9085 | 9085 | 정산 처리 |


---

## 트러블슈팅

### SSH 연결 실패
```bash
# 권한 확인
chmod 600 ~/.ssh/your-key.pem

# SSH 상세 로그 확인
ssh -v -L 9081:127.0.0.1:9081 -i ~/.ssh/your-key.pem ec2-user@<인스턴스-IP>
```

### 포트 이미 사용 중
```bash
# 기존 포트포워딩 종료
lsof -i :9081
kill -9 <PID>
```

### gRPC 서비스 접근 불가
- SSH 연결 상태 확인: 터미널 창이 열려 있나?
- docker-compose 컨테이너 실행 상태 확인: `docker ps`
- 원격 인스턴스의 docker-compose 상태 확인: AWS EC2에 별도 접속해서 `docker ps` 실행

---

## 추가 정보

- SSH 포트포워딩은 로컬 개발 전용. 운영 환경에서는 사용하지 않음
- 포트포워딩 연결이 끊어지면 자동 복구되지 않음. 수동으로 재연결 필요
- 여러 서비스를 동시에 개발할 때는 모든 gRPC 포트를 한번에 포워딩하는 것을 권장
