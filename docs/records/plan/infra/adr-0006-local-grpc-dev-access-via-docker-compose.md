# ADR-0006: 로컬 개발용 gRPC 접근은 docker-compose 포트 노출로 해결한다 (Gateway 코드 변경 없음)

- 상태: accepted
- 날짜: 2026-07-09
- 관련: 루트 `docker-compose.yml` (user/product/order/payment/settlement gRPC 포트), 티켓 "[Gateway] 내부 gRPC 접근용 Local Dev Proxy"

## 컨텍스트

개발자가 로컬에서 gRPC 관련 코드를 수정한 서비스를 테스트하려면, 개인 PC에 나머지 서비스를 전부
띄우는 부담 없이 개발 AWS 인스턴스에 이미 떠 있는 다른 서비스의 gRPC 엔드포인트를 호출해서 실제
응답 데이터까지 받아야 한다. 티켓은 "[Gateway]" 라벨을 달고 있어 apigateway 안에 프록시 기능을
추가하는 작업처럼 보였으나, 검토 과정에서 다음이 확인됐다.

- gRPC 서버 포트(user=9081, product=9082, order=9083, settlement=9085, payment=9084 예정)는
  `docker-compose.yml`에 `ports:` 매핑이 없어 컨테이너 내부망(`prompthub` 네트워크)에서만 통신
  가능하고, 호스트에도 인터넷에도 노출되지 않는다.
- 반면 HTTP 헬스체크 포트와 Postgres는 이미 `127.0.0.1:PORT:PORT` 형태로 loopback에 노출되어
  있고, "SSH 터널로만 접근, 외부 차단"이라는 관례가 자리잡혀 있다.
- AWS 보안그룹을 gRPC 포트에 대해 직접 여는 방법은 (a) docker 포트 퍼블리시 없이는 트래픽이
  컨테이너까지 도달하지 못해 기술적으로 무의미하고, (b) 이 gRPC 통신이 TLS 없는 plaintext이자
  게이트웨이의 JWT 인증을 거치지 않는 내부 전용 채널이라 인터넷에 직접 노출하면 인증 없이 내부
  API를 호출할 수 있게 되는 보안 문제가 있어 기각했다.
- apigateway(Spring Cloud Gateway, WebFlux) 안에 TCP 레벨 포워딩 리스너를 새로 구현하는 방법도
  검토했다. 대상 서비스 4~5개 대신 apigateway 컨테이너 1개만 재기동하면 된다는 이점이 있었지만,
  팀이 곧(시점 미확정) 쿠버네티스로 전환할 예정이라 이 코드는 전환 이후 `kubectl port-forward`로
  대체되어 버려질 가능성이 높고, 신규 코드 작성·유지 비용을 정당화하기 어렵다고 판단해 기각했다.

## 결정

**로컬 개발용 gRPC 접근은 apigateway 코드를 건드리지 않고, 각 서비스의 `docker-compose.yml`
항목에 기존 HTTP 헬스체크/Postgres와 동일한 `127.0.0.1:PORT:PORT` loopback 노출 패턴을 그대로
적용해서 해결한다.**

- 대상: user(9081), product(9082), order(9083), payment(9084), settlement(9085). payment는
  gRPC 서버 코드가 아직 없어 매핑만 선반영하고, 실제 서버가 붙기 전까지는 도달/응답 검증
  대상에서 제외한다.
- 개발자는 SSH(pem 키)로 인스턴스에 접속 가능하다는 전제 하에, 원격과 동일한 포트 번호로 SSH
  로컬 포트포워딩(`ssh -L PORT:127.0.0.1:PORT ...`)을 건다. 각 서비스의 `application-local.yml`에
  이미 `address: 'static://localhost:PORT'` 형태로 설정돼 있어, 포트 번호를 동일하게 맞추면
  애플리케이션 코드·설정 변경이 전혀 필요 없다.
- 반대 방향(AWS 인스턴스의 서비스가 로컬 PC로 콜백하는 것)은 스코프에서 제외한다.

## 결과

- docker-compose 포트 매핑은 컨테이너 생성 시점에 고정되므로, 이 5개 서비스 컨테이너를
  재기동해야 반영된다. 공유 개발 인스턴스이므로 반영 시점에 다른 팀원 작업에 일시적 영향을
  줄 수 있다.
- 티켓 라벨은 "[Gateway]"이지만 실제 변경은 `docker-compose.yml`에만 있고 `apigateway/` 모듈
  코드는 변경되지 않는다. 이후 리뷰어가 diff를 보고 의아해하지 않도록 이 ADR을 참조점으로
  남긴다.
- 쿠버네티스 전환이 이뤄지면 이 방식은 `kubectl port-forward` 기반 접근으로 대체될 가능성이
  높다. (재검토 트리거: 쿠버네티스 전환 착수)
- payment-service의 gRPC 서버 구현이 머지되면, 9084 포트에 대한 실제 도달·응답 검증을 후속으로
  진행한다.

## 현황 업데이트 (2026-07-22)

- **재검토 트리거가 이미 발동함**: `.github/workflows/cd-selfhosted-kubernetes.yml`이
  `push: [develop]`로 활성화되어 Kubernetes 전환이 실제로 시작됐다(`cd-selfhosted-compose.yml`의
  develop 트리거는 주석 처리되어 수동 실행으로만 남음). 로컬 gRPC 접근 방식을
  `kubectl port-forward`로 옮길지 이 ADR을 다시 열어 결정할 시점.
- **실제 gRPC 서버 보유 현황 정정**: 이 문서는 "payment만 서버 코드가 아직 없다"고 서술하지만,
  실제로는 **order·product만 실제 gRPC 서버를 띄운다.** user-service는 gRPC를 완전히
  제거했고(ADR-0001 업데이트 참조), settlement·payment는 클라이언트(`spring-boot-starter-grpc-client`)만
  가지고 서버는 없다. 즉 9081(user)·9084(payment)·9085(settlement) 포트 매핑은 현재 전부
  "받는 서버가 없는" 죽은 매핑이고, 실제로 살아있는 건 9082(product)·9083(order)뿐이다.
