# Kubernetes 배포 매니페스트

이 문서는 `k8s/` 매니페스트를 렌더링·적용·검증·복구하는 실행 가이드다. 버전, 토폴로지, 포트, 리소스 이름, 배치와 용량 계약은 [Kubernetes 아키텍처 명세](../docs/architecture/kubernetes.md)를 유일한 원본으로 사용한다.

아키텍처 값을 이 README에서 별도로 정의하지 않는다. 값이 바뀌면 아키텍처 명세와 실제 매니페스트를 함께 수정하고, 실행 절차가 바뀔 때만 이 문서를 수정한다.

모든 명령은 저장소 루트에서 실행하며, `kubectl config current-context`가 대상 클러스터를 가리키는지 먼저 확인한다.

## 렌더링과 정적 검증

현재 구현된 각 상위 패키지는 독립적으로 렌더링할 수 있다.

```bash
kubectl kustomize k8s/base/storage
kubectl kustomize k8s/base/infrastructure
kubectl kustomize k8s/base
```

저장소 루트에서 정적 검증을 실행한다.

```bash
bash scripts/validate-k8s-manifests.sh
```

스크립트는 모든 패키지가 렌더링되는지, `latest` 이미지나 미정 placeholder가 없는지, 실제 Secret이 base에 포함되지 않는지를 확인한다.

Kafka Pod는 `enableServiceLinks: false`를 유지한다. 이 값을 제거하면 Kubernetes가 `kafka` Service에서 `KAFKA_PORT=tcp://...`를 자동 생성하고, Confluent 이미지가 이를 레거시 설정으로 해석해 시작 단계에서 종료한다.

## Secret

Secret 객체, key와 저장 정책은 아키텍처 명세의 "설정과 Secret 명세"를 따른다. 실제 파일을 준비한 뒤 권한을 확인하고 적용한다.

```text
/home/ubuntu/prompthub-secrets/secret.yaml
mode: 600
```

키 이름은 `k8s/templates/secret.example.yaml`을 참고한다. 예시 파일은 가짜 값만 담고 있으며 그대로 적용하면 안 된다.

```bash
kubectl apply -f /home/ubuntu/prompthub-secrets/secret.yaml
```

Private GHCR 이미지를 배포하기 전에는 `read:packages` 권한을 가진 장기 credential로 `ghcr-pull-secret`을 준비한다. GitHub Actions 작업용 `GITHUB_TOKEN`은 장기 image pull credential로 사용하지 않는다.

## Local PV 호스트 디렉터리

Local PV 경로와 권한 기준은 아키텍처 명세의 "스토리지 명세"를 따른다. 매니페스트 적용 전에 각 노드에서 다음 디렉터리를 만든다.

Medium:

```bash
sudo install -d -m 0750 /var/lib/prompthub/postgres
sudo install -d -m 0750 /var/lib/prompthub/redis
sudo chown 70:70 /var/lib/prompthub/postgres
sudo chown 999:1000 /var/lib/prompthub/redis
```

Worker:

```bash
sudo install -d -m 0750 /var/lib/prompthub/kafka
sudo chown 1000:1000 /var/lib/prompthub/kafka
```

## 최초 적용

Kustomize의 파일 순서는 readiness를 보장하지 않는다. 아키텍처 명세의 "기동 순서와 의존성"에 따라 파동별로 적용하고 준비 상태를 확인한다. 현재 구현된 상태 저장 인프라는 다음 순서로 적용한다.

Control Plane에서 먼저 client와 server-side dry-run을 수행한다.

```bash
kubectl apply --dry-run=client -f k8s/base/namespace.yaml
kubectl apply --dry-run=client -k k8s/base/storage
kubectl apply --dry-run=client -k k8s/base/infrastructure

kubectl apply --server-side --dry-run=server -f k8s/base/namespace.yaml
kubectl apply --server-side --dry-run=server -k k8s/base/storage
kubectl apply --server-side --dry-run=server -k k8s/base/infrastructure
```

검증이 통과하면 실제로 적용한다.

```bash
kubectl apply -f k8s/base/namespace.yaml
kubectl apply -f /home/ubuntu/prompthub-secrets/secret.yaml
kubectl apply -k k8s/base/storage
kubectl apply -k k8s/base/infrastructure
```

## 검증

각 wait는 최대 60초 단위로 실행한다. 시간 초과 시 무작정 반복하지 말고 `describe`, event와 log를 먼저 확인한다.

```bash
kubectl -n prompthub wait --for=condition=Ready pod/postgres-0 --timeout=60s
kubectl -n prompthub wait --for=condition=Ready pod/redis-0 --timeout=60s
kubectl -n prompthub wait --for=condition=Ready pod/kafka-0 --timeout=60s

kubectl -n prompthub get pods -o wide
kubectl get pv
kubectl -n prompthub get pvc
```

기능 검증:

```bash
kubectl -n prompthub exec postgres-0 -- sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select schema_name from information_schema.schemata where schema_name like '\''%_service'\'' order by 1"'

kubectl -n prompthub exec redis-0 -- redis-cli ping

kubectl -n prompthub exec kafka-0 -- kafka-topics \
  --bootstrap-server kafka:9092 \
  --create --if-not-exists \
  --topic k8s-smoke \
  --partitions 1 \
  --replication-factor 1
```

### Local PV 영속성 검증

영속성 검증은 새 Kubernetes 데이터에 테스트 marker를 기록한 뒤 Pod만 삭제하고 같은 값을 다시 읽는 방식으로 수행한다. PVC, PV와 호스트 디렉터리는 삭제하지 않는다.

```bash
kubectl -n prompthub delete pod postgres-0
kubectl -n prompthub wait --for=condition=Ready pod/postgres-0 --timeout=60s

kubectl -n prompthub delete pod redis-0
kubectl -n prompthub wait --for=condition=Ready pod/redis-0 --timeout=60s

kubectl -n prompthub delete pod kafka-0
kubectl -n prompthub wait --for=condition=Ready pod/kafka-0 --timeout=60s
```

## 이미지가 내려오는 방식

Kubernetes Pod의 `image:`에 레지스트리 주소와 불변 태그 또는 digest를 지정하면 kubelet이 containerd CRI를 통해 이미지를 pull한다. EC2에서 `docker pull`을 별도로 실행하지 않는다. 기존 Docker daemon의 이미지 namespace와 Kubernetes의 `k8s.io` containerd namespace가 다르므로 Docker에 이미지가 있다는 이유만으로 Kubernetes가 그 이미지를 사용할 수 있다고 가정하지 않는다.

## 삭제와 복구 주의사항

- StatefulSet이나 Pod 삭제는 PVC/PV 삭제와 다르다.
- Local PV의 `Retain`은 데이터 디렉터리를 자동 삭제하지 않는다.
- PVC/PV 삭제는 정상 rollback 절차가 아니다.
- 데이터 초기화가 필요해도 StatefulSet, PVC/PV, 호스트 디렉터리의 관계를 확인한 뒤 별도 작업으로 수행한다.
- 현재 Docker Compose 컨테이너는 승인된 외부 트래픽 cutover 전까지 중단하거나 prune하지 않는다.
