# ADR-0008: auth 분리를 취소하고, gateway 서명 검증 + user-service forward-auth 중앙 인가로 전환한다

- 상태: accepted
- 날짜: 2026-07-10
- 대체(삭제됨): ADR-0002(stateless JWT gateway 검증 + Redis 블랙리스트), ADR-0003(auth-service 물리 분리), `auth-split-action-items.md`
- 관련: `apigateway/src/main/java/com/prompthub/apigateway/filter/ForwardAuthFilter.java`
(2026-07-22 갱신 — 작성 당시 `UserHeaderFilter.java`에서 개명됨),
`apigateway/src/main/java/com/prompthub/apigateway/config/WhitelistPathResolver.java`,
`apigateway/src/main/java/com/prompthub/apigateway/route/VersionedServiceRoute.java`,
`user-service/src/main/java/com/prompthub/user/auth/**`, ADR-0004(중앙 설정), ADR-0007(버전 라우팅),
`user-service/docs/frontend-notice-kakao-login-change.md`(프런트 공지 — RTR 배포 순서 위험 상세),
`user-service/docs/session-revocation-review.md`(로그아웃 세션 즉시 무효화 검토 — 결정 8-1의 근거)

## 컨텍스트

원래 결정은 두 갈래였다: auth-service를 물리 분리하고 로그인 시 gRPC로 user-service와
결합하며(구 ADR-0003), 검증은 gateway가 stateless로 수행하되 실시간 차단은 Redis
블랙리스트로 보강한다(구 ADR-0002). 2026-07-10 구현 착수 전 설계 검증에서 아래 사실들이
겹치며 두 결정이 모두 뒤집혔다.

1. **개발서버 메모리 제약.** JVM 프로세스를 하나 더 띄울 여유가 없어 물리 분리 자체가
 취소되었다. (구 ADR-0003이 Node 재작성을 기각하며 처방했던 힙 상한 튜닝은 여전히 유효하다.)
2. **팀 아키텍처 방침 전환.** 도메인 서비스(product·order·payment·settlement·admin)는
 인가 코드 없이 비즈니스 로직만 수행하고, 인가는 요청 경로에서 중앙 처리한다.
 gateway는 Redis 등 상태 저장소 의존 없이 순수하게 유지한다.
3. **JWT 클레임 축소 결정.** 클레임은 `sub`(uuid)만 남긴다. role 클레임은 발급 시점
 스냅샷이라 최대 AT TTL(15분)만큼 낡는 문제(판매자 승급 반영 지연)가 있었다.
4. **RT 저장 재검토(강사 피드백).** RT를 Redis 단독 저장하면 Redis 유실 시 전 활성 유저가
 AT 만료 시점부터 일제히 재로그인으로 몰려(로그인 쇄도) 인증 경로가 연쇄 붕괴할 수 있다.
 설계 판단 기준은 단일 EC2 현실이 아니라 실무(컴포넌트별 장애 도메인 독립)로 한다.
5. **검증 중 발견된 보안 결함.** 현행 OAuth 로그인은 프런트가 카카오 SDK로 조회한
 oauthId·email을 body로 전달하면 백엔드가 검증 없이 신뢰한다(사실상 자기신고제).
 타인의 카카오 ID를 POST하는 것만으로 해당 계정 토큰이 발급된다.

## 결정

### 1. 배치 — auth-service를 만들지 않는다

인증(카카오 로그인·재발급·로그아웃)과 인가 데이터는 전부 user-service 소유다.
`auth`·`refresh_token` 테이블은 user_service 영역에 남고(스키마 분리는 ADR-0002-schema
원안 5개 그대로 추후 진행), RS256 private key도 user-service만 보유한다.

### 2. 토큰 — 클레임은 uuid+epoch, AT 15분 / RT 7일

- 클레임: `sub`(uuid) + `epoch`(세션 버전, RT 발급마다 1씩 증가하는 단조 값). 기존
`status`·`role` 클레임은 제거한다. 신선도가 필요한 값(role/status)은 토큰에 굽지 않는다 —
매 요청 forward-auth가 원본에서 읽는다(결정 5). `epoch`는 "sub만" 원칙의 예외다 —
발급 시점 스냅샷이 아니라 "이 토큰이 몇 번째 세션인지"를 가리키는 단조 증가 값이라
role/status와 달리 stale 문제가 없다(결정 8-1).
- RTR(재발급마다 RT 교체) + 재사용 감지: 제시된 RT의 서명은 유효한데 저장된 현재 RT와
다르면 회전 폐기된 토큰의 재사용(탈취 시나리오)으로 판정하고 해당 유저 세션 전체를
무효화한다. 현재 토큰 1개 비교만으로 판정 가능하므로 이력 테이블은 두지 않는다.

### 3. RT 저장 — RDB 원본 + Redis 캐시

- **RDB(`refresh_token` 테이블) = 진실의 원천.** 발급·회전·폐기가 반드시 기록된다.
- **Redis = 조회 캐시.** 재발급 검증은 Redis 먼저, miss면 RDB 조회 후 재적재.
- 회전 시 쓰기 순서: RDB 갱신 → Redis 갱신. 두 저장소가 어긋나면 RDB가 이긴다.
- 근거: Redis 컨테이너 유실 시에도 재발급이 RDB로 동작해 전 유저 재로그인 쇄도가
발생하지 않는다. Redis에는 AOF(`appendonly yes`) + 볼륨 마운트를 적용한다.

### 4. OAuth — 서버 측 카카오 검증 (보안 결함 봉인, 최우선 구현)

프런트는 카카오 access token(또는 인가 코드)만 전달한다. user-service가 서버 측에서
카카오 API(`kapi.kakao.com`)를 호출해 oauthId·email을 직접 획득한다. 클라이언트가
주장하는 신원 정보는 어떤 경우에도 신뢰하지 않는다. 로그인의 User 생성 + Auth 연동
기록은 단일 로컬 트랜잭션이다(분리 취소로 분산 부분 실패 문제 자체가 소멸).

### 5. 인가 — forward-auth (요청 흐름의 핵심)

```
[공개 경로]   클라이언트 → gateway(화이트리스트) → 대상 서비스        # authorize 생략
[인증 경로]   클라이언트 → gateway: ① JWT 서명 검증(public key, uuid+epoch 추출)
                → ② user-service authorize(userId, epoch) 내부 호출 → {status, role}
                → ②-1 저장된 현재 RT epoch과 불일치하면 401(세션 무효, fail-closed, 결정 8-1)
                → ③ status ≠ ACTIVE면 거부
                → ④ gateway 정책표와 role 대조 (예: /admin/** → ADMIN)
                → ⑤ X-User-Id 주입 후 대상 서비스로 라우팅
[로그인/재발급] 화이트리스트로 forward-auth 제외, user-service로 직접 라우팅
              # 검사할 유효 AT가 없는 요청이므로 (로그인: 토큰 없음 / 재발급: AT 만료)
```

- **정책표(경로→필요 role)는 gateway 소유다.** 경로 지식은 라우터의 것이며, 라우트
등록표(`VersionedServiceRoute`)와 같은 곳에서 관리된다.
- 정책표는 `@ConfigurationProperties`(예: `gateway.route-policies`)로 외부화한다.
지금은 config server의 `configs/apigateway.yml`, 추후 k8s ConfigMap으로 YAML 블록을
그대로 이동 가능하다(ADR-0007의 `gateway.api-versions`와 동일 패턴). 정책 변경 반영은
재기동(롤링)으로 한다.
- 단, `**/admin/**` → ADMIN 캐치올은 설정이 아니라 코드 기본값**으로 박는다. 설정 파일
오류로 가장 위험한 보호선이 조용히 사라지는 것을 막는다. 기동 시 정책표가 비었거나
파싱 불가면 fail-fast로 기동을 중단한다.
- 알려진 정책표 요구(작성 시점): `/admin/**`→ADMIN, `/sellers/me/**`→SELLER,
`POST /seller/register`→BUYER.
- gateway→authorize 호출은 짧은 타임아웃 + **fail-closed(503)**. 인가는 값이 없으면
통과시킬 수 없으므로 fail-open이 불가능하다.

### 6. authorize 캐시 — user-service 내부 Redis

- 키: `user:authz:{userId}` = `{status, role}`. **원본은 user 테이블, Redis는 캐시.**
- 적재: 로그인 시 + authorize miss 시 lazy 적재(DB PK 조회 후).
- 무효화: 정지·탈퇴·승급 등 **상태 전이 도메인 메서드(`user.block()` 등) 지점에서 즉시**.
쓰기와 무효화가 모두 user-service 안이므로 분산 캐시 무효화 문제가 없다.
- TTL 60초: 무효화가 우회되는 경로(아래 결정 8의 admin 직접 UPDATE 등)의 staleness 상한.
- Redis 장애 시 DB 직접 조회로 폴백(fail-soft) — 인가는 느려질 뿐 계속 동작한다.

### 7. X-User-Role 헤더 — 전면 삭제 대신 user-service 필요분만 유지 (최종 결정)

- 당초 계획은 role 판정이 gateway 정책표에서 끝나니 `X-User-Id`만 주입하고
`X-User-Role`은 다운스트림에 전달할 필요가 없다는 것이었다. 그러나 user-service는
자체 로직상 role 정보가 필요해 이 헤더에 의존한다 — 그래서 완전 삭제 대신 **gateway가
`X-User-Id`와 `X-User-Role`을 계속 함께 주입하는 것으로 최종 결정**했다.
- product·payment·order·settlement는 컨트롤러의 `@RequestHeader("X-User-Role")`과 내부
role 분기를 이미 제거해 이 헤더를 소비하지 않는다(order·settlement의 `AuthHeaders`
상수도 `USER_ID`만 남음). 헤더가 전달돼도 무해하므로 이 서비스들 쪽에서 추가로
정리할 것은 없다.
- **소유권 검사는 인가가 아니라 비즈니스 규칙이므로 각 서비스에 남는다.**
"이 wishlist/주문이 X-User-Id의 것인가"는 서비스가 계속 검사한다.
합의 문구: **"gateway = role/status 확인, 서비스 = 소유권 확인(X-User-Id 기반)"**.

### 8. 세션 폐기 — 상태 전이 지점 앵커링

- 정지·탈퇴 시: RT 삭제(RDB+Redis) + authorize 캐시 무효화. 이 부수효과는 진입점
(컨트롤러)이 아니라 **user-service의 상태 전이 도메인 메서드에** 붙인다. 호출자가
누구든(현 admin API, 추후 admin-service, 본인 탈퇴) 상태가 바뀌는 지점은 하나이므로
누락이 구조적으로 불가능하다.
- **admin-service 계약:** admin-service는 팀 정책상 전 스키마 읽기·쓰기가 가능하지만,
**user의 status·role 변경만은 반드시 user-service API를 경유한다.** 직접 UPDATE는
세션 폐기와 캐시 무효화를 우회한다(그 경우에도 staleness는 캐시 TTL 60초로 유계).
직접 SQL은 조회·통계·데이터 보정 용도로 한정한다.

#### 8-1. 일반 로그아웃 — epoch 비교로 AT 즉시 무효화

- 로그아웃은 지금처럼 RT row만 삭제한다(신규 저장소 없음). RTR(결정 2)에서 "저장된 현재
RT"는 유저당 1개만 추적되므로, AT에 실린 `epoch`이 로그아웃 시점에 자동으로 무효가
된다 — 별도의 거부 목록(블랙리스트)을 새로 만들 필요 없이 **기존 RT 상태를 재사용**한다.
- Gateway는 상태 없음을 유지한다. epoch 비교는 이미 상태를 가진 user-service의
authorize() 안에서 수행한다(§5 ②-1). 신규 네트워크 홉 없음 — 기존 authorize 왕복에
검사 하나를 얹는 것뿐이다.
- epoch 불일치는 **fail-closed(401)**다. 인가는 값이 없으면 통과시킬 수 없다는 결정 5의
논리를 그대로 따른다.
- 즉시성: 다음 요청(authorize 호출 시점)부터 반영된다. 다른 탭·유출된 AT 사본도 이
시점부터 차단된다.
- 전제: "유저당 활성 세션 1개." 여러 기기 동시 로그인을 지원하게 되면 이 전제가 깨지고,
`epoch`은 유저 단위가 아니라 세션(디바이스) 단위로 재설계해야 한다. 그 시점에 다시
검토한다 — 지금 미리 대비하지 않는다.

### 9. 범위 제외 (명시적 미채택)

- **블랙리스트(구 ADR-0002)**: 폐기. 정지·탈퇴 차단은 forward-auth가 user.status를
원본에서 직접 확인해 해결하고(다음 요청부터 반영), 로그아웃 세션 즉시 무효화는
결정 8-1의 epoch 비교로 해결한다. 두 문제 모두 "거부 목록"을 새로 만들지 않고 이미
있는 상태(user.status, RT epoch)를 재사용해 풀리므로, 블랙리스트 자체가 완전히
불필요해졌다. 여러 기기 동시 로그인 지원 시 재검토(결정 8-1 전제 참고).
- **이메일 가입/로그인(`/auth/signup`, `/auth/login`)**: 이번 범위에서 제외.
단 **비밀번호는 인증 도메인 소유**로 선언한다 — 구현 시 credential은 user 프로필이
아니라 인증 영역에 둔다. `PATCH /users/me`의 password 필드 스펙은 그때 재결정한다.
- **민감 쓰기 경로(결제 승인·정산 실행)의 동기 사용자 확인**: 미구현. 모든 요청이
forward-auth를 통과하므로 도착 시점에 status 확인이 끝나 있다.
재검토 트리거: 실결제 전환.
- **Flyway**: 스키마 분리(ADR-0002-schema) 이후로 연기 — ADR-0003-flyway에 기록.

## 기각된 대안 (재론 방지)


| 대안                                         | 기각 사유                                                                       | 재검토 트리거               |
| ------------------------------------------ | --------------------------------------------------------------------------- | --------------------- |
| auth-service 물리 분리 + gRPC 결합 (구 ADR-0003)  | 개발서버 메모리 제약. 로그인 upsert의 분산 부분 실패(벽돌 계정) 처리 복잡도                             | 인프라 확장 + 실측 인증 트래픽 병목 |
| gateway가 Redis를 직접 읽는 중앙 인가(블랙리스트/role 캐시) | gateway를 상태 저장소 의존 없이 순수하게 유지하는 팀 방침. 로그아웃 세션 무효화도 결정 8-1(epoch 비교)로 대체 해결됨 | 여러 기기 동시 로그인 지원 시     |
| 서비스별 인가 필터(security-starter 모듈)            | 도메인 서비스는 인가 코드 없이 경량 유지하는 팀 방침                                              | —                     |
| 클레임에 role 유지                               | 발급 시점 스냅샷이라 최대 15분 stale — 승급 반영 지연                                         | —                     |
| Node.js 재작성 (구 ADR-0003에서 기각, 승계)          | 메모리 우려의 해법은 힙 상한(`-Xmx`)                                                    | JVM 튜닝으로 해소 불가한 실측 병목 |
| 매 요청 인가를 각 도메인 서비스가 자체 수행(resource server) | 서비스별 설정·구현 중복, 필터 누락 서비스 = 차단 구멍                                            | —                     |


## 결과 — 수용한 리스크 (팀 인지 필수)

1. **user-service가 전 인증 트래픽의 동기 의존점이다.** user-service 장애·재기동 시
 공개 경로를 제외한 모든 API가 중단된다. develop 머지 = 즉시 배포 환경이므로
 **user-service를 배포할 때마다 재기동 시간만큼 전 인증 API가 끊긴다.**
 개발 환경 단일 인스턴스에서 수용한다. **운영 전환 시 user-service 다중 인스턴스 필수.**
2. gateway 자체의 SPOF 성질은 인가 배치와 무관하다(유일한 진입점으로서 라우팅·서명
 검증을 계속 수행). 해법은 로직 이동이 아니라 수평 확장이다.
3. 정지·탈퇴·승급은 다음 요청부터 반영된다(캐시 staleness ≤60초).
4. 매 인증 요청에 내부 왕복 1회(수 ms)가 추가된다.
5. 로그아웃은 다음 요청(authorize 호출 시점)부터 즉시 반영된다(결정 8-1, epoch 비교).
 기존 "AT는 만료까지 최대 15분 유효" 리스크는 제거됨.
6. **RTR(결정 2) 배포는 백엔드·프런트 동시 배포가 강제된다.** 현재 `/auth/token/refresh`
 응답은 `accessToken`만 반환하고 RT는 최초 로그인 시 저장한 값을 재사용하는 구조라,
 프런트에는 "재발급 응답으로 받은 새 RT로 기존 저장값을 교체" 로직이 없다(만들 필요가
 없었으므로). RTR을 백엔드에 배포하는 순간부터 재발급마다 RT가 회전되므로, 프런트가
 교체 로직을 같은 배포 창에 함께 내지 않으면 다음 재발급 사이클에서 프런트가 여전히
 구(舊) RT를 보내고, 백엔드는 이를 재사용(탈취)으로 오판해 해당 유저 세션을 전체
 무효화한다 — **재현 조건이 아니라 배포 즉시 전 유저에게 결정적으로 발생한다.**
 완화책 없음(그레이스 기간·구버전 RT 1회 허용 등은 미채택) — 배포 순서 자체로 막는다.

## 액션 아이템 (2026-08-13 갱신 — 코드 재확인 + 사용자 확인)

구현 (user-service·gateway 담당):

- [x] **카카오 서버 측 검증** — `KakaoUserInfoClientAdapter` 구현 완료 (결정 4)
- [x] 클레임 uuid+epoch + AT 15분 / RT 7일 + RTR·재사용 감지 + RT Redis 캐시 (결정 2·3)
- [x] authorize 내부 API + `user:authz:{userId}` 캐시·무효화 (결정 6) — `AuthorizeApplicationService`, `AuthorizeController`
- [x] authorize(userId, epoch) — 저장된 현재 RT epoch과 비교, 불일치 시 401 fail-closed (결정 8-1)
- [x] gateway forward-auth 필터 — `ForwardAuthFilter`로 구현 (결정 5)
- [x] gateway 정책표(`RoutePolicyResolver`/`GatewayRoutePolicyProperties`) + admin 캐치올 + fail-fast (결정 5)
- [x] gateway 라우트에서 `/internal/orders/**` 제거 — `VersionedServiceRoute` (팀 확인 완료)
- [x] 세션 폐기 앵커링 — `DELETE /users/me`(`UserController`) 구현 완료. `UserApplicationService.withdraw()`가
  `user.withdraw()` 상태 전이 후 `sessionRevocationUseCase.revoke(userId)`로 RT 삭제·authorize 캐시
  무효화까지 수행한다 (결정 8)
- [x] **결정 7 "X-User-Role 헤더" — 완료(최종 결정: 전면 삭제 대신 유지).** user-service가
  role 정보를 필요로 해 gateway가 `X-User-Role`을 계속 주입하는 것으로 확정됨. 나머지
  product·order·payment·settlement는 이미 이 헤더를 소비하지 않아(`order-service`의
  `AuthHeaders`는 `USER_ID`만 남음) 추가로 정리할 것이 없다.

외부 공지·확인:

- [x] **프런트(브레이킹)**: 카카오 로그인 전달 방식 변경(사용자 정보 → 토큰), RTR 새 RT로 저장값
  교체 로직까지 프런트 반영 완료(2026-08-13, 사용자 확인).
- [x] **product·payment·order·settlement**: X-User-Role 제거 — 위 결정 7 항목과 동일 근거로 완료 확인.
- [x] **인프라**: product 8082 포트 외부 비노출 — `docker-compose.yml`에서 `127.0.0.1:8082:8082`
  loopback 바인딩 확인됨. Redis AOF + 볼륨 마운트 — 같은 파일의 `redis` 서비스에 `--appendonly yes` +
  `redis-data` 볼륨으로 확인됨.
- [ ] **인프라(신규 확인 필요)**: 위 포트 격리·AOF 설정은 `docker-compose.yml`(로컬용) 기준이다.
  배포가 이후 self-hosted Kubernetes로 전환됐으므로(`docs/architecture/kubernetes.md`), 같은
  격리·영속화가 `k8s/` 매니페스트에도 동일하게 있는지는 별도로 확인해야 한다.
- [ ] **팀 합의문**: "gateway = role/status 확인, 서비스 = 소유권 확인" 문구 반영 — 코드로 확인 불가한
  항목, 미확인 유지.

