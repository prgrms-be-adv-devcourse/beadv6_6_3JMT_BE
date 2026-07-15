<div align="center">

  <img src=".github/codeflow-card.svg" alt="CodeFlow card" />

</div>

# PromptHub

AI 프롬프트, 노션/PPT/엑셀 템플릿 등 디지털 상품을 사고파는 마켓플레이스 **PromptHub**의 백엔드입니다.
프로그래머스 백엔드 데브코스 파이널 프로젝트(팀 `3JMT`)로 제작 중이며, Spring Cloud 기반 MSA로 구성되어 있습니다.

## Table of Contents

- [Features](#features)
- [Tech Stack](#tech-stack)
- [Architecture](#architecture)
- [Run Locally](#run-locally)
- [API Reference](#api-reference)
- [Team](#team)
- [팀 / 기여 가이드](#팀--기여-가이드)
- [FAQ](#faq)
- [Demo](#demo)
- [License](#license)

## Features

- 회원가입/로그인, JWT(RS256) 기반 인증·인가, 판매자 전환
- 상품(프롬프트/노션/PPT/엑셀) 등록·조회·버전 관리(메이저/패치), 카테고리, 리뷰
- 위시리스트(찜)
- 장바구니, 주문 생성·취소
- Toss Payments 연동 결제·환불
- 정산 배치 처리 (Spring Batch, 판매자별 정산)
- 관리자 기능 (상품·정산 등 관리)

## Tech Stack

**Language**
![Java](https://img.shields.io/badge/Java_21-ED8B00?style=flat-square&logo=openjdk&logoColor=white)

**Framework**
![Spring Boot](https://img.shields.io/badge/Spring_Boot_4.1.0-6DB33F?style=flat-square&logo=spring-boot&logoColor=white)
![Spring Cloud](https://img.shields.io/badge/Spring_Cloud_2025.1.2-6DB33F?style=flat-square&logo=spring&logoColor=white)
![Spring Security](https://img.shields.io/badge/Spring_Security-6DB33F?style=flat-square&logo=springsecurity&logoColor=white)
![Spring Batch](https://img.shields.io/badge/Spring_Batch-6DB33F?style=flat-square&logo=spring&logoColor=white)

**Persistence**
![Spring Data JPA](https://img.shields.io/badge/Spring_Data_JPA-6DB33F?style=flat-square&logo=spring&logoColor=white)
![QueryDSL](https://img.shields.io/badge/QueryDSL_5.1.0-0769AD?style=flat-square&logo=java&logoColor=white)

**Database**
![PostgreSQL](https://img.shields.io/badge/PostgreSQL_18-316192?style=flat-square&logo=postgresql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis_7.4-DD0031?style=flat-square&logo=redis&logoColor=white)

**Messaging &amp; RPC**
![Apache Kafka](https://img.shields.io/badge/Apache%20Kafka-000?style=flat-square&logo=apachekafka)
![gRPC](https://img.shields.io/badge/gRPC-4285F4?style=flat-square)
![Protocol Buffers](https://img.shields.io/badge/Protobuf-4285F4?style=flat-square)

**외부 연동**
![Toss Payments](https://img.shields.io/badge/Toss_Payments-0064FF?style=flat-square)

**Infra &amp; DevOps**
![Docker](https://img.shields.io/badge/Docker-2496ED?style=flat-square&logo=docker&logoColor=white)
![GitHub Actions](https://img.shields.io/badge/GitHub_Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white)
![AWS EC2](https://img.shields.io/badge/AWS_EC2-FF9900?style=flat-square&logo=amazonaws&logoColor=white)
![Amazon S3](https://img.shields.io/badge/Amazon_S3-569A31?style=flat-square&logo=amazons3&logoColor=white)

**AI 개발 도구** (백엔드 런타임 연동 아님 — 코드 작성에 사용)
![Claude Code](https://img.shields.io/badge/Claude_Code-D97757?style=flat-square&logo=claude&logoColor=white)
![OpenAI Codex](https://img.shields.io/badge/OpenAI_Codex-74aa9c?style=flat-square&logo=openai&logoColor=white)

**License**
![MIT License](https://img.shields.io/badge/License-MIT-green.svg?style=flat-square)

## Architecture

<!-- TODO: 아키텍처 다이어그램 이미지 삽입 -->

**서비스 구성** (모듈 / 포트 HTTP·gRPC / 역할)


| 모듈                   | 포트          | 역할                                                              |
| -------------------- | ----------- | --------------------------------------------------------------- |
| `discovery`          | 8761        | Eureka 서비스 레지스트리                                                |
| `config`             | 8888        | Config Server (native, `config/src/main/resources/configs/` 서빙) |
| `apigateway`         | 8000        | 진입점. JWT 검증, 라우팅, `X-User-Id`/`X-User-Role` 헤더 주입 (WebFlux)     |
| `user-service`       | 8081 / 9081 | 회원·인증(JWT 발급)·판매자·찜                                             |
| `product-service`    | 8082 / 9082 | 상품·카테고리·리뷰                                                      |
| `order-service`      | 8083 / 9083 | 주문·장바구니·Outbox Relay                                            |
| `payment-service`    | 8084 / 9084 | 결제(Toss Payments), 환불                                           |
| `settlement-service` | 8085        | 정산 (Spring Batch)                                               |
| `admin-service`      | 8086        | 관리자 기능                                                          |
| `common-module`      | -           | 공용 라이브러리 (예외, 에러코드, 공통 응답 래퍼)                                   |


**통신 방식**

- **외부 → 내부**: 클라이언트 → API Gateway(WebFlux) → JWT 서명 검증(RSA 공개키) → `X-User-Id`/`X-User-Role` 헤더 주입 → Eureka에서 `lb://{SERVICE-NAME}` 조회 후 라우팅
- **내부 동기 통신**: 서비스 간 gRPC(상품/판매자 정보 조회 등), 일부는 FeignClient(HTTP)
- **내부 비동기 통신**: Kafka (`order-events`, `product-events`, `payment-events` — 이벤트 종류는 payload의 `eventType` 필드로 구분, 예: `payment.failed`)
- **외부 연동**: payment-service → Toss Payments API

**인증/인가**: user-service가 로그인 시 JWT(RS256) 발급 → API Gateway가 서명 검증 후 `sub`(사용자 ID), `roles`(BUYER/SELLER/ADMIN)를 헤더로 다운스트림에 전달. `status` 클레임이 `ACTIVE`가 아니면 Gateway 단에서 403 처리. 각 서비스는 JWT를 직접 파싱하지 않고 헤더만 신뢰한다.

**기동 순서**: `postgres` + `kafka` → `discovery` → `config` → 비즈니스 서비스 5종(8081~8085) → `apigateway`. `docker-compose.yml`의 `depends_on`이 순서를 보장한다.

**배포**: 별도 운영(prod) 서버 없이 AWS EC2 단일 인스턴스를 "개발서버"로 운영한다. `develop` 브랜치 머지가 곧 개발서버 자동 배포이며, `main`은 완성 스냅샷을 태그(`v1.0.0` 등)로만 보존하는 동결 브랜치다.

## Run Locally

Clone the project

```bash
git clone git@github.com:prgrms-be-adv-devcourse/beadv6_6_3JMT_BE.git
cd beadv6_6_3JMT_BE
```

환경 변수 설정 ([`.env.example`](./.env.example) 참고)

```bash
cp .env.example .env
# .env에 실제 값 채우기
```

전체 서비스 기동 (Docker Compose)

```bash
docker compose --env-file .env up -d --build
```

`depends_on`이 기동 순서를 보장하므로 별도로 순서를 신경 쓸 필요는 없다. 개별 서비스만 IDE에서 띄우고 싶다면 `config` → `discovery` → 나머지 서비스 → `apigateway` 순으로 기동한다.

```bash
./gradlew :user-service:bootRun
```

## API Reference

각 서비스는 springdoc-openapi로 Swagger 문서를 제공하며, API Gateway가 `/{service-name}/v3/api-docs`로 각 서비스 문서를 프록시해 통합 Swagger UI에서 확인할 수 있다.

```
http://ec2-13-209-136-116.ap-northeast-2.compute.amazonaws.com/swagger-ui/index.html
```

## Team


| 이름           | 역할  | 담당  | GitHub                                         |
| ------------ | --- | --- | ---------------------------------------------- |
| Minseo Kim   |     |     | [@git-mesome](https://github.com/git-mesome)   |
| JongChan Lee |     |     | [@oxix97](https://github.com/oxix97)           |
| Taehyeon Ko  |     |     | [@TaetaetaE01](https://github.com/TaetaetaE01) |
| Jinpyo An    |     |     | [@Jinpyo-An](https://github.com/Jinpyo-An)     |
| JiHeeKim     |     |     | [@jhkimm96](https://github.com/jhkimm96)       |


<!-- TODO: 역할/담당 채우기 -->

## 팀 / 기여 가이드

- **브랜치 전략 / 커밋 컨벤션 / 병합 전략**: [`docs/guides/git-convention.md`](./docs/guides/git-convention.md)
- **코드 컨벤션**: [클린 아키텍처](./docs/guides/clean-architecture.md) · [도메인 모델](./docs/guides/domain-model.md) · [Controller · 예외 처리](./docs/guides/controller-exception.md) · [코드 스타일](./docs/guides/code-style.md) · [Swagger 문서화](./docs/guides/swagger.md) · [보안(시크릿 유출 방지)](./docs/guides/security.md)
- **이슈/PR 템플릿**: [Pull Request](./.github/PULL_REQUEST_TEMPLATE.md) · [Bug Report](./.github/ISSUE_TEMPLATE/bug_report.md) · [Feature Request](./.github/ISSUE_TEMPLATE/feature_request.md)

## FAQ

> **왜 마이크로서비스로 나눴나요?**

데브코스 파이널 프로젝트로, 서비스 분리·서비스 간 통신(gRPC/Kafka)·배포 자동화를 직접 구현해보는 학습 목적이 크다. 실서비스 트래픽 규모에 맞춘 분리는 아니다.

> **운영(prod) 서버가 따로 없는 이유는?**

포트폴리오/학습 프로젝트라 AWS EC2 단일 인스턴스를 "개발서버"로 운영한다. `develop` 머지가 곧 배포이며, `main`은 완성 스냅샷을 태그로만 보존한다. 자세한 배경은 `docs/adr/0005-develop-deploy-main-freeze.md` 참고.

> **API 경로에 `v1`, `v2`가 같이 있는 이유는?**

세미 프로젝트(`api/v1`, 완료)에 이어 최종 프로젝트를 `api/v2`로 재구현하는 중이라 과도기적으로 공존한다. `main`은 `v1` 완성 시점의 스냅샷으로 동결되어 있다.

## Demo

<!-- TODO: 데모 gif 또는 배포 링크 삽입 -->

## License

[MIT](./LICENSE)