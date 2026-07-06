# MSA 인프라 설정 사전 구성 (비활성화)

## 목표

로컬 개발 환경에서는 비활성화 상태로, 추후 MSA 인프라가 갖춰졌을 때 설정값만 바꿔 즉시 활성화할 수 있도록 Eureka Client · Config Server Client 의존성과 설정을 사전 구성한다.

## 배경 및 결정 사항

- Spring Cloud 버전: **2025.1.2** (팀 통일, discovery/config/apigateway 모듈 동일)
- Eureka 서버: `http://localhost:8761/eureka/`
- Config Server: `http://localhost:8888` (native 프로파일, classpath:/configs/ 로드)
- `configs/application.yml`에 Eureka 클라이언트 공통 설정이 이미 정의됨 — Config Server 활성화 시 자동으로 주입됨
- API Gateway 라우트 추가, Config Server `configs/payment-service.yml` 생성은 **인프라 담당자 영역** (이 작업 제외)
- Kafka는 이미 완전히 구현된 상태 — 이 작업에서 변경하지 않음

## 변경 파일

### 1. `build.gradle`

**추가 내용:**

```groovy
ext {
    set('springCloudVersion', "2025.1.2")
}
```

```groovy
// dependencies 블록에 추가
implementation 'org.springframework.cloud:spring-cloud-starter-netflix-eureka-client'
implementation 'org.springframework.cloud:spring-cloud-starter-config'
```

```groovy
// tasks.named('test') 앞에 추가
dependencyManagement {
    imports {
        mavenBom "org.springframework.cloud:spring-cloud-dependencies:${springCloudVersion}"
    }
}
```

### 2. `src/main/resources/application.yaml`

**추가 내용 (기존 설정 유지, 하단에 추가):**

```yaml
spring:
  cloud:
    config:
      enabled: false          # 활성화 시 true로 변경
      uri: http://localhost:8888

eureka:
  client:
    enabled: false            # 활성화 시 true로 변경
    service-url:
      defaultZone: http://localhost:8761/eureka/
```

> `spring.cloud.config.enabled=false`: Config Server 클라이언트 AutoConfiguration 완전 비활성화.
> `eureka.client.enabled=false`: Eureka 등록·조회 완전 비활성화.
> 활성화할 때는 두 값을 `true`로 바꾸고 각 서버가 실행 중인지 확인한다.

## 활성화 절차 (추후 참고)

1. discovery 서비스 실행 (port 8761)
2. config 서비스 실행 (port 8888)
3. `application.yaml`에서 `enabled: false` → `true` 두 곳 변경
4. Config Server가 `configs/payment-service.yml`을 제공하는지 확인 (인프라 담당자)
5. API Gateway에 payment-service 라우트 추가 여부 확인 (인프라 담당자)
6. payment-service 재시작 후 `http://localhost:8761` 에서 `PAYMENT-SERVICE` 등록 확인

## 검증 방법

- 빌드 통과: `./gradlew build`
- 기존 테스트 전원 통과: `./gradlew test`
- 로컬 실행 정상: `./gradlew bootRun` (Eureka/Config Server 없이 정상 시작되어야 함)
