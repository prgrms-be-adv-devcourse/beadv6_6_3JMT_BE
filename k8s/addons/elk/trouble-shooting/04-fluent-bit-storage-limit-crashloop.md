# Fluent Bit `storage.total_limit_size`로 인한 CrashLoopBackOff

## 증상

ELK 적용 후 Fluent Bit만 시작하지 못한다.

```text
fluent-bit-*   0/1   CrashLoopBackOff
```

이전 컨테이너 로그:

```bash
kubectl -n elk logs daemonset/fluent-bit --previous --tail=200
```

핵심 오류:

```text
tail: unknown configuration property 'storage.total_limit_size'
input initialization failed
```

## 원인

`storage.total_limit_size`가 `[INPUT]`의 `tail` 플러그인에 선언되어 있다.

잘못된 구성:

```ini
[INPUT]
    Name                     tail
    storage.type             filesystem
    storage.total_limit_size 512M
```

이 옵션은 input 속성이 아니라 output별 filesystem logical queue 용량을 제한하는 output 속성이다.
Fluent Bit 5.0.2는 알 수 없는 input 속성을 발견하면 exit code `255`로 종료한다.

## 해결

`storage.total_limit_size`를 `[INPUT]`에서 제거하고 `[OUTPUT]`으로 옮긴다.

```diff
 [INPUT]
     storage.type             filesystem
-    storage.total_limit_size 512M

 [OUTPUT]
     Retry_Limit              False
+    storage.total_limit_size 512M
     workers                  1
```

적용:

```bash
kubectl apply --dry-run=client \
  -k k8s/addons/elk

kubectl apply \
  -k k8s/addons/elk

kubectl -n elk rollout restart daemonset/fluent-bit
kubectl -n elk rollout status daemonset/fluent-bit --timeout=5m
```

## 검증

```bash
kubectl -n elk get pod \
  -l app.kubernetes.io/name=fluent-bit \
  -o wide

kubectl -n elk logs daemonset/fluent-bit --tail=200
```

정상 기준:

- `1/1 Running`
- 재시작 횟수가 증가하지 않음
- `unknown configuration property`가 없음
- Kubernetes API 연결 성공
- `output:http` worker와 HTTP server가 시작됨

## 주의사항

- Startup probe 실패는 원인이 아니라 Fluent Bit 프로세스 종료의 결과다.
- ConfigMap만 변경하면 DaemonSet Pod template은 `unchanged`로 표시된다.
- ConfigMap 변경 후 Pod가 새 설정을 읽도록 `rollout restart`가 필요하다.
- EC2에서만 수정하지 말고 Git 원본에도 같은 변경을 반영한다.
