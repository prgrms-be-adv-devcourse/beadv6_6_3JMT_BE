# Discovery (Eureka Server)

Netflix Eureka Server. 서비스 등록·조회 레지스트리 역할을 한다.

- **포트**: 8761
- **대시보드**: http://localhost:8761
- **기동 순서**: Config Server 다음 (두 번째)

---

## 핵심 파일

| 파일 | 역할 |
|------|------|
| `src/main/resources/application.yml` | Eureka Server 설정 (포트, self-registration 비활성화) |

---

## 설정 규칙

**Discovery는 config client를 붙이지 않는다 (ADR-0004).** 부트스트랩 인프라는 의존을
최소화하고, 환경별로 달라질 값이 거의 없다. 설정은 이 모듈의 `application.yaml`로 자체 완결한다.

로컬 단일 인스턴스 환경이므로 아래 두 옵션을 반드시 비활성화한다.

```yaml
eureka:
  client:
    register-with-eureka: false   # 자기 자신을 등록하지 않음
    fetch-registry: false         # 레지스트리를 로컬에 캐시하지 않음
```

이 설정이 없으면 Eureka가 자기 자신에게 등록을 시도하다 오류를 낸다.

---

## 참고

Spring Cloud 전체 흐름은 `docs/architecture/spring-cloud.md` 참조.
