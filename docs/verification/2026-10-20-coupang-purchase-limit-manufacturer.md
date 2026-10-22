# 공통 설정 구매 제한과 제조사 자동 입력 검증

검증일: 2026-10-20, 한국 시각.

## 확인한 원인과 변경

- 실행 중인 브라우저의 신규 등록 화면을 읽어 제조사 빈 값을 확인했다. 상품 정보 도구의 공개 LF 고시 조회를 읽기 전용으로 한 번 실행한 결과, 해당 상품의 실제 항목은 `제조사(공식수입/병행수입)`이고 값은 `LF`였다. 기존 별칭에는 `제조자(공식수입/병행수입)`만 있어 자동 입력에서 누락됐다.
- 실제 항목명을 명시적 제조사 별칭에 추가했다. 제조사 입력과 상품 고시의 제조자 값이 같은 유일한 조회값을 사용한다. 제조국 제외, 누락·충돌 안내, 사용자 수정 보존은 유지한다.
- 공통 설정의 인당 최대구매수량을 미설정·설정함·설정안함으로 구분했다. 설정함에서만 양수 수량·기간을 입력하고, 설정안함은 수량 0·기간 1로 저장한다. 미설정은 두 기본값을 생략한다. 기존 부분 설정은 조회 시 유지하며 읽기만 해서는 저장하지 않는다.
- 모드 변경 시 다른 미저장 설정과 수량·기간 초안을 유지한다. 숨겨진 수량 입력은 비활성화하고 키보드 선택 초점을 보존한다. 저장 실패·revision 충돌 시 입력을 보존한다. 서버도 잘못된 기간 및 설정안함의 모순된 기간을 거절한다.
- 배송비 유형의 무료와 부가세 면세 표기를 필드별로 구분한다. 배송방법은 기존 공통 코드·일반배송 표기를 계속 사용한다.

## 실행한 검증

| 검증 | 결과 |
| --- | --- |
| Java: 제조사 별칭, 실제 LF 항목명 파싱, 등록·고시 매핑, 레이아웃 | 23개 통과 |
| JavaScript 전체 단위 검사 | 275개 통과 |
| 임시 MySQL·Redis의 CoupangWorkingCopyFlowIT | 28개 통과 |
| 모의 HTTP PC 1440px·모바일 390px 화면 회귀 | 모두 통과 |
| git diff --check | 통과 |

화면 회귀는 제조사 LF 초기 입력·새로고침 보존, 구매 제한 세 모드의 저장·재조회, 수량·기간과 다른 입력 보존, 키보드 이동, 잘못된 수량 차단, Enter 저장 금지 및 revision 충돌 입력 보존을 검사했다. 모바일 설정안함 화면의 숨김·선택 배치도 생성 이미지로 확인했다.

실행 명령은 `./mvnw -o -Dmolebutter.build-directory=target/purchase-manufacturer -Dtest=CoupangRegistrationTextTest,CoupangPrefillServiceTest,ProductLookupServiceTest,LayoutViewTest -Dsurefire.failIfNoSpecifiedTests=false test`, `node --test src/test/js/*.test.cjs`, `bash scripts/test-integration.sh -Dtest=CoupangWorkingCopyFlowIT -Dsurefire.failIfNoSpecifiedTests=false`, `node scripts/test-coupang-prefill-ui.cjs`다. 외부 쓰기 기능은 비활성화했고 Java 출력은 IDE와 분리했다.

## 적용 범위

운영 DB·실행 중인 IDE 앱·실제 쿠팡 상품값·R2 객체는 변경하지 않았다. 새 API·테이블·마이그레이션은 없다. 공통 설정의 실제 저장은 사용자가 수행한다. 공개 LF 조회와 현재 브라우저 화면 확인은 읽기 전용이며, 실제 쿠팡 등록 성공 검증을 뜻하지 않는다.

서버 변경 반영에는 사용자의 앱 재시작이 필요하다. 기존 준비 토큰·작업본·미저장 입력에는 제조사를 자동 덮어쓰지 않으므로 상품 정보 도구에서 새로 등록 준비해야 수정된 매핑을 확인할 수 있다.
