# 터미널 출력과 설정 문장을 Bash 명령으로 실행한 문제

## 증상

`kubectl get` 결과 또는 Logstash 설정 일부를 다시 셸에 붙여넣은 뒤 다음 오류가 연속으로 발생한다.

```text
-bash: syntax error near unexpected token `('
NAME: command not found
-bash: deployment.apps/elasticsearch: No such file or directory
additional_codecs: command not found
Command 'codec' not found
```

터미널이 다음과 같은 설치 명령을 제안하기도 한다.

```text
sudo snap install codec
```

## 원인

표 형태의 명령 출력이나 Logstash DSL을 Bash prompt에 붙여넣었다. Bash는 각 줄을 실행 파일 또는
shell 문법으로 해석하려 하지만 해당 문자열은 명령이 아니므로 오류가 발생한다.

예를 들어 다음은 Logstash 설정이지 Bash 명령이 아니다.

```ruby
additional_codecs => {}
codec => json {
  target => "[ingest]"
}
```

## 영향

이 사례에서 출력된 `command not found`, `No such file or directory`, syntax error는 대부분
리소스를 변경하지 않는다. 실제 `kubectl delete`, `apply`, `scale` 명령이 포함되지 않았다면
Kubernetes 상태는 그대로다.

## 확인

```bash
kubectl -n elk get pods -o wide
kubectl -n elk get deployment,statefulset,daemonset,service
```

설정 파일 수정 여부도 확인한다.

```bash
git diff -- k8s/addons/elk
```

## 해결

- 설치 제안을 실행하지 않는다.
- 필요한 명령 블록만 복사한다.
- 출력 결과와 설정 예시는 셸에 붙여넣지 않는다.
- 여러 줄 명령은 첫 줄부터 마지막 줄까지 백슬래시를 포함해 복사한다.

파일 내용을 입력할 때는 편집기를 사용한다.

```bash
vi k8s/addons/elk/logstash.yaml
```

## 붙여넣기 모드 문자 문제

다음 문자열이 명령 앞에 붙는 경우도 있다.

```text
[200~kubectl
```

이는 터미널 bracketed paste 제어 문자가 그대로 입력된 경우다. 해당 줄을 취소하고 명령을
직접 다시 입력한다.

```text
Ctrl+C
```

## 정상 판정

- ELK Pod 상태가 붙여넣기 전과 동일
- 의도하지 않은 패키지가 설치되지 않음
- `git diff`에 의도하지 않은 파일 변경이 없음
