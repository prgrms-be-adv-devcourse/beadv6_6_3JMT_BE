# 멀티모듈 전환 후 빌드 실패 (IntelliJ 재임포트 · Docker 빌드)

## 환경

Gradle 9.5.1 / 멀티레포 → 단일 루트 멀티모듈 전환(#192) 이후

## 증상

루트 `./gradlew build` 는 정상 성공하는데, 아래 두 곳에서만 빌드가 깨진다.

- IntelliJ IDEA 에서 프로젝트 빌드/임포트가 실패한다.
- 서비스별 Docker 이미지 빌드(`docker compose build <service>`)가 실패한다.

공통점은 **CLI 전체 빌드는 되는데 CLI 밖(IDE·Docker)에서만 깨진다**는 것이다.

## 원인

전환 때 루트 `settings.gradle` 은 9개 모듈을 전부 `include` 하는 단일 루트 멀티모듈로 바뀌었다.
그런데 **Gradle CLI 가 아닌 설정(IDE 메타데이터·Dockerfile)** 은 멀티레포 시절 배선을 그대로 들고 있어서
새 구조와 어긋난다.

### 1. IntelliJ — 스테일 `.idea/gradle.xml`

`.idea/gradle.xml` 이 전환 전 구조를 그대로 들고 있었다.

- 모듈마다 독립된 Gradle 프로젝트로 9개가 따로 링크됨 — 각 `externalProjectPath` 가
  `$PROJECT_DIR$/<module>` 로, 자기 자신이 루트인 것처럼 잡혀 있었다. 정상이라면 레포 루트
  `$PROJECT_DIR$` 하나만 링크돼야 한다.
- 각 서비스에 `compositeBuild` 로 `common-module` 을 included build 로 물려놓음 — 멀티레포 때
  각 서비스가 독립 Gradle 프로젝트라 common 을 composite 로 끌어오던 배선이다. 지금은 common-module 이
  같은 루트의 서브프로젝트라 이 연결이 충돌한다.

즉 IntelliJ 는 "독립 프로젝트 9개 + 각자 common composite" 로 알고 있는데 실제 파일은
"루트 1개 + 서브프로젝트 9개" 라, 임포트/빌드 때 이 불일치로 깨진다. `.idea` 는 `.gitignore` 대상
(개인 환경 종속)이라 CLI 빌드 스크립트를 고쳐도 각자 IDE 메타데이터는 갱신되지 않는다.

### 2. Docker — `settings.gradle`(전 모듈 include) ↔ `Dockerfile`(일부만 COPY)

`Dockerfile` 이 멀티레포 방식대로 `common-module` 과 대상 모듈만 복사했다.

```dockerfile
COPY common-module ./common-module
COPY ${MODULE_PATH} ./${MODULE_PATH}   # 예: settlement-service 하나만
RUN ./gradlew :${MODULE_PATH}:bootJar --no-daemon
```

그런데 `settings.gradle` 은 9개 모듈을 전부 `include` 한다. 컨테이너 안에는 나머지 모듈 디렉토리가
없고, Gradle 9 는 없는 include 프로젝트를 만나면 설정 단계에서 바로 실패한다(예전 버전은 경고만 냈다).

```
Configuring project ':order-service' without an existing directory is not allowed.
The configured projectDirectory '.../order-service' does not exist
```

## 해결

### 1. IntelliJ — `.idea` 메타데이터 초기화 후 재임포트

IntelliJ 를 완전히 종료한 뒤(열려 있으면 종료 시 IDE 가 `.idea` 를 다시 써서 초기화가 무효화됨),
레포 루트에서 스테일 파일을 지우고 루트 `build.gradle` 로 다시 임포트한다.

```bash
rm -rf .idea/gradle.xml .idea/modules.xml .idea/modules .idea/*.iml
```

이후 IntelliJ 로 레포 루트 `build.gradle` 을 열어 Gradle import → 단일 루트 멀티모듈로 재생성된다.
Gradle JVM 은 빌드 toolchain 과 맞춰 JDK 21 로 둔다.

> 툴윈도우에서 링크된 Gradle 프로젝트 9개를 Unlink 하고 레포 루트만 다시 Link 해도 된다.

### 2. Docker — 소스 전체 복사

`Dockerfile` 에서 선택적 COPY 를 `COPY . .` 로 바꾼다. `.dockerignore` 가 이미
`.git`·`.idea`·`docs`·`style`·`**/build`·`**/.gradle` 을 제외하므로, 빌드 로직이나
`settings.gradle` 을 건드리지 않고 없는 모듈 디렉토리 문제만 해소된다.

```dockerfile
# 멀티모듈 루트 settings.gradle 이 전체 모듈을 include 하므로 소스 전체를 복사한다.
COPY . .
RUN chmod +x gradlew
RUN ./gradlew :${MODULE_PATH}:bootJar --no-daemon
```

`:${MODULE_PATH}:bootJar` 만 실행하므로 나머지 모듈은 configure 만 되고 실제로 컴파일되는 건
대상 서비스와 common-module 뿐이다. 이미지 레이어 캐시 효율은 다소 떨어지지만 빌드는 정상 동작한다.

## 결과

- IntelliJ 가 레포 루트 하나를 단일 멀티모듈 프로젝트로 인식해 IDE 빌드가 정상화된다.
- 서비스별 Docker 이미지가 정상 빌드된다. (`COPY . .` 시나리오로 `:settlement-service:bootJar`
  BUILD SUCCESSFUL 확인)

## 참고

- 원인은 코드가 아니라 전환 때 함께 갱신하지 않은 설정 불일치다. CLI 빌드만 보고 "빌드 정상" 으로
  판단하면 IDE·Docker 실패를 놓친다.
- Docker 는 `settings.gradle` 을 존재하는 디렉토리만 조건부 include 하는 방식으로도 풀 수 있지만,
  루트 `build.gradle` 이 `configure([project(':apigateway'), ...])` 로 전 모듈을 eager 참조하고 있어
  build.gradle 까지 같이 손봐야 한다. 그래서 변경 범위가 작은 `COPY . .` 를 택했다.
