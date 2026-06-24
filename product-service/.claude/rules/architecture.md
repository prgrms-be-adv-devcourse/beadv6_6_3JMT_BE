# 아키텍처 규칙

## 기준 구조

Product Service는 아래 패키지 구조를 기준으로 한다.

```text
product-service
  config
    global
  application
  domain
  infra
    messaging
      config
      consumer
      producer
    persistence
  presentation
    controller
```

이 구조는 Product Service 내부 구현 기준이다. 패키지명은 실제 Java package 규칙에 맞춰 `config.global`, `infra.messaging.config`처럼 작성한다.

## 계층 책임

### presentation

- HTTP 요청과 응답을 처리한다.
- Controller는 최소한의 호출만 수행한다.
- Controller에 비즈니스 로직, 계산, 상태 판단, DB 접근 로직을 넣지 않는다.
- Controller는 request DTO를 application 계층 입력값으로 변환하고, application 결과를 response DTO로 변환한다.
- JPA entity를 Controller 응답으로 직접 반환하지 않는다.

### application

- 유스케이스 흐름을 조율한다.
- 트랜잭션 경계를 담당한다.
- domain model과 repository port를 사용해 비즈니스 흐름을 완성한다.
- HTTP, Controller, JPA 구현체에 직접 의존하지 않는다.

### domain

- Product 핵심 비즈니스 규칙을 둔다.
- 도메인 모델, enum, repository port를 둔다.
- 특정 DB/JPA 구현체나 Web 계층에 의존하지 않는다.
- 상태 변경은 public setter가 아니라 의미 있는 도메인 메서드로 표현한다.

### infra

- 외부 기술 구현을 둔다.
- DB 접근 구현은 `infra.persistence`에 둔다.
- 메시징, Kafka 설정/consumer/producer는 `infra.messaging` 아래에 둔다.
- domain 또는 application에서 정의한 port를 구현한다.

### config.global

- 서비스 전역 설정을 둔다.
- CORS, 공통 Jackson 설정, 전역 Web 설정처럼 Product Service 전체에 적용되는 설정을 둔다.
- 특정 API의 비즈니스 로직은 config에 두지 않는다.

## 의존성 방향

기본 의존성 방향은 아래를 따른다.

```text
presentation -> application -> domain
infra -> domain/application port 구현
config -> 필요한 설정 등록
```

- `presentation`은 `application`을 호출한다.
- `application`은 `domain`을 사용한다.
- `infra`는 `domain` 또는 `application`에서 정의한 port를 구현한다.
- `domain`은 다른 계층에 의존하지 않는다.
- Controller가 repository/JPA 구현체를 직접 호출하지 않는다.

## 예외 처리 규칙

예외 처리는 `common-module`의 공통 구조를 따른다.

공통 기준:

- `com.prompthub.exception.BusinessException`
- `com.prompthub.exception.ErrorCode`
- `com.prompthub.exception.response.ErrorResponse`

Product 전용 예외가 필요하면 `ProductException`처럼 `BusinessException`을 상속한다.

```java
public class ProductException extends BusinessException {
    public ProductException(ErrorCode errorCode) {
        super(errorCode);
    }
}
```

Product 전용 에러 코드가 필요하면 Product Service 안에서 `ErrorCode` 구현체를 정의한다.
단, 공통으로 써야 하는 에러 코드는 `common-module` 구조를 우선 확인한다.

예외 응답은 `ErrorResponse.of(errorCode)` 또는 `ErrorResponse.of(errorCode, message)` 형식을 따른다.

주의사항:

- Controller에서 try/catch로 에러 응답을 직접 만들지 않는다.
- 예외는 전역 예외 처리에서 공통 형식으로 변환한다.
- stack trace, SQL 상세, framework 내부 예외 메시지를 client에 노출하지 않는다.
- Product 전용 예외/에러 코드는 필요한 경우에만 추가한다.

## 코드 스타일

- `style/checkstyle/prompthub-checkstyle-rules.xml`을 따른다.
- `style/checkstyle/prompthub-formatter.xml`을 따른다.
- wildcard import를 사용하지 않는다.
- unused import를 남기지 않는다.
- 빈 catch block을 남기지 않는다.
