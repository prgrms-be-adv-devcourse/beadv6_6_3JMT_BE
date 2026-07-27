# ClusterIP Kibana에 Mac에서 안전하게 접속하는 방법

## 증상

Kibana Pod와 Service가 정상인데 Mac 브라우저에서 EC2 공인 IP의 5601 포트로 접속할 수 없다.

```bash
kubectl -n elk get service kibana
```

Service 유형:

```text
TYPE: ClusterIP
PORT: 5601
```

## 원인

Elasticsearch security가 비활성화된 현재 단계에서는 Elasticsearch와 Kibana를 외부에 공개하지
않는 것이 보안 계약이다. Kibana Service는 의도적으로 ClusterIP이며 EC2 Security Group에도
5601을 개방하지 않는다.

## 해결

### 1. Control Plane EC2에서 port-forward

```bash
kubectl -n elk port-forward \
  --address 127.0.0.1 \
  service/kibana 5601:5601
```

이 터미널을 유지한다.

### 2. Mac에서 SSH local forwarding

```bash
ssh -N \
  -L 5601:127.0.0.1:5601 \
  -i <PEM_KEY_PATH> \
  ubuntu@<CONTROL_PLANE_PUBLIC_IP>
```

이 터미널도 유지한다.

### 3. 브라우저 접속

```text
http://127.0.0.1:5601
```

## 연결 확인

Mac에서:

```bash
curl -fsS http://127.0.0.1:5601/api/status
```

Kibana status JSON이 반환되면 터널이 정상이다.

## 연결 종료

두 터미널에서 `Ctrl+C`를 누른다.

## 주의사항

- EC2 Security Group에 5601, 9200, 8000을 개방하지 않는다.
- `kubectl port-forward --address 0.0.0.0`을 사용하지 않는다.
- Elasticsearch도 같은 방식으로 필요할 때만 로컬 port-forward한다.
- 현재 security disabled는 내부 HTTP 호환을 위한 임시 경계이며 외부 공개 허가가 아니다.
