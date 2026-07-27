# `elk-secrets.example.yaml`을 찾을 수 없는 문제

## 증상

다음 복사 명령이 실패한다.

```bash
cp \
  /home/ubuntu/prompthub/k8s/templates/elk-secrets.example.yaml \
  /home/ubuntu/prompthub-secrets/elk-secret.yaml
```

오류:

```text
cp: cannot stat '/home/ubuntu/prompthub/k8s/templates/elk-secrets.example.yaml': No such file or directory
```

## 원인

EC2에 저장소 전체가 아니라 `k8s/addons/elk` 디렉터리만 복사되어
`k8s/templates/elk-secrets.example.yaml`이 없다. Secret 예제는 add-on의
Kustomize 리소스에 포함되지 않으므로 별도로 복사해야 한다.

## 진단

```bash
find /home/ubuntu/prompthub \
  -name 'elk-secrets.example.yaml' \
  -type f
```

결과가 없으면 예제 파일이 EC2에 없는 것이다.

## 해결

Mac의 저장소 루트에서 예제 파일을 복사한다.

```bash
ssh -i <PEM_KEY_PATH> ubuntu@<CONTROL_PLANE_PUBLIC_IP> \
  'mkdir -p /home/ubuntu/prompthub/k8s/templates'

scp -i <PEM_KEY_PATH> \
  k8s/templates/elk-secrets.example.yaml \
  ubuntu@<CONTROL_PLANE_PUBLIC_IP>:/home/ubuntu/prompthub/k8s/templates/
```

EC2에서 실제 Secret 파일을 준비한다.

```bash
install -d -m 0700 /home/ubuntu/prompthub-secrets

cp \
  /home/ubuntu/prompthub/k8s/templates/elk-secrets.example.yaml \
  /home/ubuntu/prompthub-secrets/elk-secret.yaml

chmod 600 /home/ubuntu/prompthub-secrets/elk-secret.yaml
```

다음 네 값은 각각 다른 안전한 값으로 바꾼다.

- Logstash HTTP password
- Kibana encrypted saved objects key
- Kibana security key
- Kibana reporting key

임의 값은 `openssl rand -hex 32`로 생성할 수 있다.

## 적용과 검증

```bash
kubectl create namespace elk --dry-run=client -o yaml | kubectl apply -f -

kubectl apply --dry-run=client \
  -f /home/ubuntu/prompthub-secrets/elk-secret.yaml

kubectl apply \
  -f /home/ubuntu/prompthub-secrets/elk-secret.yaml

kubectl -n elk get secret \
  logstash-http-credentials \
  kibana-encryption
```

정상이라면 각 Secret의 `DATA`가 다음과 같다.

```text
logstash-http-credentials   2
kibana-encryption           3
```

## 주의사항

- 실제 `elk-secret.yaml`을 Git 저장소 안에 두지 않는다.
- Secret 값을 터미널 출력이나 트러블슈팅 문서에 붙여넣지 않는다.
- `elk` namespace를 삭제하면 Secret도 함께 삭제되므로 재적용해야 한다.
