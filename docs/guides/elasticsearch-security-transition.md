# Elasticsearch 보안 전환 가이드

공유 Elasticsearch를 HTTP·무인증에서 HTTPS·인증으로 옮기는 절차다. #567에서 요청한 후속
작업이고 이슈는 #582다.

## 왜 필요한가

`k8s/addons/elk/elasticsearch.yaml`이 `xpack.security.enabled=false`로 떠 있다. 클러스터
안에서 `elasticsearch.elk.svc.cluster.local:9200`에 도달하는 주체는 누구든 `products-v1`과
`gateway-access-*`를 읽고 쓸 수 있고, product-service와 Logstash가 같은 무제한 권한을 쓴다.

이 상태는 사고가 아니라 **의도된 임시 경계**다. #567이 ELK를 붙일 때 product-service에
인증 연결 코드가 없어, 그때 보안을 켜면 상품 검색이 그 자리에서 끊기기 때문이다.

## 현재 어디까지 왔나

- **product-service는 인증 연결을 지원한다** (#582 1단계, 완료). 설정값이 비어 있으면
  지금까지와 똑같이 무인증으로 붙으므로, 이 코드가 배포돼 있어도 동작은 바뀌지 않는다
- **ES는 아직 보안이 꺼져 있다** (#582 3단계, 미실행). 아래 절차가 그 3단계다

## 0. 전제 — 단일 노드라서 인증서가 한 벌이면 된다

보안을 켜면 **"transport SSL must be enabled if security is enabled"** 부트스트랩 체크가
걸린다. 걸리면 인증서를 두 벌(HTTP용 + 노드 간 통신용) 만들어야 한다.

우리는 걸리지 않는다. [Elastic 부트스트랩 체크 문서](https://www.elastic.co/guide/en/elasticsearch/reference/current/bootstrap-checks.html)에
예외가 명시돼 있다.

> If you are running a single node in production, it is possible to evade the bootstrap
> checks, either by not binding transport to an external interface, or by **binding transport
> to an external interface and setting the discovery type to `single-node`**.

노드가 하나면 "노드 간 통신"이 존재하지 않기 때문이다.

**2026-07-29 실측으로 전제를 확인했다.**

```bash
kubectl -n elk exec statefulset/elasticsearch -c elasticsearch -- curl -s "localhost:9200/_cat/nodes?v"
# -> elasticsearch-0 한 줄

kubectl -n elk get statefulset elasticsearch -o jsonpath='{.spec.replicas}{"\n"}'
# -> 1

kubectl -n elk get pod -l app.kubernetes.io/name=elasticsearch -o jsonpath='{range .items[*]}{.metadata.name}{"  discovery.type="}{.spec.containers[0].env[?(@.name=="discovery.type")].value}{"\n"}{end}'
# -> elasticsearch-0  discovery.type=single-node
```

**전환 직전에 이 셋을 다시 확인한다.** 매니페스트가 곧 실제는 아니다.

### 이 전제가 깨지는 조건

**`replicas`를 2 이상으로 올리는 순간 transport TLS가 필수가 된다.** `discovery.type:
single-node`인 채로 스케일업하면 애초에 클러스터를 이루지 못한다. 노드를 늘릴 계획이 생기면
이 문서의 인증서 절차를 두 벌 기준으로 다시 짜야 한다.

### 메모리 여유도 함께 본다

보안을 켜면 TLS 핸드셰이크와 인증 처리로 메모리가 더 붙는다. 2026-07-29 관측에서 ES 파드의
`ram.percent`가 **100**이었다(heap은 512m 중 50%). heap 밖이 컨테이너 limit 1Gi를 꽉 쓰고
있다는 뜻이라, 전환 전에 여유를 확인한다.

```bash
kubectl -n elk top pod
kubectl -n elk describe pod elasticsearch-0 | grep -A6 "Last State"
```

`Last State`가 `OOMKilled`면 보안을 켜기 전에 limit부터 올린다.

### 선택 — 로컬에서 먼저 재보기

문서와 실측으로 확인했지만, 실제 기동까지 보고 싶으면 로컬에서 5분이면 된다.

```bash
docker run --rm -e discovery.type=single-node -e xpack.security.enabled=true \
  -e ELASTIC_PASSWORD=changeme -e ES_JAVA_OPTS='-Xms512m -Xmx512m' \
  -p 19200:9200 docker.elastic.co/elasticsearch/elasticsearch:9.4.3
```

## 1. 인증서 만들기

ES 내장 도구를 쓴다. 내부 통신 전용이라 공인 CA가 필요 없다. **0단계 전제에 따라 HTTP용
한 벌만 만든다** — transport용은 필요 없다.

```bash
# 실행 중인 ES 파드 안에서
kubectl -n elk exec -it statefulset/elasticsearch -- bash

bin/elasticsearch-certutil ca --silent --pem --out /tmp/ca.zip
bin/elasticsearch-certutil cert --silent --pem \
  --ca-cert /tmp/ca/ca.crt --ca-key /tmp/ca/ca.key \
  --dns elasticsearch,elasticsearch.elk.svc.cluster.local \
  --out /tmp/es.zip
```

`--dns`에 **파드가 실제로 불리는 이름을 전부** 넣는다. 빠지면 클라이언트가 호스트명 검증에서
끊는다. product-service는 `elasticsearch.elk.svc.cluster.local`로 부른다.

만들어진 `ca.crt`, `es.crt`, `es.key`를 로컬로 받아 Secret으로 만든다.

## 2. ES에 HTTPS·보안 켜기

`k8s/addons/elk/elasticsearch.yaml`

```yaml
env:
  - name: discovery.type
    value: single-node
  - name: xpack.security.enabled
    value: "true"                                    # false -> true
  - name: xpack.security.http.ssl.enabled
    value: "true"
  - name: xpack.security.http.ssl.certificate
    value: /usr/share/elasticsearch/config/certs/es.crt
  - name: xpack.security.http.ssl.key
    value: /usr/share/elasticsearch/config/certs/es.key
  - name: xpack.security.http.ssl.certificate_authorities
    value: /usr/share/elasticsearch/config/certs/ca.crt
```

인증서 Secret을 `/usr/share/elasticsearch/config/certs`에 마운트한다.

## 3. 계정과 권한 나누기

보안을 켠 뒤 `elastic` 계정으로 role과 user를 만든다. **서비스마다 자기 인덱스에만 권한을
준다** — 지금은 둘이 같은 무제한 권한을 쓰는 게 문제다.

```bash
# product-service — products-v1과 alias 읽기·쓰기
POST /_security/role/product_service
{ "indices": [{ "names": ["products-v1", "products"], "privileges": ["read", "write", "create_index", "manage"] }] }

# Logstash — gateway-access-* 쓰기만
POST /_security/role/logstash_writer
{ "indices": [{ "names": ["gateway-access-*"], "privileges": ["create_index", "write", "manage"] }] }
```

`products` alias와 `manage` 권한이 필요한 이유는 product-service가 기동 시
`ProductIndexBootstrap`으로 인덱스·alias 존재를 확인하고 없으면 만들기 때문이다.

## 4. 인증 정보 주입

### product-service (`prompthub` 네임스페이스)

`runtime-secret`에 키를 더한다. 실제 파일은 control-plane의
`/home/ubuntu/prompthub-secrets/secret.yaml`(mode 600)이고, 저장소에는
`k8s/templates/runtime-values.example.yaml`에 예시 키만 둔다.

```yaml
stringData:
  ES_URIS: https://elasticsearch.elk.svc.cluster.local:9200   # http -> https
  ES_USERNAME: product_service
  ES_PASSWORD: example-product-es-password
  ES_CA_PATH: /etc/elasticsearch-ca/ca.crt
```

`k8s/base/services/product/deployment.yaml`에 env 3개와 CA volume을 더한다.

```yaml
env:
  - name: ES_USERNAME
    valueFrom: { secretKeyRef: { name: runtime-secret, key: ES_USERNAME } }
  - name: ES_PASSWORD
    valueFrom: { secretKeyRef: { name: runtime-secret, key: ES_PASSWORD } }
  - name: ES_CA_PATH
    valueFrom: { secretKeyRef: { name: runtime-secret, key: ES_CA_PATH } }
volumeMounts:
  - name: elasticsearch-ca
    mountPath: /etc/elasticsearch-ca
    readOnly: true
volumes:
  - name: elasticsearch-ca
    secret:
      secretName: elasticsearch-ca
```

**Secret은 네임스페이스를 넘지 않는다.** CA Secret을 `elk`에만 만들면 `prompthub`의
product-service가 못 읽는다. **양쪽 네임스페이스에 각각 만들어야 한다.**

`config/src/main/resources/configs/product-service.yml`은 이미 키를 읽고 있다.

```yaml
elasticsearch:
  uris: ${ES_URIS}
  username: ${ES_USERNAME:}
  password: ${ES_PASSWORD:}
  ca-path: ${ES_CA_PATH:}
```

전부 기본값이 있어 값을 안 주면 무인증으로 붙는다. **다만 이 yml은 Config Server 이미지에
구워진다** — 고쳐서 머지했다고 반영되지 않고 Config Server 재배포가 필요하다
(`config/CLAUDE.md`의 "커밋 ≠ 반영").

### Logstash (`elk` 네임스페이스)

`k8s/addons/elk/logstash.yaml`의 ES output 두 곳(현재 `http://elasticsearch...:9200` 무인증)에
인증과 CA를 더한다.

```ruby
elasticsearch {
  hosts => ["https://elasticsearch.elk.svc.cluster.local:9200"]
  user => "${ES_USERNAME}"
  password => "${ES_PASSWORD}"
  cacert => "/etc/elasticsearch-ca/ca.crt"
}
```

**이 파일은 #567 담당자 소관이다.** 변경안만 준비하고 적용 주체는 합의한다.

## 5. 적용 순서

보안 스위치는 ES 하나에 달려 있어 **켜는 순간 두 서비스가 동시에 끊긴다.** 담당자와 시간을
맞춰 한 번에 넘긴다.

```
1. Secret 적용 (elk + prompthub 양쪽 CA, runtime-secret 갱신)
2. ES 재기동            ← 이 시점부터 무인증 접근이 막힌다
3. 계정·role 생성
4. product-service 재배포 + Logstash 재배포
5. 검증
```

## 6. 검증

```bash
# 인증이 실제로 걸렸는지 — 자격증명 없이 401이어야 한다
kubectl -n elk exec statefulset/elasticsearch -- \
  curl -sk -o /dev/null -w '%{http_code}\n' https://localhost:9200/_cluster/health

# 상품 목록·검색
curl -s "$BASE/api/v2/products?q=면접&size=5" | jq '{total: .meta.total, count: (.data|length)}'

# 자동완성 — 여기가 제일 먼저 깨진다
curl -s "$BASE/api/v2/products/suggest?q=회의" | jq '.data'

# 재조정 배치가 색인을 쓰고 있는지
kubectl -n prompthub logs deploy/product-service --tail=50 | grep 재조정
```

Kibana에서 `gateway-access-*`에 새 문서가 계속 들어오는지도 함께 본다.

## 7. 장애 시 복구·롤백

### 증상별 원인

| 증상 | 원인 |
|---|---|
| 목록·검색은 되는데 **자동완성만 빈 목록** | ES 연결 실패. 목록·검색은 RDB 폴백이 있지만 **자동완성은 폴백이 없다** — 가장 먼저, 그리고 조용히 깨지는 곳이다. 이때 **재조정 배치도 함께 죽어 있다**(아래 A/B 표 참고) |
| 로그에 `ES 조회에 실패해 RDB로 폴백합니다` | 인증 실패 또는 TLS 핸드셰이크 실패 |
| 기동이 `Elasticsearch CA 인증서를 읽지 못했습니다`로 죽음 | `ES_CA_PATH` 경로에 파일이 없다. volume 마운트 확인 |
| 로그에 `인증 설정이 한쪽만 채워져 있어 무인증으로 연결합니다` | `ES_USERNAME`·`ES_PASSWORD` 중 하나만 주입됐다 |
| 호스트명 검증 실패 | 1단계 `--dns`에 `elasticsearch.elk.svc.cluster.local`이 빠졌다 |

### 두 경우를 구분한다 — 배치가 구해주는 쪽과 아닌 쪽

**"기다리면 재조정 배치가 채워주겠지"는 전환 장애에서 통하지 않는다.** 배치 자신이 ES에
붙어야 하기 때문이다.

| | A. 인증 실패 (전환 중 실제로 겪을 일) | B. 인덱스 유실 |
|---|---|---|
| 상태 | 연결이 안 됨. **ES 안의 데이터는 그대로 있다** | 연결은 됨. 안이 비었다 |
| 검색·목록 | RDB 폴백으로 동작 | 0건 |
| 자동완성 | 빈 목록 | 빈 목록 |
| **재조정 배치** | **같이 죽는다** | 정상 동작 |
| 복구 | 자격증명을 고치면 **즉시** 복구 | 배치가 채운다 |

A에서 배치가 죽는 이유는 `ProductReindexService.reconcileChanged()`가 맨 처음
`productSearchIndexer.indexExists()`를 부르는데, 연결이 안 되면 거기서 예외가 나기
때문이다. 20초마다 재시도만 하고 **색인에 아무것도 쓰지 못한다.**

A는 데이터가 사라진 게 아니라 못 읽었던 것뿐이라, **자격증명을 고치면 자동완성이 바로
돌아온다.** 배치를 기다릴 필요가 없다.

### A에서 장애가 조용한 이유

```
자격증명 틀림
  ↓
검색·목록   → RDB 폴백 → 정상 동작        ← 사용자는 이상을 못 느낀다
자동완성    → 빈 목록                     ← 조용히 안 뜬다
배치        → 20초마다 예외, 아무것도 못 씀
  ↓
그동안 상품이 수정돼도 ES에 반영되지 않는다
  ↓
자격증명 고침 → 자동완성 즉시 복구 + 배치가 밀린 변경분을 따라잡음
```

**영구 손실은 없다.** 배치는 성공했을 때만 워터마크(`lastSucceededAt`)를 전진시키므로,
실패한 구간의 변경분은 복구 후 다시 걸린다. 그 사이 파드가 재기동되면 워터마크가 메모리에서
사라져 첫 tick이 전체 재조정을 돌리므로 그것도 안전하다.

전환 중에는 **이 로그를 지켜본다.** `증분 재조정 완료`가 20초마다 안 찍히면 배치가 죽어 있다.

```bash
kubectl -n prompthub logs deploy/product-service --tail=50 -f | grep -E "재조정|ES 조회에 실패|자동완성 조회에 실패"
```

### 롤백

`k8s/addons/elk/elasticsearch.yaml`에서 `xpack.security.enabled`를 `false`로 되돌리고 ES를
재기동한다. product-service는 `ES_URIS`를 `http://`로 되돌리면 되고, 인증 env는 남아 있어도
연결에 쓰이지 않으므로 급히 지울 필요 없다.

### 인덱스가 유실됐다면 (위 표의 B)

RDB로부터 재생성된다. **수동 트리거 엔드포인트는 없다** — 재생성 경로는
`ProductReconcileScheduler` 하나다.

- 기동 후 첫 tick에서 전체 재조정
- 20초 주기 증분 재조정
- 매일 04:00 전체 스윕

가장 빠른 방법은 **product-service 파드를 재기동**해 첫 tick의 전체 재조정을 유발하는 것이다.
상품 임베딩은 Postgres 컬럼(pgvector)이 원본이라 재색인 시 복사만 되고 OpenAI를 다시
호출하지 않는다.

## 로컬 개발

로컬 `product-service/docker-compose.yml`의 ES는 무인증 HTTP 그대로 둔다.
`application-local.yml`이 `ES_URIS`만 주고 나머지는 비어 있어 무인증으로 붙는다. 인증 경로를
로컬에서 확인하려면 위 0단계의 docker 명령으로 보안을 켠 ES를 띄우고 환경변수를 주면 된다.
