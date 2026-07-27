# Server-Side Apply의 `last-applied-configuration` 충돌 경고

## 증상

Server-side dry-run에서 다음 경고가 반복된다.

```text
Warning: failed to migrate kubectl.kubernetes.io/last-applied-configuration
Apply failed with 1 conflict: conflict with "kubectl-client-side-apply"
```

각 리소스의 마지막 출력은 다음과 같다.

```text
serverside-applied (server dry run)
```

## 원인

기존 리소스가 `kubectl apply -f` 또는 `kubectl apply -k`의 client-side 방식으로 생성되었다.
Server-Side Apply가 기존 `kubectl.kubernetes.io/last-applied-configuration` annotation을
자신의 field ownership으로 이전하려 할 때 기존 client-side field manager와 충돌한다.

애플리케이션 설정 필드의 충돌이 아니라 kubectl 관리용 annotation 충돌이다.

## 판정

다음 조건이면 비치명적 경고로 취급할 수 있다.

- 명령이 `--dry-run=server`임
- 실제 매니페스트 필드 충돌이 별도로 출력되지 않음
- 모든 리소스가 `serverside-applied (server dry run)`으로 끝남
- client-side dry-run과 Kustomize 렌더가 성공함

## 해결

Server-side dry-run은 검증 용도로 사용하고 실제 적용은 기존과 같은 client-side 방식으로 유지한다.

```bash
kubectl apply --dry-run=client \
  -k k8s/addons/elk

kubectl apply --server-side --dry-run=server \
  -k k8s/addons/elk

kubectl apply \
  -k k8s/addons/elk
```

## 정상 판정

```bash
kubectl -n elk get daemonset fluent-bit
kubectl -n elk get configmap fluent-bit-config
```

실제 적용 결과에서 변경 리소스는 `configured`, 나머지는 `unchanged`로 출력될 수 있다.

## 주의사항

- 이 경고를 없애기 위해 바로 `--force-conflicts`를 사용하지 않는다.
- 같은 운영 리소스에 client-side와 server-side 실제 적용을 임의로 혼용하지 않는다.
- field manager를 전환하려면 별도 변경 계획과 리소스별 ownership 검토가 필요하다.
