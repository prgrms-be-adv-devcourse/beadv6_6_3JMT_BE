# notification-service PostgreSQL rollout

notification-service는 `notification_service` 전용 PostgreSQL schema와 같은 이름의 login
role을 사용한다. Kubernetes 배포 전에 DB 계약을 먼저 준비하지 않으면 Postgres Pod가
`CreateContainerConfigError`가 되거나 notification-service의 Flyway가 연결에 실패한다.

## 적용 순서

1. Medium의 실제 `postgres-secret` 파일에 `NOTIFICATION_SERVICE_PASSWORD`를 추가하고
   권한 `600`을 유지한다. 저장소의 example 값이나 개발용 기본 비밀번호를 사용하지 않는다.
2. Secret을 적용한 뒤 PostgreSQL StatefulSet이 해당 key를 환경변수로 읽을 수 있는지
   운영자가 확인한다. key가 없는 상태에서 StatefulSet manifest를 먼저 적용하면 Postgres가
   시작하지 않는다.
   애플리케이션 Release workflow는 이 단계 전에 모든 `secretKeyRef`의 key 존재 여부를
   자동 검증한다. `postgres-secret.NOTIFICATION_SERVICE_PASSWORD`도 검사 대상이며,
   검증기는 Secret 값을 읽거나 로그에 출력하지 않는다.
   ```bash
   kubectl get secret postgres-secret -n prompthub -o json \
     | jq -e '.data | has("NOTIFICATION_SERVICE_PASSWORD")' >/dev/null
   ```
3. 기존 PostgreSQL PVC를 사용 중이면, Postgres 관리자 계정으로 아래 계약을 한 번
   반영한다. init ConfigMap은 데이터 디렉터리가 비어 있을 때만 실행되므로 기존 PVC에는
   적용되지 않는다.
   - `notification_service` schema 생성 및 소유자를 `notification_service`로 변경
   - `notification_service` login role 생성 또는 실제 Secret 값으로 비밀번호 갱신
   - `public`과 다른 서비스 schema 접근 회수
   - notification schema의 `USAGE`, `CREATE`, 기존 table/sequence 및 기본 권한 부여
   - role `search_path`를 `notification_service, public`으로 설정
4. 위 DB 준비가 끝난 뒤 notification-service가 포함된 Release workflow를 실행한다. 이
   workflow는 `release-services=notification-service`,
   `manifest-services=notification-service`, `confirmation=RELEASE`로 수동 실행할 수
   있으며, notification-service 이미지를 발행하고 그 digest를 Deployment에 주입한다.
5. rollout 후 `notification_service` schema에 Flyway history와 notification 테이블이
   생성됐는지, Pod가 Config Server·Eureka·Kafka·Redis에 연결됐는지 확인한다.

   ```bash
   kubectl rollout status deployment/notification-service -n prompthub --timeout=10m
   kubectl get pods -n prompthub -l app.kubernetes.io/name=notification-service
   ```

신규 빈 PVC에서는 `k8s/base/infrastructure/postgres/init-configmap.yaml`이 같은 계약을
자동 생성한다. Docker 개발 환경은 `docker-entrypoint-initdb.d/01-init-schemas-and-roles.sh`
에서 동일한 schema·role 계약을 제공한다.
