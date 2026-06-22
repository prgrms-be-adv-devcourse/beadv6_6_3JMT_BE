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

- [ ] 클린아키텍처: 의존성 방향(presentation→application→domain←infrastructure)·포트&어댑터·계층 책임 준수
- [ ] 도메인 모델: 비즈니스 setter 금지·상태변경은 도메인 메서드·Lombok 허용 범위 준수
- [ ] Controller/예외: 얇은 컨트롤러(Repository 직접 접근 금지)·커스텀 예외·전역 예외 핸들러
- [ ] 코드 스타일: 네이밍 케이스·와일드카드 import 금지·빈 catch 금지
- [ ] Swagger: 표현계층 한정·@Operation/@ApiResponses/@Schema 명시
- [ ] Git 컨벤션: 커밋 메시지(`<타입>: <내용>`)·브랜치 명명 규칙

## 👀 리뷰어를 위한 참고 사항 (Notes for Reviewers)

## ➕ 추가 정보 (Additional Information)