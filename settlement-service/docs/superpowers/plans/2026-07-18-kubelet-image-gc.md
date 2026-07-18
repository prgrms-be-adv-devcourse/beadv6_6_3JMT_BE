# Kubernetes 노드 이미지 GC 기준 조정 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 두 EC2 Kubernetes 노드의 kubelet 이미지 GC 기준을 60/50으로 낮추고 운영 문서와 검증·복구 절차를 남긴다.

**Architecture:** 이미지 수명 주기는 kubelet GC만 관리하고 외부 prune 작업은 추가하지 않는다. Worker와 Control Plane을 한 번에 하나씩 변경하며, 각 노드는 설정 백업 후 kubelet만 재시작하고 Ready·Pod·Local PV 상태를 확인한 다음 다음 노드로 진행한다.

**Tech Stack:** Kubernetes 1.36.2, kubeadm, kubelet, containerd 2.2.5, EC2, SSH, Bash, Markdown

## Global Constraints

- `imageGCHighThresholdPercent: 60`을 두 노드에 명시한다.
- `imageGCLowThresholdPercent: 50`을 두 노드에 명시한다.
- `imageMinimumGCAge: 0s`와 `imageMaximumGCAge: 0s`를 유지해 기간 기반 보존을 두지 않는다.
- Worker를 먼저 적용하고 검증이 끝난 뒤 Control Plane을 적용한다.
- 두 노드를 동시에 재시작하거나 drain하지 않는다.
- `crictl rmi --prune`, `ctr images rm`, cron 기반 외부 이미지 정리를 추가하지 않는다.
- StatefulSet, PVC, PV와 `/var/lib/prompthub`의 Local PV 데이터를 수정하거나 삭제하지 않는다.
- Deployment의 기존 `revisionHistoryLimit: 1`, 불변 Git SHA 이미지 태그와 GHCR 기반 롤백을 유지한다.
- 기존 설정 파일을 노드별로 백업하고 kubelet이 실패하면 즉시 복원한다.
- 현재 확인된 SSH endpoint는 Control Plane `54.116.129.184`, Worker `13.209.136.116`이며 연결이 실패하면 주소 또는 보안 그룹을 확인하기 전까지 원격 변경을 중단한다.

---

### Task 1: 운영 가이드에 이미지 GC 기준 추가

**Files:**
- Modify: `k8s/README.md`

**Interfaces:**
- Consumes: `settlement-service/docs/superpowers/specs/2026-07-18-kubelet-image-gc-design.md`의 60/50/0s 결정과 노드별 적용 순서
- Produces: 운영자가 재구성과 장애 복구에 사용할 kubelet 이미지 GC 실행 가이드

- [ ] **Step 1: 기존 문서 위치를 확인한다**

Run:

```bash
sed -n '245,280p' k8s/README.md
```

Expected: `## 이미지가 내려오는 방식` 다음에 `## 삭제와 복구 주의사항`이 이어진다.

- [ ] **Step 2: 이미지 GC 운영 기준과 적용·검증·복구 절차를 추가한다**

`## 이미지가 내려오는 방식` 아래에 다음 내용을 추가한다.

````markdown
### kubelet 이미지 GC 운영 기준

두 EC2 노드는 containerd 이미지와 Local PV를 같은 루트 디스크에서 사용하므로 kubelet 기본
이미지 GC 상한 85%를 사용하지 않는다. Control Plane과 Worker의
`/var/lib/kubelet/config.yaml`에 다음 값을 유지한다.

```yaml
imageGCHighThresholdPercent: 60
imageGCLowThresholdPercent: 50
imageMinimumGCAge: 0s
imageMaximumGCAge: 0s
```

이미지 사용률이 60%를 넘으면 kubelet이 미사용 이미지를 정리해 50% 수준까지 낮춘다.
기간 기반 강제 삭제와 `crictl rmi --prune`, `ctr images rm` 같은 외부 정리 작업은 사용하지
않는다. Deployment는 `revisionHistoryLimit: 1`로 직전 ReplicaSet을 보존하고, 로컬 이미지가
GC된 경우에도 GHCR의 불변 Git SHA 태그를 다시 pull해 롤백한다.

설정은 Worker, Control Plane 순서로 한 노드씩 적용한다. 각 노드에서 기존 설정을 백업하고
kubelet만 재시작한 뒤 `systemctl is-active kubelet`, `kubectl get nodes`, 전체 Pod 상태를
검증한다. kubelet이 시작하지 않으면 백업을 복원하고 다음 노드 적용을 중단한다. drain,
StatefulSet 재시작, PVC/PV와 `/var/lib/prompthub` 삭제는 수행하지 않는다.
````

- [ ] **Step 3: 문서의 필수 기준을 정적으로 검증한다**

Run:

```bash
rg -n "imageGCHighThresholdPercent: 60|imageGCLowThresholdPercent: 50|imageMaximumGCAge: 0s|revisionHistoryLimit: 1|crictl rmi --prune" k8s/README.md
```

Expected: 다섯 기준이 모두 새 운영 섹션에서 검색된다.

- [ ] **Step 4: Kubernetes 저장소 검증을 실행한다**

Run:

```bash
bash scripts/validate-k8s-manifests.sh
```

Expected: exit code 0이며 모든 Kustomize와 CD 경계 검증이 통과한다.

- [ ] **Step 5: 공통 운영 문서만 커밋한다**

```bash
git add k8s/README.md
git commit -m "docs: Kubernetes 이미지 GC 운영 절차 추가"
```

Expected: 커밋에는 `k8s/README.md` 한 파일만 포함된다.

### Task 2: 원격 변경 전 연결과 기준 상태 확인

**Files:**
- Read: `/var/lib/kubelet/config.yaml` on Control Plane and Worker
- Read: `/var/lib/containerd`, `/var/lib/prompthub` on Control Plane and Worker

**Interfaces:**
- Consumes: SSH key `/Users/taetaetae/3JMT.pem`, Control Plane `54.116.129.184`, Worker `13.209.136.116`
- Produces: 변경 전 노드·Pod·디스크·설정 기준 상태와 원격 변경 진행 가능 여부

- [ ] **Step 1: 두 SSH endpoint의 연결을 확인한다**

Run:

```bash
ssh -i /Users/taetaetae/3JMT.pem -o BatchMode=yes -o ConnectTimeout=10 ubuntu@54.116.129.184 hostname
ssh -i /Users/taetaetae/3JMT.pem -o BatchMode=yes -o ConnectTimeout=10 ubuntu@13.209.136.116 hostname
```

Expected: 두 명령 모두 exit code 0과 서로 다른 hostname을 반환한다. 시간 초과면 공인 IP,
인스턴스 상태 또는 SSH 보안 그룹을 확인할 때까지 Task 3 이후를 실행하지 않는다.

- [ ] **Step 2: 클러스터 기준 상태를 확인한다**

Run on Control Plane:

```bash
kubectl get nodes -o wide
kubectl -n prompthub get pods -o wide
kubectl -n nginx-ingress get pods -o wide
kubectl get pv
kubectl -n prompthub get pvc
kubectl -n prompthub rollout status deployment/settlement-service --timeout=60s
```

Expected: 두 노드가 `Ready`, 모든 Pod가 Ready, PV/PVC가 `Bound`, settlement-service rollout이
완료 상태다. 하나라도 실패하면 설정 변경 전에 원인을 해결한다.

- [ ] **Step 3: 두 노드의 서비스와 현재 설정을 확인한다**

Run on each node:

```bash
systemctl is-active kubelet
systemctl is-active containerd
sudo grep -E '^(imageGCHighThresholdPercent|imageGCLowThresholdPercent|imageMinimumGCAge|imageMaximumGCAge):' /var/lib/kubelet/config.yaml
```

Expected: kubelet과 containerd가 `active`, 최소·최대 age가 `0s`다. high/low가 없으면 기본
85/80을 사용하는 현재 상태로 기록한다.

- [ ] **Step 4: 두 노드의 디스크와 이미지 기준 상태를 확인한다**

Run on each node:

```bash
df -h / /var/lib/containerd /var/lib/prompthub
sudo du -sh /var/lib/containerd /var/lib/prompthub
sudo crictl images
```

Expected: 각 경로가 접근 가능하고 루트 파일시스템에 설정 변경과 kubelet 재시작을 수행할
여유 공간이 있다. 명령은 이미지를 삭제하지 않는다.

### Task 3: Worker kubelet 이미지 GC 설정 적용

**Files:**
- Modify: `/var/lib/kubelet/config.yaml` on Worker
- Create: `/var/lib/kubelet/config.yaml.bak-20260718-before-image-gc` on Worker

**Interfaces:**
- Consumes: Task 2에서 확인한 정상 Worker와 기존 kubelet 설정
- Produces: 60/50/0s 이미지 GC 설정을 사용하며 Ready 상태인 Worker

- [ ] **Step 1: Worker 설정 백업 경로가 비어 있는지 확인하고 백업한다**

Run on Worker:

```bash
sudo test ! -e /var/lib/kubelet/config.yaml.bak-20260718-before-image-gc
sudo cp --preserve=all /var/lib/kubelet/config.yaml /var/lib/kubelet/config.yaml.bak-20260718-before-image-gc
```

Expected: 두 명령이 exit code 0이다. 백업이 이미 있으면 덮어쓰지 말고 현재 설정과 비교한 뒤
작업을 중단한다.

- [ ] **Step 2: 네 가지 이미지 GC 값을 한 번의 파일 갱신으로 적용한다**

Run on Worker:

```bash
sudo sed -i \
  -e '/^imageGCHighThresholdPercent:/d' \
  -e '/^imageGCLowThresholdPercent:/d' \
  -e '/^imageMinimumGCAge:/d' \
  -e '/^imageMaximumGCAge:/d' \
  -e '/^apiVersion:/a imageGCHighThresholdPercent: 60\nimageGCLowThresholdPercent: 50\nimageMinimumGCAge: 0s\nimageMaximumGCAge: 0s' \
  /var/lib/kubelet/config.yaml
```

Expected: 다른 kubeadm 설정은 유지되고 네 키가 top-level에 한 번씩 존재한다.

- [ ] **Step 3: 재시작 전에 설정값과 중복을 검증한다**

Run on Worker:

```bash
sudo grep -nE '^(imageGCHighThresholdPercent|imageGCLowThresholdPercent|imageMinimumGCAge|imageMaximumGCAge):' /var/lib/kubelet/config.yaml
```

Expected:

```text
imageGCHighThresholdPercent: 60
imageGCLowThresholdPercent: 50
imageMinimumGCAge: 0s
imageMaximumGCAge: 0s
```

각 키의 검색 결과는 한 줄이어야 한다. 값이나 개수가 다르면 백업을 복원하고 재시작하지 않는다.

- [ ] **Step 4: Worker kubelet을 재시작한다**

Run on Worker:

```bash
sudo systemctl restart kubelet
systemctl is-active kubelet
systemctl is-active containerd
```

Expected: 두 서비스가 모두 `active`다.

- [ ] **Step 5: Worker 적용 후 클러스터 상태를 검증한다**

Run on Control Plane:

```bash
kubectl wait --for=condition=Ready node --all --timeout=60s
kubectl -n prompthub get pods -o wide
kubectl -n nginx-ingress get pods -o wide
```

Expected: 두 노드가 Ready이고 기존 Pod가 모두 Ready다. 실패하면 Worker에서 다음 명령으로
복구하고 Control Plane 적용을 중단한다.

```bash
sudo cp --preserve=all /var/lib/kubelet/config.yaml.bak-20260718-before-image-gc /var/lib/kubelet/config.yaml
sudo systemctl restart kubelet
```

### Task 4: Control Plane kubelet 이미지 GC 설정 적용

**Files:**
- Modify: `/var/lib/kubelet/config.yaml` on Control Plane
- Create: `/var/lib/kubelet/config.yaml.bak-20260718-before-image-gc` on Control Plane

**Interfaces:**
- Consumes: Task 3에서 검증된 Ready Worker와 정상 클러스터
- Produces: 60/50/0s 이미지 GC 설정을 사용하며 Ready 상태인 Control Plane

- [ ] **Step 1: Control Plane 설정 백업 경로가 비어 있는지 확인하고 백업한다**

Run on Control Plane:

```bash
sudo test ! -e /var/lib/kubelet/config.yaml.bak-20260718-before-image-gc
sudo cp --preserve=all /var/lib/kubelet/config.yaml /var/lib/kubelet/config.yaml.bak-20260718-before-image-gc
```

Expected: 두 명령이 exit code 0이며 기존 백업을 덮어쓰지 않는다.

- [ ] **Step 2: Worker와 같은 네 가지 이미지 GC 값을 적용하고 검증한다**

Run on Control Plane:

```bash
sudo sed -i \
  -e '/^imageGCHighThresholdPercent:/d' \
  -e '/^imageGCLowThresholdPercent:/d' \
  -e '/^imageMinimumGCAge:/d' \
  -e '/^imageMaximumGCAge:/d' \
  -e '/^apiVersion:/a imageGCHighThresholdPercent: 60\nimageGCLowThresholdPercent: 50\nimageMinimumGCAge: 0s\nimageMaximumGCAge: 0s' \
  /var/lib/kubelet/config.yaml

sudo grep -nE '^(imageGCHighThresholdPercent|imageGCLowThresholdPercent|imageMinimumGCAge|imageMaximumGCAge):' /var/lib/kubelet/config.yaml
```

Expected: 네 키가 60/50/0s 값으로 한 번씩 존재한다. 다르면 백업을 복원하고 재시작하지 않는다.

- [ ] **Step 3: Control Plane kubelet을 재시작하고 서비스 상태를 확인한다**

Run on Control Plane:

```bash
sudo systemctl restart kubelet
systemctl is-active kubelet
systemctl is-active containerd
```

Expected: 두 서비스가 모두 `active`다.

- [ ] **Step 4: Control Plane 적용 후 클러스터 상태를 검증한다**

Run on Control Plane:

```bash
kubectl wait --for=condition=Ready node --all --timeout=60s
kubectl -n prompthub get pods -o wide
kubectl -n nginx-ingress get pods -o wide
```

Expected: 두 노드와 모든 기존 Pod가 Ready다. 실패하면 다음 명령으로 복구한다.

```bash
sudo cp --preserve=all /var/lib/kubelet/config.yaml.bak-20260718-before-image-gc /var/lib/kubelet/config.yaml
sudo systemctl restart kubelet
```

### Task 5: 최종 검증과 작업 트리 확인

**Files:**
- Verify: `k8s/README.md`
- Verify: `/var/lib/kubelet/config.yaml` on Control Plane and Worker

**Interfaces:**
- Consumes: Task 1의 운영 문서와 Task 3~4의 노드 설정
- Produces: #403 완료 여부를 판단할 수 있는 저장소·클러스터·디스크 검증 결과

- [ ] **Step 1: 두 노드의 최종 설정과 서비스를 재확인한다**

Run on each node:

```bash
systemctl is-active kubelet
systemctl is-active containerd
sudo grep -nE '^(imageGCHighThresholdPercent|imageGCLowThresholdPercent|imageMinimumGCAge|imageMaximumGCAge):' /var/lib/kubelet/config.yaml
df -h / /var/lib/containerd /var/lib/prompthub
sudo du -sh /var/lib/containerd /var/lib/prompthub
```

Expected: 서비스가 active이고 네 설정이 60/50/0s이며 디스크와 Local PV 경로가 정상이다.

- [ ] **Step 2: 전체 Kubernetes 상태를 재확인한다**

Run on Control Plane:

```bash
kubectl get nodes -o wide
kubectl -n prompthub get pods -o wide
kubectl -n nginx-ingress get pods -o wide
kubectl get pv
kubectl -n prompthub get pvc
kubectl -n prompthub rollout status deployment/settlement-service --timeout=60s
```

Expected: 노드와 Pod가 Ready, PV/PVC가 Bound, settlement-service rollout이 완료 상태다.

- [ ] **Step 3: 저장소 검증과 변경 범위를 확인한다**

Run:

```bash
bash scripts/validate-k8s-manifests.sh
git status --short
git log --oneline origin/develop..HEAD
```

Expected: 검증 exit code 0, 작업 트리가 깨끗하며 #403의 설계·구현 계획·운영 문서 커밋만 보인다.

- [ ] **Step 4: 완료 조건과 남은 장기 개선을 보고한다**

Report:

```text
두 노드 kubelet 이미지 GC: high 60 / low 50 / min age 0s / max age 0s
노드·Pod·Ingress·PV/PVC·settlement rollout: 정상
외부 containerd prune: 미설치
장기 개선: containerd와 Local PV용 EBS 분리
```
