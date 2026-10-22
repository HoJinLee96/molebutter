# 판매기간·고시유형 표시 일치 검증 — 2026-10-20

## 확인 범위와 원인

공식 SOURCE는 2026-10-03 수집본을 읽었으며 원문·SOURCE를 변경하지 않았다. 상품 조회/수정은 `saleStartedAt`·`saleEndedAt`을 일시로 제공하고 별도의 기간 제한 없음 플래그는 없다. 고시 값은 상품의 `notices`에, 고시 선택 후보는 카테고리 메타정보의 `noticeCategories`에 있다.

앱은 종료일이 2099년으로 시작하면 OFF로 표시하고 실제 날짜를 숨겼다. 고시유형은 규격 조회가 성공해야만 선택란을 생성했다. 같은 시각의 기존 로그에 HTTP 200 / INVALID_RESPONSE가 있지만 이전 로그는 카탈로그 요청 종류나 실패 필드를 구분하지 않으므로 그 응답을 특정하지 않았다. 이번 검증에서 실상품 API 원문과 Wing 내부 응답을 새로 수집하거나 비교하지 않았다.

## 구현

- 판매기간은 시작·종료 일시를 항상 표시한다. 초 단위와 2099년의 날짜/시간 차이를 보존하며 브라우저 시간대나 DST로 해석하지 않는다. 잘못된 날짜는 텍스트로 남긴다.
- 장기 설정은 명시적인 버튼으로만 현재 한국 시각부터 2099-12-31T23:59:00까지 입력한다. 빈 신규 작업본을 열기만 해서는 날짜를 생성하지 않는다.
- 고시유형은 실패·대기 중에도 표시하고 비활성화하며 오류와 재조회 버튼을 같은 영역에 제공한다. 규격 조회만으로 기존 유형을 첫 후보로 교체하지 않는다. 최신 후보에 없는 기존 유형도 보존한다.
- 옵션별 유형이 다르면 혼합 상태를 표시하고 각 옵션별 내용 편집을 제공한다. 사용자가 유형을 고를 때만 전체 옵션에 적용한다.
- 카테고리 코드 변경 시 이전 조회를 취소하고 오래된 응답을 버린다. 기존 규격 조회 버튼의 명시적 클릭 및 연결된 Enter 조회도 검증했다.
- 로그는 CATEGORY_RULES / CATEGORY_LIST / BRAND / 출고·반품지 요청을 구분한다. 카테고리 파서 실패는 ROOT / DATA / ATTRIBUTES / NOTICE_CATEGORIES / CERTIFICATIONS / DOCUMENTS / OFFER_CONDITIONS의 고정 단계만 남긴다. 알 수 없는 code·잘못된 배열 구조는 성공이나 빈 규격으로 처리하지 않는다. 본문·자격 증명·계정값·상품값은 추가하지 않는다.
- 새 API·테이블·마이그레이션은 없다. 기존 작업본 및 미전송 입력 보존과 전송 보호를 유지한다.

## 실행 결과

- Java 전체 verify: **829 tests, failures 0, errors 0, skipped 0**.
- 격리 MySQL·Redis: **38 tests, failures 0, errors 0, skipped 0**. CoupangWorkingCopyFlowIT 22, CoupangIntentMigrationIT 1, CoupangRegisteredProductsIT 15. 정확한 초 단위 날짜·옵션별 다른 고시유형 저장/재진입, 수정 의도 없음, 기존 revision·UNKNOWN 보호 포함.
- JavaScript 전체: **269 tests, failures 0**.
- 마지막 출고·반품지 진단 분류 보완 후 관련 CoupangClientTest 26개·CoupangRetryTest 10개를 재실행해 모두 통과했다. 로그는 `/tmp/molebutter-period-diagnostics.log`에 있다.
- 새 모의 화면: 1440px / 390px에서 일반·2099년 날짜, 자정·초 단위, 빈 값·잘못된 값, 명시적 장기 설정, 실패/재조회/입력 보존, 후보에서 사라진 유형, 혼합 유형별 개별 입력, 저장/재진입, 지연된 이전 응답 무시 통과.
- 기존 모의 화면: 신규 작업본 첫 저장·세션 만료 복구(PC/모바일 각각 2가지), 등록 거절 후 재진입 및 고시유형 저장/미리보기, 수정 의도 완료 처리, 저장/완료 경합, 구매속성 식별, 목록 이동 회귀 통과.
- PC/모바일 캡처를 직접 확인했다. `git diff --check` 통과.

실행 로그: `/tmp/molebutter-period-{java,it,js,ui,new-ui,reentry-ui,regression-ui}.log`. 화면 캡처: `/tmp/coupang-sale-period-{1440,390}.png`, `/tmp/coupang-period-notices-{1440,390}.png`.

Java 빌드는 `target/period-notices-check`, DB 검증은 임시 MySQL·Redis와 `target/verification`을 사용했다. 모의 화면은 임의 포트 로컬 서버와 별도 headless Chrome을 사용하고 외부 요청을 차단했다. 초기 sandbox의 로컬 소켓/Chrome 제한 이후 해당 격리 검증만 권한을 받아 다시 실행해 통과했다.

**운영 DB·실행 중인 IDE 앱·실제 쿠팡 판매값·실제 R2 객체는 변경하지 않았고, 운영 앱 재시작도 수행하지 않았다.** 따라서 실행 중인 앱에 적용된 상태는 이번 로컬 검증 결과와 구분한다.
