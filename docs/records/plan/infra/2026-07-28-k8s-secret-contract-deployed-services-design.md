# 배포 서비스 기준 Kubernetes Secret 계약 설계

## 목표

Kubernetes Secret 계약 검증은 `k8s/base/services/kustomization.yaml`을 통해
현재 배포되는 서비스가 사용하는 Config Server 프로필만 검증한다. 이를 통해
아직 배포하지 않은 미래 서비스의 프로필이 사용하지 않는 Secret key 때문에
배포를 막는 문제를 방지하면서, 배포 대상 서비스의 누락 key는 CI 실패로
계속 감지한다.

## 배경

`notification-service.yml`은 `${NOTIFICATION_SERVICE_PASSWORD}`를 선언하고,
현재 `notification`은 애플리케이션 서비스 Kustomization에 포함되어 있으며
Deployment와 PostgreSQL `secretKeyRef` 소비자가 있다. 이전 검증기는 모든
Config Server 프로필을 스캔했기 때문에, 배포 목록에 없는 미래 프로필도
배포를 차단할 수 있었다. notification-service는 실제 배포 대상이므로
예시 Secret에 해당 key를 계속 유지해야 하며, Kustomization 밖의 프로필만
placeholder 검사에서 제외해야 한다.

## 설계

### 배포 기준 정보

`k8s/base/services/kustomization.yaml`을 Kubernetes 애플리케이션 배포 서비스의
단일 기준으로 사용한다. 검증기는 이 파일의 직접 `resources` 항목을 읽고
각 서비스 디렉터리 이름에 `-service`를 붙여 Config Server 프로필 이름을
만든다.

현재 Kustomization의 `user`, `product`, `order`, `payment`, `settlement`,
`admin`, `ai`, `notification`은 각각 대응하는 `*-service.yml` 프로필로
변환된다. 공통 `configs/application.yml`은 모든 배포 Config Client가
사용하므로 항상 검증 범위에 포함한다.

### Secret 계약 동작

검증기는 공통 프로필과 Kustomization에서 도출한 배포 서비스 프로필에서만
`${대문자_ENV_KEY}` placeholder를 추출한다. 추출된 key는
`k8s/templates/runtime-values.example.yaml`에 있어야 하며, 예시 key가
실제 manifest 소비자와 연결되는지와 승인된 비-Config key인지 확인하는
기존 검증도 유지한다.

Kustomization에 없는 프로필은 자동으로 제외한다. 현재 `notification`은
목록에 있으므로 `notification-service.yml`과
`NOTIFICATION_SERVICE_PASSWORD`는 계속 필수 검증 대상이다. 향후 서비스가
Kustomization에 추가되면 대응 프로필과 placeholder도 별도 allowlist 없이
자동으로 필수 항목이 된다.

### 오류 처리

Kustomization에 등록된 서비스와 일치하는 Config Server 프로필이 없으면
검증기는 명확한 오류 메시지로 실패한다. 이를 통해 배포 서비스 디렉터리와
Config 계약 사이의 이름 불일치를 조기에 발견한다.

## 테스트

실제 검증기를 임시 fixture에 복사해 다음 동작을 확인한다.

1. Kustomization에 없는 프로필에 고유 placeholder가 있어도 검증이 성공한다.
2. 배포 프로필에 필요한 예시 key를 제거하면 기존 누락 key 오류로 실패한다.
3. 서비스를 Kustomization에 추가하면 해당 고유 placeholder가 자동으로
   필수 항목이 된다.

전체 매니페스트 검증도 실행해 변경된 Secret 계약이 CI 진입점과 연동되는지
확인한다.

## 문서

Kubernetes 운영 문서에는 Config Server placeholder 검증 범위가 Config Server가
제공할 수 있는 모든 프로필이 아니라, 현재 서비스 Kustomization에 등록된
프로필이라는 점을 명시한다.

## 범위 제외

- notification-service의 기존 Kubernetes 배포 연결을 변경하지 않는다.
- 운영 Secret 값을 저장소에 추가하지 않는다.
- 런타임 자격 증명, Config Server 동작, 기타 매니페스트 계약은 변경하지 않는다.
