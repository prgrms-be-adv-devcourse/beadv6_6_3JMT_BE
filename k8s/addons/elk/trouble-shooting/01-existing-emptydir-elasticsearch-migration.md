# 기존 `emptyDir` Elasticsearch를 StatefulSet으로 전환할 때 데이터 손실

## 증상

새 `k8s/addons/elk` 패키지를 적용하기 전에 기존 리소스를 조회했을 때
`deployment/elasticsearch`가 실행 중이고 데이터 볼륨이 `emptyDir`로 확인된다.

```bash
kubectl -n elk get deployment,statefulset,service,pvc

kubectl -n elk get deployment elasticsearch \
  -o jsonpath='{range .spec.template.spec.volumes[*]}{.name}{" => emptyDir="}{.emptyDir}{" pvc="}{.persistentVolumeClaim.claimName}{"\n"}{end}'
```

문제가 된 실제 형태:

```text
elasticsearch-data => emptyDir={}
```

기존 클러스터에는 `products-v1`, `application-logs-*`, Kibana 내부 인덱스가 존재할 수 있다.

## 원인

`emptyDir` 데이터는 Pod 수명에 종속된다. 기존 Deployment Pod를 삭제하거나 재생성하면
Elasticsearch 데이터가 함께 사라진다.

새 패키지의 Elasticsearch는 다음과 같이 영속성을 제공한다.

- `StatefulSet/elasticsearch`
- `PersistentVolume/elasticsearch-local-pv`
- `PersistentVolumeClaim/elasticsearch-data`
- Control Plane의 `/var/lib/prompthub/elasticsearch`
- `persistentVolumeReclaimPolicy: Retain`

기존 Deployment와 새 StatefulSet을 동시에 실행하면 같은 Service selector가 두 Pod를 선택할
가능성도 있으므로 동시에 유지하면 안 된다.

## 진단

기존 인덱스와 alias를 확인한다.

```bash
kubectl -n elk port-forward service/elasticsearch 19200:9200
```

다른 터미널에서:

```bash
curl -fsS 'http://127.0.0.1:19200/_cluster/health?pretty'
curl -fsS 'http://127.0.0.1:19200/_cat/indices?v&s=index'
curl -fsS 'http://127.0.0.1:19200/_cat/aliases?v'
```

## 해결

1. `products-v1`의 mapping, settings, alias와 문서를 논리 백업한다.
2. 보존할 로그와 Kibana Saved Objects를 별도로 내보낸다.
3. 백업을 EC2 외부에도 복사한다.
4. Product Service의 Elasticsearch 쓰기를 중단할 점검 시간을 잡는다.
5. 기존 ELK를 제거한 뒤 새 StatefulSet을 적용한다.
6. `products-v1`과 `products` alias를 복원한다.

문서 수가 적을 때의 논리 백업 예:

```bash
backup_dir="/home/ubuntu/prompthub-backups/elk-$(date +%Y%m%d-%H%M%S)"
mkdir -p "${backup_dir}"
chmod 700 "${backup_dir}"

curl -fsS 'http://127.0.0.1:19200/products-v1/_mapping?pretty' \
  -o "${backup_dir}/products-v1-mapping.json"
curl -fsS 'http://127.0.0.1:19200/products-v1/_settings?pretty' \
  -o "${backup_dir}/products-v1-settings.json"
curl -fsS 'http://127.0.0.1:19200/products-v1/_alias?pretty' \
  -o "${backup_dir}/products-v1-alias.json"
curl -fsS -H 'Content-Type: application/json' \
  'http://127.0.0.1:19200/products-v1/_search?pretty' \
  -d '{"size":1000,"sort":["_doc"],"query":{"match_all":{}}}' \
  -o "${backup_dir}/products-v1-documents.json"
```

## 정상 판정

```bash
kubectl -n elk get pod,pvc
kubectl get pv elasticsearch-local-pv
```

다음을 모두 만족해야 한다.

- `elasticsearch-0`이 `1/1 Running`
- `elasticsearch-data`가 `Bound`
- `elasticsearch-local-pv`가 `Bound`
- Elasticsearch Pod가 Control Plane에 배치
- 복원한 `products-v1` 문서 수와 alias가 백업과 일치

## 주의사항

- 백업 검증 전 `deployment/elasticsearch`를 삭제하지 않는다.
- `kubectl delete pod`도 `emptyDir` 데이터 손실을 일으킨다.
- PVC, PV와 `/var/lib/prompthub/elasticsearch`는 일반 rollback 과정에서 삭제하지 않는다.
