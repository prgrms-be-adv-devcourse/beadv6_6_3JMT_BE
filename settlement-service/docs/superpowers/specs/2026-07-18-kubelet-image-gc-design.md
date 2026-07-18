# Kubernetes 노드 이미지 GC 기준 조정 설계

- 작성일: 2026-07-18
- 관련 이슈: #403 `[FEATURE] Kubernetes 노드 이미지 GC 기준 조정`
- 작업 브랜치: `infra/#403-kubelet-image-gc`

## 1. 배경

Docker Compose에서 Kubernetes로 전환한 뒤 애플리케이션 이미지는 Docker daemon이 아니라
각 노드의 containerd `k8s.io` namespace에 저장된다. GHCR의 짧은 Git SHA 태그를 사용하는
배포가 반복되면 사용하지 않는 이미지 레이어가 노드 로컬 디스크에 남을 수 있다.

현재 두 EC2 노드는 컨테이너 이미지와 Local PV 데이터를 루트 디스크에서 함께 사용한다.
Control Plane에는 PostgreSQL과 Redis, Worker에는 Kafka의 Local PV가 있다. PV의 선언 용량은
호스트 파일시스템을 물리적으로 분리하거나 사용량을 제한하지 않으므로 이미지 증가가 상태
데이터의 여유 공간까지 잠식할 수 있다.

2026-07-18 점검 결과 kubelet의 이미지 GC 설정은 다음과 같다.

- `imageGCHighThresholdPercent`: 명시되지 않아 기본값 85
- `imageGCLowThresholdPercent`: 명시되지 않아 기본값 80
- `imageMinimumGCAge`: `0s`
- `imageMaximumGCAge`: `0s`

기본 상한 85%는 Local PV와 이미지를 같은 디스크에서 사용하는 현재 구조에 늦다.

## 2. 목표

1. 두 노드에서 이미지 사용량이 디스크를 압박하기 전에 kubelet GC가 동작하게 한다.
2. 최대 미사용 기간에 따른 강제 삭제를 두지 않고 디스크 사용량을 기준으로 정리한다.
3. 현재 배포 버전과 직전 버전의 롤백 가능성을 유지한다.
4. kubelet 외부의 이미지 정리 작업으로 런타임 상태를 훼손하지 않는다.
5. Local PV 데이터는 삭제하거나 재구성하지 않는다.

## 3. 핵심 결정

### 3.1 kubelet 이미지 GC 임계값을 60/50으로 조정한다

Control Plane과 Worker의 `/var/lib/kubelet/config.yaml`에 다음 값을 명시한다.

```yaml
imageGCHighThresholdPercent: 60
imageGCLowThresholdPercent: 50
imageMinimumGCAge: 0s
imageMaximumGCAge: 0s
```

`imageMinimumGCAge: 0s`는 kubelet 설정 API 규칙에 따라 기본값 `2m`으로 해석된다. 사용하지
않은 지 2분이 지나지 않은 이미지는 디스크 임계값을 넘어도 GC 대상에서 제외된다.
`imageMaximumGCAge: 0s`는 최대 미사용 기간에 따른 강제 GC를 비활성화한다.

이미지 파일시스템 사용률이 60%를 넘으면 kubelet이 미사용 이미지를 정리하고 50% 수준까지
낮추도록 한다. 새로운 이미지가 배포될 때마다 즉시 이전 이미지를 지우는 방식은 사용하지
않는다.

60/50은 현재의 단일 디스크 구조에서 PV 여유 공간을 먼저 확보하기 위한 운영 기준이다.
장기적으로 이미지 저장소와 PV 디스크를 분리한 뒤에는 실제 사용량을 근거로 다시 조정한다.

### 3.2 롤백은 Deployment 이력과 GHCR을 기준으로 보장한다

애플리케이션 Deployment는 기존 `revisionHistoryLimit: 1`을 유지한다. 현재 버전 C를 배포하면
직전 버전 B의 ReplicaSet 명세가 한 개 남고, A보다 오래된 ReplicaSet은 보존하지 않는다.

로컬 이미지 캐시는 롤백 보장의 원본으로 사용하지 않는다. kubelet GC가 B 이미지를 이미
정리했더라도 `kubectl rollout undo`가 B의 불변 SHA 태그를 참조하면 GHCR에서 다시 pull한다.
따라서 다음 조건을 유지해야 한다.

- 배포 이미지는 `latest`가 아닌 불변 Git SHA 태그 또는 digest를 사용한다.
- 직전 배포 이미지 B는 GHCR에서 삭제하지 않는다.
- `ghcr-pull-secret`과 노드의 레지스트리 접근이 정상이어야 한다.

이 방식은 직전 버전 롤백을 보장하지만 로컬 디스크에 정확히 B 한 개만 남는 것을 보장하지는
않는다. 로컬 보존 개수보다 디스크 상한과 레지스트리 기반 복구 가능성을 우선한다.

### 3.3 외부 이미지 prune 작업은 두지 않는다

cron, `crictl rmi --prune`, `ctr images rm` 같은 외부 정리 작업은 추가하지 않는다. kubelet이
알고 있는 이미지 사용 상태와 외부 도구의 판단이 어긋나면 실행 중이거나 곧 필요한 이미지가
삭제될 수 있다. 이미지 생명주기는 kubelet GC 한 곳에서 관리한다.

Docker daemon은 Kubernetes 런타임과 별개다. 기존 Compose 종료 후 Docker 서비스는 비활성
상태를 유지하며, Docker 이미지 정리 절차를 containerd에 재사용하지 않는다.

## 4. 적용 절차

설정은 Worker, Control Plane 순서로 한 노드씩 적용한다. kubelet 재시작 때문에 노드 상태가
잠시 변할 수 있으므로 두 노드를 동시에 변경하지 않는다. Local PV가 노드에 고정돼 있고
클러스터가 두 노드뿐이므로 drain은 수행하지 않는다.

각 노드에서 다음 순서로 진행한다.

1. 디스크, 노드, Pod와 현재 kubelet 설정을 기록한다.
2. `/var/lib/kubelet/config.yaml`을 타임스탬프가 포함된 파일로 백업한다.
3. 네 가지 이미지 GC 값을 명시한다.
4. kubelet을 재시작하고 서비스가 `active`인지 확인한다.
5. 해당 노드가 `Ready`로 돌아오고 기존 Pod가 정상인지 확인한다.
6. Worker 검증이 끝난 뒤에만 Control Plane에 같은 변경을 적용한다.

설정 파일의 다른 kubeadm 값은 변경하지 않는다. containerd, API Server, StatefulSet, PVC,
PV와 Local PV 호스트 디렉터리는 재시작하거나 삭제하지 않는다.

## 5. 검증

### 5.1 설정과 서비스

- 두 노드의 설정 파일에 60/50/0s 값이 존재한다.
- 두 노드의 `kubelet`과 `containerd`가 `active`다.
- `kubectl get nodes`에서 두 노드가 모두 `Ready`다.

### 5.2 워크로드

- `prompthub` namespace의 모든 Pod가 Ready다.
- `nginx-ingress` namespace의 Controller Pod가 Ready다.
- settlement-service Deployment rollout이 정상이다.
- PostgreSQL, Redis, Kafka Pod와 PVC/PV 상태가 기존과 같다.

### 5.3 디스크와 이미지

Control Plane에서 다음 항목을 적용 전후에 비교한다.

```bash
df -h / /var/lib/containerd /var/lib/prompthub
sudo du -sh /var/lib/containerd /var/lib/prompthub
sudo ctr -n k8s.io images list
sudo journalctl -u kubelet --since "10 minutes ago"
```

Worker에서는 이미지 조회에 `crictl`을 사용한다.

```bash
df -h / /var/lib/containerd /var/lib/prompthub
sudo du -sh /var/lib/containerd /var/lib/prompthub
sudo crictl images
sudo journalctl -u kubelet --since "10 minutes ago"
```

설정 적용만으로 현재 사용률이 60% 미만인 노드에서 즉시 이미지가 삭제될 필요는 없다. 이후
이미지 사용률이 상한을 넘었을 때 kubelet 로그와 디스크 사용량으로 GC 동작을 확인한다.

## 6. 실패와 복구

노드가 `Ready`로 돌아오지 않거나 kubelet이 시작하지 못하면 다음 순서로 복구한다.

1. `systemctl status kubelet`과 `journalctl -u kubelet`에서 설정 파싱 오류를 확인한다.
2. 해당 노드의 백업 파일을 `/var/lib/kubelet/config.yaml`로 복원한다.
3. kubelet을 재시작한다.
4. 노드 Ready와 기존 Pod 상태를 다시 확인한다.
5. 원인을 해결하기 전에는 다음 노드에 적용하지 않는다.

이미지 GC로 로컬 이미지가 삭제돼도 GHCR의 불변 태그는 삭제되지 않는다. 롤백 이미지 pull이
실패하면 GC 설정을 되돌리는 대신 GHCR 인증과 네트워크를 먼저 복구한다.

## 7. 저장소 반영 범위

이 설계 문서는 settlement-service 담당 범위에 저장한다. 실제 운영 절차의 원본인
`k8s/README.md`는 저장소 루트의 공통 영역이라 현재 모듈 지침상 별도 사용자 승인 없이
수정하지 않는다. 구현 단계에서는 다음 두 작업을 분리한다.

- 승인된 원격 작업: 두 EC2 노드의 kubelet 설정 적용과 검증
- 추가 승인이 필요한 저장소 작업: `k8s/README.md`에 60/50 기준, 적용, 검증, 복구 절차 반영

노드 설정만 바꾸고 저장소 실행 가이드를 갱신하지 않으면 재구성 시 기본값으로 돌아갈 수
있으므로 최종 완료 전 공통 문서 반영 여부를 결정한다.

## 8. 장기 개선

이미지 임계값 조정은 단일 디스크 구조의 위험을 줄이는 단기 조치다. 다음 인프라 변경에서는
containerd 이미지 저장소와 Local PV를 서로 다른 EBS 볼륨으로 분리한다. 분리 후에는 각
파일시스템의 용량, 경보, 백업과 복구 기준을 독립적으로 운영한다.

## 9. 완료 기준

- 두 노드에 60/50/0s 설정이 적용되어 있다.
- 두 노드, 전체 애플리케이션 Pod와 Ingress가 정상이다.
- Local PV와 상태 저장 Pod에 변경이나 데이터 손실이 없다.
- 직전 Deployment 버전이 GHCR의 불변 태그로 롤백 가능하다.
- 외부 이미지 prune 주기 작업이 추가되지 않는다.
- 설정, 검증 결과와 복구 방법이 운영 문서에 남아 있다.
