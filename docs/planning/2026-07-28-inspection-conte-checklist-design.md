# 프롬프트 상품 검수 - CONTE 체크리스트 판정 추가

## 배경

`InspectionPromptFactory`의 시스템 프롬프트는 현재 두 가지 반려 기준(금지/불법 콘텐츠, 스팸/저품질 콘텐츠)만 판단한다. PromptHub는 프롬프트 상품 마켓플레이스이므로, 등록된 프롬프트 상품의 본문(`content`)이 "좋은 프롬프트"의 구성 요소를 갖췄는지 참고 정보로 함께 받고 싶다는 요구가 있었다.

체크 대상 7개 요소 (CONTE + eXecution + 역할부여):

- **Context (배경 정보):** AI가 상황을 이해하는 데 필요한 배경 지식이나 문맥
- **Objective (목표):** AI에게 요구하는 구체적인 작업이나 목적
- **Nuance (뉘앙스와 세부 조건):** 작업 시 지켜야 할 구체적인 제약 조건이나 선호 사항
- **Tone (어조):** 결과물에 적용할 목소리의 톤과 스타일
- **Examples (예시):** AI에게 '좋은 결과물'이 무엇인지 보여주는 견본 (few-shot)
- **eXecution (실행 및 형식):** 최종 결과물의 구체적인 포맷과 전달 방식
- **역할 부여 (Role Assignment):** AI가 취해야 할 특정 전문성과 관점 지정

## 목표

- 시스템 프롬프트가 프롬프트 상품의 본문(`content`)만 보고 위 7개 요소의 존재 여부를 판단하게 한다.
- 판단 결과(boolean 7개)를 `InspectionVerdict`에 담아 반환한다.
- 이 체크는 **참고 정보**일 뿐, 기존 반려 기준(금지/불법, 스팸/저품질)에 영향을 주지 않는다. 즉 7개 요소가 전부 없어도 그 자체만으로는 반려 사유가 되지 않는다.
- 체크 결과를 `ai-events`(`PRODUCT_INSPECTION_COMPLETED`) 이벤트 payload까지 함께 발행한다.

## 비목표

- product-service 쪽 컨슈머(`ProductInspectionResultConsumer`, `ProductInspectionResultHandler`, `Product` 엔티티)가 새 필드를 실제로 활용하도록 만드는 작업은 이번 범위에 포함하지 않는다. payload 필드 추가는 하위 호환(additive)이며, 기존 컨슈머는 필요한 필드만 `payload.path(...)`로 읽으므로 깨지지 않는다.
- 상품명/설명/태그는 이 체크리스트 판단 대상이 아니다. `content`(본문)만 본다.
- 7개 요소 중 일부를 "필수"로 취급해 가중치를 매기는 기능은 없다. 단순 boolean 7개.

## 설계

### 1. `InspectionPromptFactory` — 시스템 프롬프트

기존 `SYSTEM_PROMPT`에 별도 섹션을 추가한다. 기존 반려 기준 설명 뒤, rejectionReason 지시 앞에 삽입.

```
또한 아래 7가지 요소가 상품 "본문"(사용자 메시지의 본문 필드)에 포함되어 있는지 참고용으로 판단한다.
이 판단은 승인/반려 여부나 rejectionReason에 영향을 주지 않는다 — 요소가 부족하다는 이유만으로 반려하지 않는다.
- Context (배경 정보): AI가 상황을 이해하는 데 필요한 배경 지식이나 문맥
- Objective (목표): AI에게 요구하는 구체적인 작업이나 목적
- Nuance (뉘앙스와 세부 조건): 작업 시 지켜야 할 구체적인 제약 조건이나 선호 사항
- Tone (어조): 결과물에 적용할 목소리의 톤과 스타일
- Examples (예시): AI에게 '좋은 결과물'이 무엇인지 보여주는 견본
- eXecution (실행 및 형식): 최종 결과물의 구체적인 포맷과 전달 방식
- 역할 부여 (Role Assignment): AI가 취해야 할 특정 전문성과 관점 지정
각 요소의 존재 여부를 hasContext, hasObjective, hasNuance, hasTone, hasExamples, hasExecution, hasRoleAssignment 필드에 boolean으로 담는다.
```

`formatInstructions`, `userPrompt`는 변경 없음 — `BeanOutputConverter`가 `InspectionVerdict` record 필드로부터 JSON 스키마를 자동 생성하므로 프롬프트 팩토리의 포맷 지시 로직 자체는 손댈 필요 없다.

### 2. `InspectionVerdict`

```java
public record InspectionVerdict(
	boolean approved,
	String rejectionReason,
	boolean hasContext,
	boolean hasObjective,
	boolean hasNuance,
	boolean hasTone,
	boolean hasExamples,
	boolean hasExecution,
	boolean hasRoleAssignment
) {
}
```

### 3. 데이터 흐름

`ProductInspectionService.inspect()` → `InspectionEventProducer.publish(...)`로 7개 boolean을 그대로 전달 → `ProductInspectionCompletedPayload`에 동일 필드 추가.

```java
// InspectionEventProducer
public void publish(
	UUID productId, boolean approved, String rejectionReason,
	boolean hasContext, boolean hasObjective, boolean hasNuance,
	boolean hasTone, boolean hasExamples, boolean hasExecution, boolean hasRoleAssignment
) { ... }
```

```java
// ProductInspectionCompletedPayload — 필드 추가 (기존 3개 필드명/의미는 유지)
public record ProductInspectionCompletedPayload(
	UUID productId, boolean approved, String rejectionReason,
	boolean hasContext, boolean hasObjective, boolean hasNuance,
	boolean hasTone, boolean hasExamples, boolean hasExecution, boolean hasRoleAssignment
) { ... }
```

기존 클래스 주석("필드명은 변경하지 않는다")은 기존 3개 필드에 대한 것이므로 유지하되, 새 필드는 additive라는 점을 한 줄 덧붙인다.

### 4. `SpringAiProductInspectionAgent`

변경 없음. `BeanOutputConverter<InspectionVerdict>`가 새 필드를 포함한 JSON 스키마를 자동 생성하고 `converter.convert(text)`로 그대로 역직렬화된다.

## 에러 처리

새로 추가되는 필드는 모두 primitive boolean이라 AI 응답에 해당 키가 없으면 Jackson 역직렬화 시 기본값(`false`)으로 채워진다. 별도 null 처리나 검증 로직은 필요 없다.

## 테스트 계획

- `SpringAiProductInspectionAgentTest`: 응답 JSON에 7개 필드를 포함한 케이스로 given/verify 갱신, `InspectionVerdict`의 7개 필드가 올바르게 매핑되는지 확인.
- `ProductInspectionServiceTest`: `aiPort.inspect(...)`가 반환하는 `InspectionVerdict`의 7개 필드가 `inspectionEventProducer.publish(...)` 호출 인자로 그대로 전달되는지 검증하도록 given/verify 갱신.
- `InspectionEventProducerTest`: `publish(...)` 시그니처에 7개 파라미터 추가, `ProductInspectionCompletedPayload`에 값이 그대로 담기는지 승인/반려 두 케이스 모두 검증.

## 오픈 이슈 / 후속 작업

- product-service가 이 체크리스트 결과를 실제로 저장/노출할지는 별도 요구사항으로 결정 필요 (이번 스펙 범위 밖).
