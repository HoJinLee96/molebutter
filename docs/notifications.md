# 사용자별 알림 API와 근태 연동

V15는 알림 사건과 사용자별 수신·삭제 상태, 최신화 작업의 알림 순번을 추가한다. 기존 결과를 소급 생성하지 않는다. 개수는 삭제하지 않은 알림의 수이며 읽음 상태나 자동 만료는 없다.

## 생성과 보존

- `NotificationService.publish`는 업무 트랜잭션 안에서 호출한다. 업무가 롤백되면 알림도 저장되지 않는다. 사건 키로 중복을 막고, 사용자별 삭제 상태는 재처리 후에도 유지한다.
- 근태 정정 신청·취소는 본인에게, 승인·반려는 본인과 처리 관리자에게 알림을 전달한다.
- 업무 충돌(`OperationFailure`), 낙관적 잠금 충돌과 예상하지 못한 서버 오류는 업무 롤백 후 별도 트랜잭션으로 실패 알림을 저장한다. 실패 알림 저장이 실패해도 원래 API 오류 응답은 유지한다.
- `X-Operation-Id`가 같은 요청은 실패 알림을 중복 생성하지 않는다. 일반 저장 성공·GET·인증·CSRF·입력 검증 오류와 알림 API 자체 오류는 실패 알림으로 만들지 않는다.

## API

기존 `ApiResponse`와 문자열 ID를 사용한다. 최신 계정 상태와 수신자 소유권을 검사한다.

- `GET /api/notifications?cursor=...&size=20`: 삭제하지 않은 알림을 ID 내림차순으로 반환한다. 최대 50개이며 `items`, `nextCursor`를 제공한다.
- `GET /api/notifications/summary`: 미삭제 개수 `count`와 최신 ID `latestId`를 반환한다.
- `GET /api/notifications/{id}/target`: 서버에서 확인한 내부 이동 경로 또는 이동 불가 사유를 반환한다. 읽기나 이동은 알림을 삭제하지 않는다.
- `DELETE /api/notifications/{id}`: CSRF 검증 후 해당 사용자의 삭제 시각만 기록한다. 반복 삭제는 성공한다.

권한을 잃은 업무의 알림 제목·내용은 가린다. 개수에는 포함하며 본인 알림 삭제는 허용한다. 삭제된 업무 대상에 대한 알림은 보존하고 이동할 때 다시 검사한다.

## 단계별 연결

이번 범위는 저장 구조·알림 API·근태 정정·실패 처리다. 상품 권한과 최신화 알림 생성 메서드는 후속 상품 서비스 연결을 위한 기반으로 제공한다. 상품 등록·최신화에서의 호출과 알림 화면·공통 메뉴 연결은 후속 변경에서 적용한다.

## 검증

`ProductFlowIT`의 알림 검증은 트랜잭션 롤백·중복·커서·개별 삭제·권한 변경·CSRF·삭제된 대상·근태 정정과 실패 알림을 확인한다. `MigrationUpgradeIT`는 V14→V15 전환과 기존 선정·매입처 데이터 보존을 확인한다. 임시 MySQL·Redis를 사용하는 `bash scripts/test-integration.sh '-Dtest=*Test,MigrationUpgradeIT,AuthenticationFlowIT,AttendanceFlowIT,ProductFlowIT'`로 실행한다.
