## 🛠️ 설명 (Description)

어떤 작업을 했는지 설명해주세요.

## 📄 설계 문서 (Design Document)

관련 설계 문서나 위키 페이지가 있다면 링크를 추가해주세요.(하위 항목 노션링크 첨부)

## ✅ 테스트 계획 (Test Plan)

- 어떤 테스트를 수행했는지, 또는 수행할 예정인지 작성해주세요.
- 유닛 테스트, 통합 테스트, E2E 테스트 등
- 테스트 커버리지

## 📝 변경 사항 요약 (Summary)

- 주요 변경 사항 1
- 주요 변경 사항 2
- ...

## 🔗 관련 이슈 (Related Issues)

- Closed #이슈번호
- Related #이슈번호

## ☑️ 체크리스트 (Checklist)

- [ ] 레이어 격리: Domain 패키지에 Spring/JPA/OpenAI SDK 등 외부 의존성 침투 없음
- [ ] 단방향 의존: Controller → Service → Repository 흐름 엄수 (순환 참조 없음)
- [ ] 비용 방어: LLM·임베딩 호출 전 `source_hash` 멱등성 검증 로직 포함
- [ ] 보안: `.env` 값이나 API Key가 코드에 하드코딩되지 않음

## 👀 리뷰어를 위한 참고 사항 (Notes for Reviewers)

## ➕ 추가 정보 (Additional Information)

TODO: 추후 자가점검 항목은 아키텍처에 맞게 변동해야 합니다.