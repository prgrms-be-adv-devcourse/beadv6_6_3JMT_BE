# ELK 1차 테스트 준비 — nori 이미지 퍼블리시 및 배포 계획

> **최종 저장 위치**: 승인 후 `product-service/docs/plans/2026-07-27-elk-nori-image-deploy.md`로 옮긴다
> (`save-plan-docs` 스킬 — plan은 git 추적, spec은 로컬).

## Context

내일 팀 담당자가 ELK 스택(PR #567)을 클러스터에 적용한다. 그런데 머지된
`k8s/addons/elk/elasticsearch.yaml:169`가 **nori 플러그인이 없는 순정 Elasticsearch 이미지**를
가리키고 있다.

product-service의 `products-v1` 인덱스 매핑이 `nori_tokenizer`를 직접 참조하므로, 이 상태로
적용하면 인덱스 생성이 실패한다. **그런데 에러가 겉으로 드러나지 않는다** — `reconcileAll()`이
`indexExists()` 가드에서 조용히 스킵하고, 모든 검색이 `ProductQueryService`의 RDB 폴백으로
넘어가기 때문이다. 화면은 정상으로 보이고 HTTP 에러도 없다. "검색 결과가 나오는가"만 확인하는
테스트는 통과해버린다.

현재 클러스터에는 nori가 포함된 커스텀 이미지(`elasticsearch-nori:9.4.3`)로 뜬 ES가 이미
5일째 돌고 있으나, **레지스트리 접두어가 없는 로컬 이미지**라 그 이미지를 빌드한 노드
(`k8s-worker`)에만 존재한다. 새 StatefulSet은 `nodeSelector: prompthub.io/node-pool:
control-stateful`로 **control-plane 노드에 배치**되므로 그 이미지를 찾지 못한다.

**목표**: 내일 배포 시 nori 포함 ES가 정상 기동하고, `products-v1`이 재생성되며, 한글 형태소
검색이 실제로 동작하는 것을 확인 가능한 상태로 만든다.

## 현재 상태 (확인 완료)

| 항목 | 값 |
|---|---|
| 기존 ES | `elk` 네임스페이스, **Deployment** `elasticsearch`, 5d8h |
| 기존 ES 이미지 | `elasticsearch-nori:9.4.3` (로컬, 레지스트리 없음) |
| 기존 ES 노드 | `k8s-worker` (`10.244.1.224`) |
| `products-v1` | green, **65 docs**, 240.7kb → 재색인 수 초 |
| 기타 인덱스 | `application-logs-2026.07.23~26` (4일치, 약 6MB) — **교체 시 유실, 복구 불가** |
| 재시작 | **6회** (마지막 07-26 23:04, exit 255 / Reason Unknown — OOM 아님, 원인 미확인) |
| 신규 매니페스트 | `k8s/addons/elk/elasticsearch.yaml:169` = 순정 이미지, `imagePullPolicy: IfNotPresent` |
| 신규 StatefulSet 배치 | control-plane (`prompthub.io/node-pool: control-stateful`) |
| CD 자동 적용 | **없음** — `cd-selfhosted-kubernetes.yml`은 `k8s/addons/nginx-ingress`만 적용 |
| 노드 docker | CLI만 있고 **데몬 없음** (containerd) → 로컬 PC에서 빌드해야 함 |
| `gh` 토큰 | `write:packages` 스코프 **없음** |

## Global Constraints

- 이미지 태그: `ghcr.io/prgrms-be-adv-devcourse/prompthub-elasticsearch:9.4.3-nori`
- 베이스 이미지 버전은 기존과 동일한 **9.4.3** 유지 (ES 버전과 플러그인 버전은 반드시 일치)
- `k8s/addons/elk/` 하위 파일 중 **`elasticsearch.yaml`의 `image:` 한 줄만** 수정한다
  (`logstash.yaml`, `kibana.yaml`, `fluent-bit.yaml`은 건드리지 않는다 — 담당자 소유)
- product-service 코드는 이 계획에서 **변경하지 않는다** (#585·#586·#582·#378은 별개)
- Windows 터미널에서 명령을 복사하면 `\r`이 딸려 들어가 `invalid syntax` 오류가 난다
  (이미 겪음). **명령은 직접 타이핑하거나 한 줄씩 붙여넣는다.**

---

## Task 1: nori 포함 ES 이미지를 GHCR에 퍼블리시

**Files:**
- 사용(수정 없음): `product-service/docker/elasticsearch/Dockerfile`

**Interfaces:**
- Produces: 퍼블리시된 이미지 태그
  `ghcr.io/prgrms-be-adv-devcourse/prompthub-elasticsearch:9.4.3-nori` — Task 3이 이 문자열을
  매니페스트에 넣고, Task 5가 이 이미지의 기동을 검증한다.

**왜 로컬 PC에서 빌드하는가**: `k8s-worker`에 docker 데몬이 없다(containerd만). `ctr`로
태그·푸시하는 방법도 있으나 토큰을 명령줄에 노출해야 하고 이미지 이름 접두어가 달라질 수 있다.
Dockerfile이 2줄이라 로컬 빌드가 더 단순하고 검증도 쉽다.

- [ ] **Step 1: Dockerfile 내용 확인**

Run:
```
type C:\programmers_prj\beadv6_6_3JMT_BE\product-service\docker\elasticsearch\Dockerfile
```

Expected:
```
FROM docker.elastic.co/elasticsearch/elasticsearch:9.4.3
RUN bin/elasticsearch-plugin install --batch analysis-nori
```

- [ ] **Step 2: GHCR 푸시 권한 추가**

Run:
```
gh auth refresh --scopes write:packages
```

Expected: 브라우저가 열리고 인증 코드 입력 후 `✓ Logged in as gfkmkl`.
현재 토큰 스코프는 `gist, read:org, repo, workflow`로 `write:packages`가 없다.

- [ ] **Step 3: GHCR 로그인**

Run:
```
gh auth token | docker login ghcr.io -u gfkmkl --password-stdin
```

Expected: `Login Succeeded`

- [ ] **Step 4: 이미지 빌드**

Run:
```
cd C:\programmers_prj\beadv6_6_3JMT_BE\product-service\docker\elasticsearch
```
```
docker build -t ghcr.io/prgrms-be-adv-devcourse/prompthub-elasticsearch:9.4.3-nori .
```

Expected: 베이스 이미지(약 700MB) 다운로드 후 플러그인 설치.
마지막 줄이 `naming to ghcr.io/prgrms-be-adv-devcourse/prompthub-elasticsearch:9.4.3-nori`.
수 분 소요.

- [ ] **Step 5: 푸시**

Run:
```
docker push ghcr.io/prgrms-be-adv-devcourse/prompthub-elasticsearch:9.4.3-nori
```

Expected: 레이어 업로드 후 `9.4.3-nori: digest: sha256:... size: ...`

- [ ] **Step 6: 레지스트리에서 다시 받아 검증**

로컬 이미지로 확인하면 "푸시가 제대로 됐는지"가 검증되지 않는다. 반드시 지우고 다시 받는다.

Run:
```
docker rmi ghcr.io/prgrms-be-adv-devcourse/prompthub-elasticsearch:9.4.3-nori
```
```
docker pull ghcr.io/prgrms-be-adv-devcourse/prompthub-elasticsearch:9.4.3-nori
```
```
docker run --rm ghcr.io/prgrms-be-adv-devcourse/prompthub-elasticsearch:9.4.3-nori bin/elasticsearch-plugin list
```

Expected: `analysis-nori`

**이 출력이 안 나오면 Task 2 이후로 진행하지 않는다.**

---

## Task 2: GHCR 패키지 공개 설정

**Files:** 없음 (GitHub 웹 UI)

**Interfaces:**
- Consumes: Task 1이 퍼블리시한 패키지
- Produces: `elk` 네임스페이스에서 인증 없이 pull 가능한 상태

**왜 필요한가**: GHCR 패키지는 기본 private으로 생성된다. 기존 서비스들은
`k8s/base/services/*/deployment.yaml:34`에서 `imagePullSecrets: [name: ghcr-pull-secret]`을
쓰는데, **그 시크릿은 `prompthub` 네임스페이스에만 있다.** 시크릿은 네임스페이스 스코프라
`elk`에서는 쓸 수 없다.

private으로 두려면 `elk`에 시크릿을 새로 만들고 `elasticsearch.yaml`에 `imagePullSecrets`를
추가해야 하는데, 그러면 담당자 소유 매니페스트의 수정 범위가 커진다. **공개 이미지는 순정
Elasticsearch에 오픈소스 플러그인 하나를 얹은 것뿐이라 비공개로 둘 이유가 없다.**

- [ ] **Step 1: 패키지 가시성 확인**

브라우저에서 열기:
```
https://github.com/orgs/prgrms-be-adv-devcourse/packages
```

`prompthub-elasticsearch` 항목의 Private/Public 표시 확인.

- [ ] **Step 2: Public으로 변경 (Private인 경우)**

패키지 → **Package settings** → 하단 **Danger Zone** → **Change visibility** → **Public**
→ 패키지 이름 입력 후 확인.

- [ ] **Step 3: 인증 없이 pull 되는지 검증**

Run:
```
docker logout ghcr.io
```
```
docker rmi ghcr.io/prgrms-be-adv-devcourse/prompthub-elasticsearch:9.4.3-nori
```
```
docker pull ghcr.io/prgrms-be-adv-devcourse/prompthub-elasticsearch:9.4.3-nori
```

Expected: 인증 없이 다운로드 성공.
실패하면 아직 private이므로 Step 2를 다시 확인한다.

---

## Task 3: elk 매니페스트의 image 한 줄 변경 PR

**Files:**
- Modify: `k8s/addons/elk/elasticsearch.yaml:169`

**Interfaces:**
- Consumes: Task 1의 이미지 태그, Task 2의 공개 상태
- Produces: develop에 머지된 매니페스트 — Task 5의 배포가 이 이미지로 뜬다

**브랜치 전략**: PR #567은 이미 develop에 머지됐으므로 남의 브랜치를 건드릴 필요가 없다.
develop에서 새 브랜치를 파는 정상 흐름(`create-branch` 스킬 규칙)을 따른다.

- [ ] **Step 1: 최신 develop에서 브랜치 생성**

Run:
```
cd C:\programmers_prj\beadv6_6_3JMT_BE
```
```
git checkout develop
```
```
git pull origin develop
```
```
git checkout -b chore/#583-es-nori-image
```

- [ ] **Step 2: 변경 전 상태 확인**

Run:
```
git grep -n "image: docker.elastic.co" -- k8s/addons/elk/elasticsearch.yaml
```

Expected:
```
k8s/addons/elk/elasticsearch.yaml:169:          image: docker.elastic.co/elasticsearch/elasticsearch:9.4.3
```

- [ ] **Step 3: image 한 줄 수정**

`k8s/addons/elk/elasticsearch.yaml`의 169번 줄을 아래로 바꾼다.
`initContainers`의 `busybox:1.37.0`(147번 줄)은 **건드리지 않는다.**

```yaml
      containers:
        - name: elasticsearch
          image: ghcr.io/prgrms-be-adv-devcourse/prompthub-elasticsearch:9.4.3-nori
          imagePullPolicy: IfNotPresent
```

- [ ] **Step 4: 렌더링 검증**

Run (Git Bash):
```
kubectl kustomize k8s/addons/elk | grep "image:"
```
PowerShell이면: `kubectl kustomize k8s/addons/elk | Select-String "image:"`

Expected: `prompthub-elasticsearch:9.4.3-nori`가 보이고, `docker.elastic.co/elasticsearch`는
더 이상 없다. busybox(initContainer)·logstash·kibana·fluent-bit 이미지는 그대로.

- [ ] **Step 5: 정적 검증 스크립트**

Run:
```
bash scripts/validate-k8s-manifests.sh
```

Expected: 통과. (PR #567이 이 스크립트에 ELK 계약을 추가해뒀다)

- [ ] **Step 6: 커밋**

```
git add k8s/addons/elk/elasticsearch.yaml
```
```
git commit -m "chore: ES 이미지를 nori 플러그인 포함 커스텀 이미지로 교체 (#583)"
```
```
git push -u origin chore/#583-es-nori-image
```

- [ ] **Step 7: PR 생성**

`create-github-pr` 스킬 절차를 따르되, 본문에 아래 내용이 반드시 들어가야 한다:

- `products-v1` 매핑이 `nori_tokenizer`를 참조하므로 순정 이미지로는 인덱스 생성이 실패한다
- 실패가 조용하다 — `reconcileAll()`이 `indexExists()` 가드에서 스킵하고 검색이 RDB 폴백으로
  넘어가 겉보기 정상이 된다
- 이미지는 `product-service/docker/elasticsearch/Dockerfile`(로컬 개발용)을 그대로 빌드한 것.
  순정 9.4.3 + `analysis-nori`만 추가
- `docker run ... bin/elasticsearch-plugin list`로 레지스트리 pull 후 검증 완료
- `Closes #583`

---

## Task 4: 담당자에게 배포 전 전달

**Files:** 없음 (GitHub 코멘트 또는 팀 채널)

**Interfaces:**
- Consumes: Task 3의 PR 번호
- Produces: 배포 순서 합의 — Task 5가 안전하게 실행될 조건

**왜 지금 보내는가**: 이미지 푸시는 클러스터에 영향이 없어 언제 해도 되지만, 담당자가
`kubectl apply -k k8s/addons/elk`를 먼저 실행하면 되돌리기 번거로운 상태가 된다. **알리는 데
드는 비용이 몇 초이고, 안 알렸을 때 손실이 크므로 Task 1과 병행해 먼저 보낸다.**

- [ ] **Step 1: 메시지 전달**

아래 4가지가 반드시 포함되어야 한다.

```
elk 적용 전에 확인 부탁드립니다.

1. 이미지 교체 필요
현재 elasticsearch.yaml이 순정 이미지라 그대로 apply하면 products-v1 인덱스 생성이
실패합니다(nori 플러그인 없음). product-service는 RDB 폴백으로 넘어가서 에러 없이
검색만 저품질이 돼요 — 겉으로는 정상으로 보입니다.
nori 포함 이미지를 GHCR에 올렸고 image 한 줄 바꾸는 PR(#583) 올렸습니다.
그거 머지 후에 apply해주세요.

2. 배포 순서
기존 deployment/elasticsearch와 새 statefulset/elasticsearch가 같은 이름·같은
네임스페이스(elk)입니다. 종류가 달라 동시에 존재할 수 있는데, Service 셀렉터가 라벨
기준이라 양쪽 파드에 트래픽이 갈 수 있습니다. 기존 Deployment를 먼저 지우고
StatefulSet을 적용해주세요.

3. 로그 유실 경고
지금 elk에 application-logs-2026.07.23~26 (4일치, 약 6MB)가 쌓여 있습니다.
ES 교체하면 유실되고 복구가 안 됩니다. 알고 계신 파이프라인인가요?
새 구성(gateway-access-*)과 인덱스 이름이 달라서 중복 수집 가능성도 있어 보입니다.
products-v1은 65건이라 재색인 몇 초면 되니 백업 불필요합니다.

4. #588 타이밍
CI/CD 분리 PR(#588)이 리뷰 중인데, ELK 테스트 동안에는 머지를 미뤄주실 수 있을까요?
CD 동작이 도중에 바뀌면 결과 해석이 어려워질 것 같습니다.

혹시 elk를 자동으로 적용하는 스크립트가 따로 있나요? 제가 확인한 CD 워크플로에는
k8s/addons/elk가 없어서 수동 적용으로 이해하고 있습니다.
```

- [ ] **Step 2: 회신 확인**

특히 3번(로그 파이프라인 인지 여부)과 마지막 질문(자동 적용 스크립트 존재 여부)의 답을
받아야 Task 5의 전제가 성립한다.

---

## Task 5: 배포 후 검증

**Files:** 없음 (클러스터 조회)

**Interfaces:**
- Consumes: Task 3 머지, Task 4 합의, 담당자의 apply 완료
- Produces: "nori 한글 검색이 실제로 동작한다"는 확인

**검증 순서가 중요한 이유**: 폴백 때문에 겉보기로는 정상·비정상이 구분되지 않는다.
아래 순서는 **가장 근본적인 것부터** 확인해 실패 지점을 좁힌다.

- [ ] **Step 1: 검색 테스트에 쓸 상품명 미리 확보 (배포 전)**

Run (기존 ES에서):
```
kubectl exec -n elk deploy/elasticsearch -- curl -s "localhost:9200/products/_search?size=10&_source=name&pretty"
```

Expected: 상품명 10개. **여러 단어로 된 이름**을 2~3개 메모해둔다
(예: `시니어 코드리뷰 프롬프트`). Step 6에서 그 **중간 단어**로 검색할 것이다.

- [ ] **Step 2: nori 설치 확인 — 가장 먼저**

Run:
```
kubectl exec -n elk statefulset/elasticsearch -- curl -s localhost:9200/_cat/plugins
```

Expected: `analysis-nori` 포함.

**비어 있으면 여기서 중단한다.** 이미지 교체가 반영되지 않은 것이므로 아래 단계는 의미가 없다.
`kubectl get statefulset elasticsearch -n elk -o jsonpath='{.spec.template.spec.containers[0].image}'`
로 실제 적용된 이미지를 확인한다.

- [ ] **Step 3: 파드가 정상 기동했는지**

Run:
```
kubectl get pod -n elk -o wide
```

Expected: `elasticsearch-0` Running, **control-plane 노드**에 배치.
`ImagePullBackOff`면 Task 2(패키지 공개)가 미완료다.

- [ ] **Step 4: 인덱스 생성 확인**

Run:
```
kubectl exec -n elk statefulset/elasticsearch -- curl -s "localhost:9200/_cat/indices?v"
```

Expected: `products-v1`이 green 또는 yellow.

- [ ] **Step 5: product-service 연결 및 색인 확인**

수동 재색인 트리거는 **불필요하다** — `ProductReconcileScheduler`가 20초마다
`reconcileAll()`(전체 재조정)을 실행하므로 자동으로 채워진다.

Run (배포 후 30초 이상 경과한 뒤):
```
kubectl logs -n prompthub deploy/product-service --tail=50
```

Expected: `index=products-v1 생성 및 alias=products 연결 완료` 또는
`alias=products 이미 존재`, 그리고 `전체 재조정 완료. upsert=N`.

**`ES 인덱스가 아직 없어 이번 재조정 사이클을 건너뜁니다`가 반복되면** Step 2 실패 상태다.

Run:
```
kubectl exec -n elk statefulset/elasticsearch -- curl -s "localhost:9200/products/_count"
```

Expected: `{"count":65,...}` 부근 (기존과 동일한 family 수).

- [ ] **Step 6: 한글 형태소 검색 실제 확인 — 최종 판정**

브라우저에서 `/browse` 접속 후, Step 1에서 메모한 상품명의 **중간 단어**로 검색한다.

Expected: 해당 상품이 결과에 나온다.

**이것이 핵심 검증이다.** 앞 단계가 모두 통과해도 nori가 실제로 형태소를 쪼개는지는 검색해봐야
안다. 글자 단위로만 매칭되면 중간 단어 검색이 실패한다.

- [ ] **Step 7: ES_URIS 설정 확인**

Run:
```
kubectl get secret runtime-secret -n prompthub -o jsonpath="{.data.ES_URIS}" | base64 -d
```

Expected: `http://elasticsearch.elk.svc.cluster.local:9200`

시크릿 이름은 `runtime-secret`(`k8s/templates/runtime-values.example.yaml:23`), 값은 같은
파일 34번 줄 기준. 다른 값이면 product-service가 새 ES를 못 찾으므로 시크릿을 갱신하고
`kubectl rollout restart deploy/product-service -n prompthub`로 재기동한다.

---

## 이 계획에서 제외한 것

- **#585·#586·#582·#378 구현** — 내일 테스트와 무관하다. 현재 20초 전체 재조정이 65건 규모에서
  문제없이 동작하므로, #585를 미리 구현해도 테스트 결과가 달라지지 않는다
- **임베딩 검증** — #378이 미구현이다. `product-service/src/main/java`에 OpenAI·embedding 관련
  코드가 0건이고 환경변수도 없다. OpenAI 키를 준비해뒀더라도 사용하는 코드가 없어
  **내일 확인할 대상이 아니다.** 확인 가능한 최대치는 nori 한글 검색까지다
- **테스트 상품 데이터 생성** — 이미 65건이 색인돼 있어 INSERT문이 필요 없다
- **수동 재색인 트리거** — 20초 스케줄러가 같은 `reconcileAll()`을 호출한다
- **ES 데이터 백업** — `products-v1`은 RDB에서 재생성 가능하고 65건이라 수 초면 끝난다

## 미해결 항목 (내일 논의 재료)

**ES 파드가 3일간 6회 재시작했다.** 마지막 종료가 `Exit Code: 255 / Reason: Unknown`으로
OOMKilled가 아니다(그랬다면 `137`). 14시간 가동 후 종료라 빠른 크래시 루프도 아니다.
이벤트는 이미 만료됐다.

원인 확인용 (급하지 않음):
```
kubectl logs -n elk deploy/elasticsearch --previous | tail -60
```
```
kubectl get deploy elasticsearch -n elk -o yaml | grep -A8 resources
```
```
sudo dmesg -T | grep -i oom | tail
```

메모리 limit이 없으면 쿠버네티스가 OOM을 기록하지 못하고 호스트 커널이 죽인다 — 그 경우
정확히 `Unknown`/`255`로 보인다. `dmesg`에 흔적이 있으면 결국 메모리 문제다.

**이게 중요한 이유**: 같은 heap 1GB 노드에 앞으로 `gateway-access-*`(#567),
`logs-prompthub.application/infrastructure`(#575, 전 서비스+인프라 로그), 결제 감사로그(#540),
`behavior-logs`(#381), 그리고 #378의 벡터 인덱스가 순차로 들어온다. **이미 6번 죽은 노드에
그만큼을 더 얹는 상황**이므로, 재시작 기록은 "로그 스트림 추가 전에 메모리 증설을 검토하자"는
근거가 된다.
