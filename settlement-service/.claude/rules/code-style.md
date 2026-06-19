# 코드 스타일 컨벤션

Java 코드의 네이밍 케이스와 기본 스타일 규칙을 정의한다.
아래 규칙은 대부분 프로젝트 checkstyle 설정(`style/checkstyle/Prompthub-checkstyle-rules.xml`)으로 강제된다.

> 관련 문서: 계층·역할별 클래스 접미사(`~Controller`, `~UseCase` 등) 규칙은 `clean-architecture.md` 참고.

## 1. 네이밍 케이스

| 대상 | 규칙 | O | X | checkstyle |
| --- | --- | --- | --- | --- |
| 클래스명 | PascalCase | `UserService` | `userService` | `TypeName` |
| 메서드명 | camelCase | `getUser` | `GetUser` | `MethodName` |
| 변수명(지역·필드·파라미터) | camelCase | `userId` | `UserId` | `LocalVariableName` · `MemberName` · `ParameterName` |
| 상수명(`static final`) | UPPER_SNAKE_CASE | `MAX_COUNT` | `maxCount` | `ConstantName` |
| 패키지명 | 소문자 | `com.prompthub` | `com.PromptHub` | `PackageName` |

## 2. import

- **와일드카드 import 를 금지한다.** (`AvoidStarImport`)

  ```java
  import java.util.*;          // X
  import java.util.List;       // O
  import java.util.Map;        // O
  ```

- 사용하지 않는 import 는 남기지 않는다. (`UnusedImports`)

## 3. 예외 블록

- **빈 catch 블록을 금지한다.** (`EmptyCatchBlock`)
  예외를 삼키지 말고 최소한 로깅하거나 적절히 변환·전파한다.

  ```java
  try {
      ...
  } catch (IOException e) {
      // X — 빈 블록
  }

  try {
      ...
  } catch (IOException e) {
      log.warn("...", e);      // O — 로깅/변환/전파
      throw new SettlementException(...);
  }
  ```
