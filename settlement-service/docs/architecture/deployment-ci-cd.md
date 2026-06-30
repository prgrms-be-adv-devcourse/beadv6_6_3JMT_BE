# 배포 구조 — CI/CD 흐름

정산 서비스를 포함한 백엔드를 어떻게 빌드하고 EC2에 띄우는지를 정리한다.
프론트(Vercel)와 백엔드(EC2) 배포 경로가 다르므로 둘을 나눠서 본다.

## 전체 그림

```
            ┌─────────────┐        ┌──────────────┐        ┌──────────────────────┐
Frontend ──▶│   Vercel    │        │GitHub Actions│        │         EC2          │
            │ 자동 SSL    │◀───────│ main-cd.yml  │───────▶│ self-hosted runner   │
            └─────────────┘        └──────────────┘        │ docker compose up -d │
                                                            │ Services·Kafka·PG    │
                                                            └──────────────────────┘
```

- 프론트: Vercel에 올린다. 별도 인증서 작업 없이 Vercel이 SSL을 자동으로 붙여준다.
  공통 도메인을 받아 쓴다.
- 백엔드: GitHub Actions가 트리거되고 실제 배포는 EC2에 떠 있는 self-hosted runner가 한다.

## CI — 빌드/테스트 검증

CI 단계는 "이 코드가 빌드되고 테스트가 통과하는가"를 확인하는 게 목적이다.

- PR이나 push 시점에 돈다.
- 빌드가 깨지거나 테스트가 실패하면 여기서 막힌다.
- 배포까지는 가지 않는다. 합쳐도 되는 코드인지만 거른다.

## CD — EC2에서 빌드·실행

CD는 `main-cd.yml`로 트리거되고 실제 작업은 EC2 위에서 일어난다.
여기서 핵심은 GitHub Actions가 EC2에 SSH로 접속하는 게 아니라는 점이다.

흐름은 이렇다.

1. EC2 안에 self-hosted runner 에이전트가 미리 설치돼서 계속 떠 있다.
2. `main-cd.yml`이 트리거되면 GitHub이 job을 큐에 올린다.
3. EC2에 떠 있는 runner가 그 job을 집어가서 EC2 로컬에서 직접 실행한다.
4. runner가 빌드 → env 주입 → 이미지 생성 → `docker compose up -d`를 수행한다.

즉 "GitHub이 EC2에 들어가서 시킨다"가 아니라 "EC2에 사는 일꾼이 GitHub 일감을 받아와
자기 집(EC2)에서 처리한다"에 가깝다. 결과적으로 빌드·배포가 전부 EC2에서 일어난다.

배포 작업 자체는 스크립트로 묶어 runner가 실행한다.

## 도메인·CORS 설정

배포 파이프라인과는 별개로, 한 번 세팅해두는 성격의 작업이다.

- 프론트·백엔드에 공통 도메인을 적용한다.
- API Gateway에서 cross-origin 도메인을 열어둔다(CORS). 프론트(Vercel 도메인)에서
  백엔드를 부를 수 있게 허용한다.

## 컨테이너 구성

EC2의 Docker에서 `docker compose up -d`로 다음을 띄운다.

- 각 서비스(정산 등)
- Apache Kafka
- PostgreSQL

env는 runner가 EC2 로컬에서 주입한다. EC2에 `.env`를 미리 두거나 GitHub Secrets를
워크플로우에서 내려주는 방식 중 무엇을 쓸지는 정해지면 여기에 기록한다.
