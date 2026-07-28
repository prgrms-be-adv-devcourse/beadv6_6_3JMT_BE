# Elasticsearch `rollout status`를 사용할 수 없는 문제

## 증상

Elasticsearch가 실행 중인데 다음 명령이 실패한다.

```bash
kubectl -n elk rollout status \
  statefulset/elasticsearch \
  --timeout=10m
```

오류:

```text
error: rollout status is only available for RollingUpdate strategy type
```

## 원인

Elasticsearch StatefulSet은 다음 업데이트 전략을 사용한다.

```yaml
updateStrategy:
  type: OnDelete
```

단일 노드 Local PV 데이터를 보호하기 위해 운영자가 상태를 확인한 뒤 직접 Pod 재생성 여부를
결정하도록 한 설계다. `kubectl rollout status statefulset/...`은 `RollingUpdate` 전략에서만
지원되므로 이 메시지는 Elasticsearch 장애가 아니다.

## 해결

Pod의 Ready condition을 직접 기다린다.

```bash
kubectl -n elk wait \
  --for=create \
  pod/elasticsearch-0 \
  --timeout=5m

kubectl -n elk wait \
  --for=condition=Ready \
  pod/elasticsearch-0 \
  --timeout=10m
```

상태와 로그를 확인한다.

```bash
kubectl -n elk get pod elasticsearch-0
kubectl -n elk logs pod/elasticsearch-0 --tail=100
```

## 정상 판정

```text
NAME              READY   STATUS    RESTARTS
elasticsearch-0   1/1     Running   0
```

Elasticsearch API도 확인한다.

```bash
kubectl -n elk port-forward service/elasticsearch 19200:9200
```

다른 터미널에서:

```bash
curl -fsS 'http://127.0.0.1:19200/_cluster/health?pretty'
```

단일 노드에서는 상태가 `green` 또는 `yellow`이고 다음 값이면 정상 범위다.

```text
timed_out: false
number_of_nodes: 1
unassigned_primary_shards: 0
```

## 함께 확인할 리소스

```bash
kubectl -n elk get pvc elasticsearch-data
kubectl get pv elasticsearch-local-pv
```

PV와 PVC는 모두 `Bound`여야 한다.

## 주의사항

- 이 오류를 해결하려고 `OnDelete`를 `RollingUpdate`로 바꾸지 않는다.
- 매니페스트를 다시 적용해도 기존 `OnDelete` StatefulSet Pod는 자동 교체되지 않을 수 있다.
- Pod 수동 삭제 전 Elasticsearch 데이터, 인덱스 상태와 점검 시간을 확인한다.
