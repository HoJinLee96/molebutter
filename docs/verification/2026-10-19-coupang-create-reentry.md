# 쿠팡 신규 등록 실패 재진입·고시 유형 저장 검증

2026-10-19. 운영 DB·실행 중 IDE 앱·실제 쿠팡 판매값·R2 객체는 변경하지 않았다. 빌드는 기본 target/classes 대신 `target/coupang-create-reentry-check`, DB 검사는 임시 MySQL·Redis와 `target/verification`을 사용했다. 새 엔드포인트·DB 구조·마이그레이션 변경은 없다.

## 원인과 변경

- 확인한 실행 로그는 `CREATE / WRITE / FAILED / PREFLIGHT_BUSY`였다. HTTP 200의 ERROR를 성공으로 오인한 것이 아니라, 실제 POST 전에 앱 내부 조회 가드가 거절한 상태다.
- CREATE 준비·전송은 계정 조회 가드를 점유한 채 저장된 브랜드를 검증한다. 재기동·캐시 만료로 브랜드 재조회가 필요하면 공개 브랜드 조회가 같은 가드를 다시 획득하려 해 BUSY로 실패했다. 같은 스레드에서만 중첩 획득 가능한 잠금으로 바꾸고, 중첩 조회 완료 시 상위 가드가 유지되도록 각 획득에 대응해 해제한다. 타 스레드는 기존처럼 BUSY로 배제한다. 요청 간격·인증·취소 검사는 유지한다.
- CREATE의 판매자상품코드 기준 조회는 등록 전 기준 수집과 UNKNOWN 결과 복구에 필요하므로 제거하지 않는다. HTTP 200 ERROR는 FAILED·등록상품 ID 없음으로 유지하고 재준비는 CREATE이다. 성공·미확인 상태를 임의로 초기화하지 않는다.
- 고시 선택 변경은 이전 유형의 행을 폼에 남겨 두지만, flatten이 첫 행의 유형을 선택값으로 저장하던 문제가 있었다. 임시 저장 문서에는 현재 선택 유형과 해당 행만 넣는다. 폼 자체는 이 변환으로 변경하지 않으며 재조회·전송 입력의 행별 수정·제거도 기존 계약을 사용한다. 서버가 첫 고시 행을 기준으로 정규화해도 선택 유형과 일치한다.

## 검증

- `CoupangBrandsTest.coldSavedBrandCanBeValidatedInsidePreparationWithoutReleasingTheOuterGuard`: 실제 클라이언트·모의 HTTP로 캐시 없는 저장 브랜드의 중첩 검증, 내부 조회 후 상위 잠금 유지, 경쟁 스레드 BUSY, 정상/예외 종료 후 해제를 검사했다.
- `CoupangWorkingCopyWriteTest.rejectedCreateStaysCreateOnReloadAndRetryWithoutLookingUpAnAssignedProduct`: HTTP 200 ERROR 뒤 FAILED·등록 ID 없음·재준비 CREATE 유지, 기존 상품 상세 조회 없음, SKU 기준 조회 유지, 준비 단계 쓰기 없음.
- `src/test/js/coupang-working-copy.test.cjs`: 첫 행과 다른 고시 유형 선택, 활성 고시만 저장·복원, 편집 중 이전 입력 보존, 행별 수정 의도 복원과 참조 내용 유지.
- `CoupangWorkingCopyFlowIT.newWorkingCopyRetainsSelectedNoticeCategoryAndReferenceContentsAcrossReentry`: 격리 DB에 신규 작업본 저장·재진입 시 고시 유형과 상세페이지 참조 내용 복원·실제 전송 없음.
- `scripts/test-coupang-create-reentry-ui.cjs`: 실제 프로젝트 HTML/JS/CSS와 모의 API로 1440/390px에서 실패한 CREATE 작업 불러오기 → 고시 유형 변경·참조 체크 → 임시 저장 → 새로고침 → 신규 등록 미리보기. 실제 execute 호출 0회. 이미지 `/tmp/coupang-notice-reentry-{1440,390}.png`를 육안 확인했다.

## 실행 결과

- 전체 Java `verify`: 812개, 실패·오류·생략 0.
- 격리 MySQL·Redis `CoupangWorkingCopyFlowIT`: 21개, 실패·오류·생략 0.
- JavaScript 전체: 265개 통과, 실패·생략 0.
- 모의 화면: 1440/390px 모두 통과. 고시 선택과 참조 내용의 저장·재진입, CREATE 미리보기 확인 버튼 활성화, execute 0회를 검사했다.
- `git diff --check` 통과.

로그: `/tmp/molebutter-create-reentry-{verify,it,js,ui}.log`. 초기 새 브랜드 테스트 응답에서 필수 브랜드 메타 필드를 빠뜨린 부분을 보완했고, 로컬 모의 HTTP 서버의 소켓 권한이 필요한 검사는 격리 검증 실행 권한으로 다시 실행했다. 최종 전체 회귀는 모두 통과했다.

이전 실제 실패를 재현하기 위해 운영 앱에서 재시도하지 않았다. 현재 실행 중인 앱은 재기동하지 않았으며 변경 적용 후 사용자가 다시 확인해야 한다.
