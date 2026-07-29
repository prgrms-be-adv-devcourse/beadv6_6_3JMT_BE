# ELK Elasticsearch 교체 — 배포 준비 및 검증 계획

> 원래 이 문서는 "nori 이미지 퍼블리시 및 배포 계획"이었다. nori를 실측한 결과 제거하기로
> 하면서(#583 close) 이미지 관련 Task 3개가 전부 없어졌고, 배포 준비·검증만 남았다.
> 경위는 문서 끝 「이력」 참고.

## Context

팀 담당자가 ELK 스택(PR #567)을 클러스터에 적용한다. `elk` 네임스페이스의 Elasticsearch가
기존 **Deployment**(emptyDir)에서 새 **StatefulSet**(로컬 PV)으로 바뀐다.

product-service의 `products-v1`이 이 ES를 쓴다. 교체 자체는 안전하다 — ES는 RDB에서
재생성 가능한 캐시이고, 교체 중에는 `ProductQueryService`의 RDB 폴백이 요청을 계속 처리한다.

**주의할 점은 실패가 조용하다는 것이다.** 인덱스 생성이 실패하면 `reconcileAll()`이
`indexExists()` 가드에서 스킵하고 모든 검색이 RDB 폴백으로 넘어간다. HTTP 에러도 없고
화면도 정상으로 보인다. "검색 결과가 나오는가"만 확인하는 테스트는 통과해버린다.
그래서 아래 검증은 **ES에 직접 물어보는 것부터** 한다.

**목표**: 교체 후 `products-v1`이 재생성되고 검색이 실제로 ES를 타는 것을 확인 가능한
상태로 만든다.

## 현재 상태 (2026-07-27 확인)

| 항목 | 값 |
|---|---|
| 기존 ES | `elk` 네임스페이스, **Deployment** `elasticsearch`, emptyDir |
| 기존 ES 노드 | `k8s-worker` (`10.244.1.224`) |
| `products-v1` | green, **65 docs**, 240.7kb → 재색인 수 초 |
| 기타 인덱스 | `application-logs-2026.07.23~26` (4일치, 약 6MB) — **교체 시 유실, 복구 불가** |
| 재시작 | **6회** (마지막 07-26 23:04, exit 255 / Reason Unknown — OOM 아님, 원인 미확인) |
| 신규 매니페스트 | `k8s/addons/elk/elasticsearch.yaml:169` = 순정 이미지 — **수정 불필요** |
| 신규 StatefulSet 배치 | control-plane (`prompthub.io/node-pool: control-stateful`) |
| CD 자동 적용 | **없음** — `cd-selfhosted-kubernetes.yml`은 `k8s/addons/nginx-ingress`만 적용 |

## Global Constraints

- `k8s/addons/elk/` 하위 파일은 **건드리지 않는다** — 담당자 소유이고, 순정 이미지 그대로
  쓰면 되므로 수정할 이유가 없다
- product-service 코드는 이 계획에서 변경하지 않는다 (#585·#586·#582·#378은 별개)
- Windows 터미널에서 명령을 복사하면 `\r`이 딸려 들어가 `invalid syntax` 오류가 난다
  (이미 겪음). **명령은 직접 타이핑하거나 한 줄씩 붙여넣는다.**

---

## Task 1: 담당자에게 배포 전 전달 — **완료 (2026-07-27)**

PR #567 코멘트로 전달했다. 내용:

1. nori 이미지 교체가 **불필요**해졌음 — 매니페스트 그대로 apply
2. 기존 Deployment를 먼저 지우고 StatefulSet 적용 (트래픽 분산 때문이 아니라, heap 1GB
   노드에 ES가 둘 뜨는 것과 같은 이름 워크로드 공존 혼선 때문)
3. `application-logs-*` 유실 경고 — 인지하고 있는 파이프라인인지 확인 요청
4. `elk` 자동 적용 스크립트 존재 여부 질문

- [ ] **회신 확인** — 특히 3번(로그 파이프라인 인지 여부)과 4번(자동 적용 스크립트)의 답을
  받아야 Task 2의 전제가 성립한다

---

## Task 2: 배포 후 검증

**순서가 중요한 이유**: 폴백 때문에 겉보기로는 정상·비정상이 구분되지 않는다.
가장 근본적인 것부터 확인해 실패 지점을 좁힌다.

- [ ] **Step 0: 검색 테스트에 쓸 상품명 미리 확보 (배포 전)**

```
kubectl exec -n elk deploy/elasticsearch -- curl -s "localhost:9200/products/_search?size=10&_source=name&pretty"
```

상품명 10개 중 **여러 단어로 된 이름**을 2~3개 메모해둔다(예: `시니어 코드리뷰 프롬프트`).
Step 5에서 그 **중간 단어**로 검색할 것이다.

- [ ] **Step 1: 파드가 정상 기동했는지**

```
kubectl get pod -n elk -o wide
```

Expected: `elasticsearch-0` Running, **control-plane 노드**에 배치.

- [ ] **Step 2: 인덱스 생성 확인**

```
kubectl exec -n elk statefulset/elasticsearch -- curl -s "localhost:9200/_cat/indices?v"
```

Expected: `products-v1`이 green 또는 yellow.

**여기서 안 보이면 아래 단계는 의미 없다.** Step 3의 로그로 원인을 좁힌다.

- [ ] **Step 3: product-service 연결 및 색인 확인**

수동 재색인 트리거는 **불필요하다** — `ProductReconcileScheduler`가 20초마다
`reconcileAll()`을 실행하므로 자동으로 채워진다.

```
kubectl logs -n prompthub deploy/product-service --tail=50
```

Expected: `index=products-v1 생성 및 alias=products 연결 완료` 또는 `alias=products 이미 존재`,
그리고 `전체 재조정 완료. upsert=N`.

**`ES 인덱스가 아직 없어 이번 재조정 사이클을 건너뜁니다`가 반복되면** ES에 못 붙고 있는
것이다 — Step 6으로 간다.

- [ ] **Step 4: 문서 수 확인**

```
kubectl exec -n elk statefulset/elasticsearch -- curl -s "localhost:9200/products/_count"
```

Expected: `{"count":65,...}` 부근 (기존과 동일한 family 수).

- [ ] **Step 5: 검색 실제 확인 — 최종 판정**

브라우저에서 `/browse` 접속 후, Step 0에서 메모한 상품명의 **중간 단어**로 검색한다.

Expected: 해당 상품이 결과에 나온다.

**이것이 핵심 검증이다.** 앞 단계가 모두 통과해도 검색이 ES를 타는지는 검색해봐야 안다.
`/products/suggest?q=` 자동완성도 같은 방식으로 중간 단어를 넣어 확인한다(#379).

- [ ] **Step 6: ES_URIS 설정 확인 (앞 단계가 실패했을 때만)**

```
kubectl get secret runtime-secret -n prompthub -o jsonpath="{.data.ES_URIS}" | base64 -d
```

Expected: `http://elasticsearch.elk.svc.cluster.local:9200`

시크릿 이름은 `runtime-secret`(`k8s/templates/runtime-values.example.yaml:23`), 값은 같은
파일 34번 줄 기준. 다르면 시크릿을 갱신하고
`kubectl rollout restart deploy/product-service -n prompthub`로 재기동한다.

---

## 이 계획에서 제외한 것

- **#585·#586·#582·#378 구현** — 내일 테스트와 무관하다
- **임베딩 검증** — #378이 미구현이다. `product-service/src/main/java`에 OpenAI·embedding
  관련 코드가 0건이고 환경변수도 없다. OpenAI 키를 준비해뒀더라도 사용하는 코드가 없어
  **내일 확인할 대상이 아니다**
- **테스트 상품 데이터 생성** — 이미 65건이 색인돼 있어 INSERT문이 필요 없다
- **수동 재색인 트리거** — 20초 스케줄러가 같은 `reconcileAll()`을 호출한다
- **ES 데이터 백업** — `products-v1`은 RDB에서 재생성 가능하고 65건이라 수 초면 끝난다
- **nori 플러그인 제거 작업** — 필요 없다. 플러그인은 컨테이너 파일시스템(`plugins/`)에
  있고 PV는 `data/`만 마운트하므로(`elasticsearch.yaml:208-210`), 파드가 교체되면
  자동으로 사라진다

## 미해결 항목 (내일 논의 재료)

**ES 파드가 3일간 6회 재시작했다.** 마지막 종료가 `Exit Code: 255 / Reason: Unknown`으로
OOMKilled가 아니다(그랬다면 `137`). 14시간 가동 후 종료라 빠른 크래시 루프도 아니다.
이벤트는 이미 만료됐다.

원인 확인용 (급하지 않음):

```
kubectl logs -n elk deploy/elasticsearch --previous | tail -60
kubectl get deploy elasticsearch -n elk -o yaml | grep -A8 resources
sudo dmesg -T | grep -i oom | tail
```

메모리 limit이 없으면 쿠버네티스가 OOM을 기록하지 못하고 호스트 커널이 죽인다 — 그 경우
정확히 `Unknown`/`255`로 보인다. `dmesg`에 흔적이 있으면 결국 메모리 문제다.

**이게 중요한 이유**: 같은 heap 1GB 노드에 앞으로 `gateway-access-*`(#567),
`logs-prompthub.*`(#575), 결제 감사로그(#540), `behavior-logs`(#381)가 순차로 들어온다.
**이미 6번 죽은 노드에 그만큼을 더 얹는 상황**이므로, 재시작 기록은 "로그 스트림 추가 전에
메모리 증설을 검토하자"는 근거가 된다.

## 이력 — nori 전제가 폐기된 경위

이 문서의 원래 Task 1~3은 "nori 플러그인이 없으면 `products-v1` 생성이 실패한다"는 전제로
GHCR 이미지 퍼블리시·패키지 공개·매니페스트 수정 PR을 계획했다.

**그 전제를 한 번도 재보지 않았다.** 실제로 재보니 nori가 이 서비스 어휘를 잘못 쪼개고
있었다 — 조사 분리("보고서"로 "보고서를" 찾기)가 0건이고, 매칭되는 케이스도 한 글자
겹침에 기댄 오탐이었다. `korean` 분석기가 `nori_tokenizer` 단독이라 품사 필터도 사용자
사전도 없는 게 원인이다.

nori를 제거하면서 이미지 의존이 통째로 사라졌다. 측정 결과는 **#583**에 정리돼 있다.
