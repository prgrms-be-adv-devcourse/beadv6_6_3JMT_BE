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

## 0. 먼저 확인할 것

**ES 9.4.3에서 `discovery.type=single-node`로 security를 켤 때 transport TLS가 필수인지
확인한다.** ES 8+는 보안을 켜면 transport 계층 TLS 부트스트랩 체크가 걸리는데, single-node는
면제되는 것으로 알려져 있으나 버전마다 다르다. 여기 결과에 따라 아래 2단계의 설정이 달라진다.

```bash
docker run --rm -e discovery.type=single-node -e xpack.security.enabled=true \
  -e ELASTIC_PASSWORD=changeme -e ES_JAVA_OPTS='-Xms512m -Xmx512m' \
  -p 19200:9200 docker.elastic.co/elasticsearch/elasticsearch:9.4.3
```

기동에 성공하면 transport TLS 없이 갈 수 있다. 부트스트랩 체크로 죽으면
`xpack.security.transport.ssl.enabled=true`와 인증서를 함께 넣어야 한다.

## 1. 인증서 만들기

ES 내장 도구를 쓴다. 내부 통신 전용이라 공인 CA가 필요 없다.

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
| 목록·검색은 되는데 **자동완성만 빈 목록** | ES 연결 실패. 목록·검색은 RDB 폴백이 있지만 **자동완성은 폴백이 없다** — 가장 먼저, 그리고 조용히 깨지는 곳이다 |
| 로그에 `ES 조회에 실패해 RDB로 폴백합니다` | 인증 실패 또는 TLS 핸드셰이크 실패 |
| 기동이 `Elasticsearch CA 인증서를 읽지 못했습니다`로 죽음 | `ES_CA_PATH` 경로에 파일이 없다. volume 마운트 확인 |
| 로그에 `인증 설정이 한쪽만 채워져 있어 무인증으로 연결합니다` | `ES_USERNAME`·`ES_PASSWORD` 중 하나만 주입됐다 |
| 호스트명 검증 실패 | 1단계 `--dns`에 `elasticsearch.elk.svc.cluster.local`이 빠졌다 |

### 롤백

`k8s/addons/elk/elasticsearch.yaml`에서 `xpack.security.enabled`를 `false`로 되돌리고 ES를
재기동한다. product-service는 `ES_URIS`를 `http://`로 되돌리면 되고, 인증 env는 남아 있어도
연결에 쓰이지 않으므로 급히 지울 필요 없다.

### 인덱스가 유실됐다면

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
