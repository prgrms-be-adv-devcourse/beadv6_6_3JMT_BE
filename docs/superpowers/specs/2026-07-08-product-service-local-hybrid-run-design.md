# product-service 로컬 단독 실행 + 나머지 AWS 하이브리드 런북 설계

## 배경 / 목적

product-service 코드를 수정할 때마다 config/discovery/apigateway/user-service/order-service/payment-service/settlement-service를 전부 로컬에 띄우지 않고, **product-service만 로컬로 돌리고 나머지는 AWS 배포본을 그대로 사용**하면서 실제 흐름을 검증하고 싶다. 이 세션에서 시행착오로 이미 필요한 조각을 다 찾았고, 이를 반복 가능한 런북 문서로 정리하는 것이 목적이다. 코드 변경은 없다 — 전부 기존 설정값 조합으로 해결된다.

## 왜 코드 변경이 필요 없는가 (이미 확인된 사실)

- **gRPC(판매자 정보 조회)**: product-service에는 이미 `GrpcSellerClientAdapter`(`@Profile({"dev","prod","local"})`, 실제 gRPC)와 `StubSellerClient`(`@Profile({"default","test"})`, 고정값 `"테스트판매자"`)가 둘 다 존재한다. Active profile을 비워두면(=`default`) 자동으로 스텁이 붙어서, AWS user-service의 gRPC 포트가 로컬에서 안 닿는 문제를 코드 수정 없이 우회한다.
- **Eureka 등록**: 루트 `build.gradle`이 전 서비스에 `spring-cloud-starter-netflix-eureka-client`를 적용해서, product-service도 기동 시 자동으로 `localhost:8761`(기본값)에 등록을 시도한다. 로컬에 discovery-service가 없으면 `Connection refused`로 실패한다. 이번 시나리오는 게이트웨이/디스커버리를 아예 안 거치므로(FE가 product-service를 직접 호출) Eureka 등록 자체가 필요 없다 — `product-service/.env`에 `EUREKA_CLIENT_ENABLED=false` 한 줄만 추가하면 된다.
- **DB/Kafka**: 로컬에 이미 떠 있다. Postgres는 `product-service/.env`가 가리키는 `localhost:5433`(기존 pgvector 컨테이너)에 이미 연결 정보가 있고, Kafka는 `product-service/docker-compose.yml`의 `product-kafka-dev` 컨테이너가 9092 포트로 이미 실행 중이다.
- **FE 라우팅**: FE(`beadv6_6_3JMT_FE`)에 이미 구현된 `lib/directRouting.ts`(`NEXT_PUBLIC_LOCAL_PROXY_PATHS`/`NEXT_PUBLIC_LOCAL_PROXY_TARGET` 기반 범용 로컬 우회)를 그대로 쓴다. product-service 경로만 지정하면 나머지는 자동으로 AWS 게이트웨이로 간다.

## 결과물

- `product-service/docs/local-hybrid-run.md` 런북 문서 1개 (BE 설정 + FE 설정을 순서대로 한 문서에 담음)
- 코드/설정 파일 변경 없음. 문서만 신규 작성.

## 문서 구성

1. **목적/알려진 제약 요약**: 왜 이렇게 하는지 한 문단 + gRPC(스텁 고정값)·Kafka(AWS와 무관) 제약을 맨 위에 명시해서 오해 방지
2. **사전 준비물**: 로컬 Postgres(5433, 이미 떠 있는 pgvector 컨테이너 재사용 또는 본인 로컬 Postgres), `product-service/docker-compose.yml`의 Kafka(`docker compose up -d product-kafka` 등), `product-service/.env` 파일 존재 확인
3. **BE: product-service 실행**
   - `product-service/.env`에 `EUREKA_CLIENT_ENABLED=false` 추가
   - Active profile은 비워둠(default) — 이유(StubSellerClient) 명시
   - IntelliJ Gradle Run Configuration 생성 절차(Gradle project: `product-service`, Tasks: `bootRun`) 또는 터미널 명령(`gradlew.bat :product-service:bootRun`)
   - 기동 성공 로그 예시(`Started ProductApplication`, `gRPC server started on port 9082`)
4. **FE: 로컬 product-service만 바라보기**
   - `beadv6_6_3JMT_FE/.env.local`에 추가할 값 예시:
     ```
     NEXT_PUBLIC_LOCAL_PROXY_PATHS=/api/v1/products,/api/v1/sellers/me/products,/api/v1/admin/products
     NEXT_PUBLIC_LOCAL_PROXY_TARGET=http://localhost:8082
     ```
   - 이 목록에 없는 `/api/v1/*` 경로는 전부 기존처럼 `NEXT_PUBLIC_API_URL`(AWS 게이트웨이)로 감을 명시
5. **동작 확인**: `npm run dev` 후 브라우저에서 `/browse`, `/detail/[id]` 접속 → 정상 응답 확인. 개발자도구 Network 탭에서 해당 요청이 `localhost:8082`로 나가는지, 그 외 요청(`/api/v1/orders` 등)은 여전히 AWS로 나가는지 확인
6. **알려진 제약 (재정리, 트러블슈팅용)**
   - 판매자 닉네임/프로필 이미지가 실제 값이 아니라 "테스트판매자"로 고정 표시됨 (StubSellerClient)
   - product-service가 발행하는 Kafka 이벤트(`product-events`)는 로컬 Kafka로만 가서 AWS의 user-service가 못 받음 — 찜 목록 등 이벤트 기반 캐시 갱신은 검증 안 됨
   - 이 문서는 mock 인프라(`NEXT_PUBLIC_API_MOCKING`)와는 무관 — 필요시 `disabled` 유지

## 스코프 경계

- 다른 서비스(user/order/payment/settlement-service)를 같은 방식으로 로컬 단독 실행하는 방법은 이번 문서에 포함하지 않는다. 필요해지면 같은 패턴(profile 비우기/gRPC stub 유무 확인/Eureka 끄기)을 참고해 별도로 정리한다.
- gRPC SSH 터널(실제 판매자 데이터로 테스트) 설정은 인프라 담당자 협조가 필요한 별도 작업이라 이번 문서 범위 밖이다 — "알려진 제약"으로만 언급한다.
