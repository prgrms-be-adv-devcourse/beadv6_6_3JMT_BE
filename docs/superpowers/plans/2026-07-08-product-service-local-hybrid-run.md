# product-service 로컬 하이브리드 실행 런북 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** product-service만 로컬로 띄우고 나머지 서비스는 AWS 배포본을 그대로 쓰는 방법을 담은 런북 문서 `product-service/docs/local-hybrid-run.md`를 작성한다.

**Architecture:** 코드 변경 없음 — 이미 존재하는 설정(profile 기본값, `.env`, FE의 `lib/directRouting.ts`)을 조합해서 쓰는 방법을 문서화한다.

**Tech Stack:** Markdown 문서 작성뿐. Spring Boot(product-service), Next.js(FE)는 이미 구현된 상태 그대로 사용.

## Global Constraints

- 코드/설정 파일은 전혀 수정하지 않는다. 신규 문서 파일 1개만 생성한다.
- `product-service/.env`에 이미 있는 실제 값(DB_HOST=localhost, DB_PORT=5433, DB_NAME=postgres, DB_USERNAME=postgres)을 문서 예시에 그대로 반영한다. `DB_PASSWORD`, `OPENAI_API_KEY` 같은 실제 시크릿 값은 문서에 절대 옮겨적지 않는다 — "이미 설정돼 있음"이라고만 언급한다.
- **git add/commit 금지** — 이 저장소는 사용자가 명시적으로 요청하기 전까지 커밋하지 않는다. 아래 각 태스크의 "커밋" 스텝은 파일 저장까지만 하고 git 명령은 실행하지 않는다.
- 문서에 적는 모든 명령어·포트·경로는 실제로 이 세션에서 검증된 값과 정확히 일치해야 한다(Task 2에서 재대조).

---

### Task 1: `product-service/docs/local-hybrid-run.md` 작성

**Files:**
- Create: `product-service/docs/local-hybrid-run.md`

**Interfaces:** 없음 (문서 산출물, 다음 태스크가 그 내용을 검증만 함)

- [ ] **Step 1: 문서 디렉터리 확인**

Run: `ls product-service/docs`
Expected: 디렉터리가 있으면 그대로 진행. 없다는 에러가 나면 `product-service/docs/local-hybrid-run.md`를 만드는 시점에 디렉터리도 함께 생성된다(별도 mkdir 불필요, 파일 생성 도구가 상위 디렉터리를 만들어줌).

- [ ] **Step 2: 아래 내용 그대로 `product-service/docs/local-hybrid-run.md`에 작성**

```markdown
# product-service 로컬 하이브리드 실행

product-service만 로컬로 띄우고, 나머지(config/discovery/apigateway/user-service/order-service/
payment-service/settlement-service)는 AWS 배포본을 그대로 사용해서 검증하는 방법이다. 코드 변경은
필요 없다 — 기존 설정 조합만으로 된다.

## 알려진 제약 (먼저 읽기)

- **판매자 정보가 실제 값이 아니다.** 아래 방식은 gRPC로 user-service를 호출하지 않는 `StubSellerClient`를
  쓰기 때문에, 판매자 닉네임 등은 항상 `"테스트판매자"` 고정값으로 나온다.
- **Kafka 이벤트가 AWS로 안 간다.** 로컬 Kafka는 AWS의 Kafka와 완전히 별개 클러스터라서,
  product-service가 발행하는 `product-events`를 AWS의 user-service가 받지 못한다. 찜 목록 등
  이벤트 기반 캐시 갱신은 이 방식으로 검증되지 않는다.
- 이 문서는 mock 인프라(`NEXT_PUBLIC_API_MOCKING`)와 무관하다. FE가 mock을 쓰든 안 쓰든 상관없이 동작한다.

## 사전 준비물

- **로컬 Postgres** — `product-service/.env`가 가리키는 `localhost:5433`에 Postgres가 떠 있어야 한다.
  이미 이 값으로 `.env`가 설정돼 있다면(`DB_HOST=localhost`, `DB_PORT=5433`, `DB_NAME=postgres`,
  `DB_USERNAME=postgres`) 그 Postgres 인스턴스가 떠 있는지만 확인한다.
- **로컬 Kafka** — `product-service/docker-compose.yml`에 정의된 `product-kafka` 서비스를 띄운다.

  ```
  cd product-service
  docker compose up -d product-kafka
  ```

  컨테이너 이름은 `product-kafka-dev`, 포트는 `9092`다.
- **`product-service/.env` 파일 존재 확인** — `DB_USERNAME`/`DB_PASSWORD` 등 필수 값이 이미 있어야 한다.
  (`application.yml`이 `optional:file:./product-service/.env`로 저장소 루트 기준 상대 경로로 자동 읽는다.)

## BE: product-service 실행

1. **Eureka 클라이언트 비활성화** — `product-service/.env` 파일 끝에 아래 줄을 추가한다.

   ```
   EUREKA_CLIENT_ENABLED=false
   ```

   product-service는 게이트웨이/디스커버리를 거치지 않고 FE가 직접 호출할 것이므로 Eureka에 등록될
   필요가 없다. 이 값이 없으면 기동 시 `localhost:8761`(로컬에 없는 discovery-service)로 등록을
   시도하다 `Connection refused`로 실패한다.

2. **Active profile은 비워둔다** — `dev`/`prod`/`local` 중 아무것도 지정하지 않는다(=Spring 기본값인
   `default` 프로파일). `GrpcSellerClientAdapter`(`@Profile({"dev","prod","local"})`, 실제 gRPC)
   대신 `StubSellerClient`(`@Profile({"default","test"})`, 고정값 반환)가 자동으로 붙는다. `local`
   프로파일을 지정하면 실제 gRPC를 시도하다 AWS user-service의 gRPC 포트가 로컬에 안 닿아서 실패한다.

3. **실행**

   터미널(저장소 루트 기준):

   ```
   gradlew.bat :product-service:bootRun
   ```

   또는 IntelliJ:
   - `Run` → `Edit Configurations...` → `+` → `Gradle`
   - **Gradle project**: `product-service`
   - **Tasks**: `bootRun`

4. **기동 성공 확인** — 콘솔에 아래와 같은 로그가 보이면 정상이다.

   ```
   c.p.product.config.GrpcServerConfig      : gRPC server started on port 9082
   c.prompthub.product.ProductApplication   : Started ProductApplication in ...
   ```

   HTTP는 `8082`, gRPC는 `9082` 포트로 뜬다.

## FE: 로컬 product-service만 바라보기

`beadv6_6_3JMT_FE/.env.local`에 아래 두 값을 추가한다.

```
NEXT_PUBLIC_LOCAL_PROXY_PATHS=/api/v1/products,/api/v1/sellers/me/products,/api/v1/admin/products
NEXT_PUBLIC_LOCAL_PROXY_TARGET=http://localhost:8082
```

`lib/directRouting.ts`가 이 값을 읽어서, `NEXT_PUBLIC_LOCAL_PROXY_PATHS`에 나열된 경로로 가는 요청만
`NEXT_PUBLIC_LOCAL_PROXY_TARGET`(로컬 product-service)으로 보낸다. 이 목록에 없는 나머지
`/api/v1/*` 요청(주문, 유저, 결제, 정산 등)은 전부 기존처럼 `NEXT_PUBLIC_API_URL`(AWS 게이트웨이)로
그대로 간다.

## 동작 확인

1. FE를 실행한다: `npm run dev`
2. 브라우저에서 `/browse`, `/detail/[id]` 페이지에 접속해 상품 목록/상세가 정상적으로 나오는지 확인한다.
3. 브라우저 개발자도구 Network 탭에서:
   - 상품 관련 요청(`/api/v1/products...`)이 `localhost:8082`로 나가는지 확인한다.
   - 상품과 무관한 요청(예: `/api/v1/orders`)은 여전히 AWS 게이트웨이 주소로 나가는지 확인한다.
4. 판매자 닉네임이 `"테스트판매자"`로 고정 표시되면 정상이다(위 "알려진 제약" 참고).
```

- [ ] **Step 3: 파일 저장 확인만 하고 커밋은 하지 않는다** (Global Constraints 참고)

---

### Task 2: 문서 내용 정확성 재대조

**Files:**
- Read only: `product-service/docs/local-hybrid-run.md` (Task 1 산출물)
- Read only: `product-service/.env`, `product-service/docker-compose.yml`, `product-service/src/main/resources/application.yml`, `product-service/src/main/java/com/prompthub/product/infra/client/StubSellerClient.java`, `product-service/src/main/java/com/prompthub/product/infra/client/GrpcSellerClientAdapter.java`

**Interfaces:** 없음 (검증 전용 태스크, 산출물 없음 — 불일치 발견 시 Task 1 문서를 직접 고친다)

- [ ] **Step 1: 포트/경로 값 대조**

Run:
```
grep -n "port" product-service/src/main/resources/application.yml
grep -n "9092\|container_name" product-service/docker-compose.yml
```
Expected: `server.port: 8082`, `grpc.server.port` 기본값 `9082`가 문서의 "HTTP는 8082, gRPC는 9082"와 일치. `product-kafka` 서비스의 `container_name: product-kafka-dev`, `ports: ['9092:9092']`가 문서의 Kafka 설명과 일치.

- [ ] **Step 2: `.env` 값 대조 (시크릿 제외)**

Run: `grep -v -E "PASSWORD|API_KEY" product-service/.env`
Expected: `DB_HOST=localhost`, `DB_PORT=5433`, `DB_NAME=postgres`, `DB_USERNAME=postgres`가 문서의 "사전 준비물" 절에 적은 값과 정확히 일치. 다르면 문서 쪽을 실제 `.env` 값에 맞게 고친다.

- [ ] **Step 3: `@Profile` 값 대조**

Run:
```
grep -n "@Profile" product-service/src/main/java/com/prompthub/product/infra/client/StubSellerClient.java
grep -n "@Profile" product-service/src/main/java/com/prompthub/product/infra/client/GrpcSellerClientAdapter.java
```
Expected: `StubSellerClient`가 `@Profile({"default", "test"})`, `GrpcSellerClientAdapter`가 `@Profile({"dev", "prod", "local"})`. 문서의 "Active profile은 비워둔다" 설명과 일치하는지 확인.

- [ ] **Step 4: 불일치 발견 시 Task 1 문서를 직접 수정하고 이 Step들을 다시 확인한다.** 전부 일치하면 완료.

- [ ] **Step 5: 파일 저장 확인만 하고 커밋은 하지 않는다**
