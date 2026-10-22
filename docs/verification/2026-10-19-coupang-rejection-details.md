# 쿠팡 상세 거절 사유와 단계 로그 검증

2026-10-19 구현. 운영 DB·실행 중인 IDE 앱·실제 쿠팡 판매값·R2 객체를 변경하지 않는다. 실행 중인 앱의 기본 target/classes 대신 별도 `target/rejection-details-check`에서 빌드한다. 격리 DB 검사는 기존 스크립트의 임시 MySQL·Redis와 `target/verification`을 사용한다. DB 구조·마이그레이션·공개 엔드포인트 변경은 없다.

## 변경

- HTTP 200 `ERROR` 및 확정 4xx 거절의 허용된 오류 필드만 추출한다. 기본 안내·결과 코드와 상세 설명을 함께 전송 기록에 표시한다.
- 요청 문자열 값은 가리고 응답 상품명·속성값·알 수 없는 객체는 추출하지 않는다. URL·이메일·연락처·긴 식별자 제거, 개인정보·인증 표식 포함 조각 생략, Unicode 정규화와 제어 문자 제거, 길이 제한을 적용한다. 원문을 저장하거나 로그에 남기지 않는다.
- 기존 Result JSON에 선택적 `rejectionDetails`를 추가한다. 이전 JSON은 null로 읽고 기존 상태 복구를 유지한다. 기존 VARCHAR(500)에 들어가는 요약과 최대 360자 상세 설명을 보존한다.
- 커밋 뒤 실행 ID·단계 ID·단계 종류·WRITE/RECONCILE·상태·정규화한 결과 코드만 INFO/WARN으로 기록한다. 로그 캡처에서 원문·계정·요청·상세 설명 누출이 없음을 검사한다.
- UNKNOWN·ACCEPTED, 승인 대기, 단일 전송·중복 방지 키·미완료 입력 보존을 유지한다. 이전에 버린 사유는 복원하지 않는다.

## 검증

- `CoupangWriteOutcomeTest`: 평면/중첩 ERROR, 알려진 오류 분류 유지, 400 거절, 인증/호출 제한/서버 오류 비노출, 잘못된 응답 UNKNOWN, SUCCESS 접수, 과거 Result JSON 호환.
- `CoupangRejectionDetailsTest`: 요청값·전화·이메일·URL·식별자 제거, 자격 증명/개인정보 조각 생략, 전체 너비 문자·길이 제한·미지원 객체·잘못된 요청 문맥 처리, 실패에만 상세 표시.
- `SubmissionWorkerDiagnosticsTest`: FAILED/ACCEPTED/UNKNOWN 로그의 단계별 메타데이터와 수준, 민감한 결과·계정·전문 미노출.
- `CoupangWorkingCopyWriteTest.actualDispatchPreservesRedactedReasonAlongsideTheExactRequest`: 실제 게이트웨이의 모의 전송에서 요청 감사 정보와 상세 사유가 함께 유지되고 추가 전송이 없는지 검사.
- `CoupangWorkingCopyFlowIT.redactedRejectionSurvivesDatabaseAndHistoryReloadWithoutRawMessageOrExtraDispatch`: 격리 MySQL의 결과 JSON·목록·단건 재조회, 원문 비저장, 승인 미실행, 기존 키 재사용·미완료 의도 보존 검사.
- `scripts/test-coupang-rejection-details-ui.cjs`: 1440/390px의 실제 프로젝트 HTML/JS/CSS·모의 API에서 여러 줄 표시, 긴 내용 줄바꿈, HTML 이스케이프, 새로고침 복원, 쓰기 요청 0회. 이미지 `/tmp/coupang-rejection-details-{1440,390}.png`를 육안 확인한다.

## 실행 결과

- 전체 `verify`: Java 806개, 실패·오류·생략 0. 기존 설치 Chrome을 사용하도록 `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`을 적용하고 별도 빌드 경로에서 성공했다. 최초 샌드박스 실행은 Playwright 다운로드 대기로 중단했으며, 이번 검증의 Maven·자식 프로세스만 종료한 뒤 다시 실행했다.
- 최종 보완 후 관련 단위·게이트웨이·로그 테스트 59개 재실행: 실패·오류·생략 0. 긴 일반 설명보다 옵션별 오류를 먼저 표시하고, 한글에 붙은 숫자 연락처·숫자 우편번호도 제거하는 경계를 추가했다.
- 최종 격리 `CoupangWorkingCopyFlowIT` 20개: 실패·오류·생략 0. 임시 서비스는 스크립트 종료 시 정리했다.
- JavaScript 전체 264개: 통과, 실패·생략 0.
- PC 1440px·모바일 390px 모의 화면: 통과. 새로고침·여러 줄·긴 내용·HTML 이스케이프·쓰기 요청 0회를 확인하고 PNG를 육안 검토했다.
- `git diff --check`: 통과.

명령은 각각 `/tmp/molebutter-rejection-verify.log`, `/tmp/molebutter-rejection-unit-final.log`, `/tmp/molebutter-rejection-it-final.log`, `/tmp/molebutter-rejection-js.log`, `/tmp/molebutter-rejection-ui.log`에 실행 결과를 남겼다. 합성 데이터 검사이며 실제 거절 응답을 재현하기 위해 쿠팡에 재전송하지 않았다. 실행 중인 IDE 앱을 재시작하거나 운영 DB를 변경하지 않았으므로 현재 실행 앱에는 아직 이 코드가 적용되지 않았다.
