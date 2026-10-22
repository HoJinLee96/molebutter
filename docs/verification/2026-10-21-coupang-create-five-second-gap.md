# 신규 등록의 두 단계에 최소 5초 적용

검증일: 2026-10-21 한국 시각. 사용자가 요청한 앱의 호출 간격 정책을 검사했다. 운영 DB·실행 중인 IDE 앱·실제 쿠팡 상품값·R2 객체는 변경하지 않았다. 새 테이블·마이그레이션·API 호출은 없다.

## 동작

- 신규 등록 성공 응답 → 최소 5초 → 판매 승인 PUT → 승인 성공 응답 → 최소 5초 → 최초 상품 상세 GET은 자동 진행한다. 등록 응답의 상품 ID와 기존 실행 단계를 그대로 사용한다.
- `DefaultCoupangWriteGateway`는 신규 등록에서 파생한 승인 단계에만 `CoupangProductClient.withFirstResponseGap`을 지정한다. 공통 HTTP 실행기의 취소 가능한 대기·로그·더 긴 Retry-After를 재사용한다. 응답 처리 시간을 대기에 포함하지 않는다.
- 5초 지정은 첫 실제 전송에서 소비한다. 자동 재시도, 이후 별도의 결과 재확인, 기존 상품 편집 승인, 가격·재고 변경 및 일반 조회는 공통 2초를 유지한다. 호출 전 예외·실패·취소 이후 다른 요청으로 5초 지정이 남지 않는다.
- 승인 응답이 실패이면 성공 후 상세 조회를 실행하지 않는다. 승인 후 조회가 아직 처리 중이면 기존 ACCEPTED/결과 확인 상태를 유지한다. UNKNOWN·등록 ID 보존·중복 전송 방지는 바꾸지 않는다.

## 실행한 검사

Java 21·오프라인 Maven으로 IDE 빌드 경로와 분리한 `target/creation-gap-check`에서 실행했다.

- `CoupangWorkingCopyWriteTest` 87개와 `CoupangApiLoggingTest` 7개: 합계 94개 통과, 실패·오류·생략 0.
- 뒤에 추가한 `existingProductApprovalKeepsTheCommonResponseGap` 1개 별도 통과. 기존 상품 편집의 승인에는 5초 지정이 없는지 검사한다.
- 실제 공통 클라이언트와 모의 HTTP 전송기를 연결한 규격 GET → 등록 POST → 승인 PUT → 상세 GET 검사에서 POST/PUT 응답을 각각 1.2초 지연했다. 응답 완료→다음 전송 간격은 각각 로그 기준 **5,004ms / 5,005ms**였다. 정확한 네 호출과 ID 연결도 검사하며 상품코드·옵션 재고 조회를 추가하지 않는다.
- 별도 실제 대기 검사에서 최초 승인 요청은 5초, 같은 요청의 자동 재시도와 이후 일반 조회는 각각 2초 이상·4.9초 미만이었다. 호출 전 예외 뒤에도 일반 조회는 2초를 유지했다. `sincePreviousResponseMs`와 `paceWaitMs` 로그 및 민감정보 미출력도 검사했다.
- `scripts/test-integration.sh -Dtest=CoupangWorkingCopyFlowIT -Dsurefire.failIfNoSpecifiedTests=false`: 임시 MySQL·Redis와 모의 API로 작업본·미리보기·전송·revision·결과 확인 등 33개 통과, 실패·오류·생략 0. 전체 모듈 컴파일도 통과했다. 임시 서비스와 데이터는 실행 후 정리했다.
- 변경 범위 `git diff --check` 통과. 합계 128개 검사를 실행했다. 화면 변경은 없으며 이번 변경에서 PC/모바일 화면 검사를 새로 실행하지 않았다.

모의 API의 시각·응답과 로컬 실제 대기로 확인한 결과이며 실계정에서 5초 뒤 승인 처리가 완료됨을 보장하는 검증은 아니다. 수집일 2026-10-03의 공식 SOURCE는 처리 중 오류에 대해 별도의 재시도 안내를 제공한다. 앱의 2초·5초 정책과 공식 처리 완료 조건은 구분한다.
