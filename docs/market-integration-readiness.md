# 쿠팡 앱과 내부 업무의 경계

> 문서 유형: 현재 구현 안내. 2026-10-15 쿠팡 전용 경계로 정리했다. 날짜가 붙은 적용·검증 문단은 당시 기록이며 현재 정책과 구분한다.

## 현재 책임과 이름

| 책임 | 코드 | 기존 이름 / 호환성 |
|---|---|---|
| 매입처 조회 작업 관리 | `SupplierRefreshService`, `SupplierRefreshWorker` | `ProductRefresh*`; `/api/product-refresh`, `product_refresh_*`는 유지 |
| 매입처 상품 판정 | `SupplierLookupService`, `SupplierLookupStatusPolicy` | `ProductLookupService`, `ProductStatusPolicy`; 저장 상태값은 유지 |
| 매입처 상품 조회 | `SupplierProductGateway`, `ProcurementMall` | `ProductSourceGateway`, `Mall`; enum 직렬화 값은 유지 |
| 재고 조회·집계 | `InventoryQueryService` | 상품코드 그룹은 화면 표시 규칙 |
| 매입 주문·상품 변경 | `InventoryPurchaseService` | 판매 주문과 별개 |
| 수량 이력·취소·환불 | `InventoryMovementService` | 기존 입출고 API·요청 해시·버전 규칙 유지 |
| 공통 기준 | `BusinessAccess`, `BusinessRevision`, `BusinessIds`, `BusinessTime`, `CatalogConsistencyGuard` | 재고가 `ProductStore`의 인증·ID·시간을 사용하지 않음 |
| 공통 화면 표시 | `AppUI` | 상품 화면이 `AttendanceUI`를 가져오지 않음 |

`InventoryService`는 기존 호출부의 진입점을 유지하는 위임 계층이다. SQL 조회와 세 종류의 변경 책임을 한 클래스에 다시 모으지 않는다. `AttendanceUI`는 근태 표시만 담당한다. 공통 설정 이름 입력은 `NamedSettingInput`을 사용한다.

## 입력·권한·공개 응답

- 메뉴에 등록되지 않은 API 영역은 기본 거절한다. 인증 공개 경로와 인증 후 본인 정보·비밀번호 변경·본인 알림 경로는 명시한다. 새 쿠팡 업무 API도 권한을 등록하기 전에는 접근할 수 없다.
- 최신 DB 계정 상태·역할 검사를 유지한다. JWT 역할만으로 재고 업무 권한을 확정하지 않는다.
- 사용자가 읽을 입력 오류는 `InputValidationFailure`, 상태 충돌은 `OperationFailure`로 작성한다. 외부 예외 메시지를 이 타입에 복사하지 않는다. 일반 입력 예외는 공통 400, 일반 상태 예외·인프라 실패는 공통 500이다.
- 바인딩·JSON 파싱 오류의 원문은 로그에 남기지 않는다. 예상하지 못한 오류는 클래스·요청 경로·스택 위치만 기록한다. 쿼리 문자열·예외 메시지·원인 메시지·인증 토큰·메일 공급자 응답은 기록하지 않는다.
- `InventoryView`는 항목·주문·이력·상품 집계·옵션의 공개 필드와 관리자 필드를 명시한다. 새로운 SQL 열은 자동 공개되지 않는다. 중첩 항목과 옵션도 각각 투영한다. PRODUCT에게 매입 단가·합계·결제 정보·비공개 메모·환불 정보는 반환하지 않는다.

## 재고 잠금과 재시도

잠금 순서는 **카탈로그 공통 가드 → 요청 식별자 → 매입 주문 → 매입 상품 → 이동/환불 기록**이다. 요청 식별자를 사용하지 않는 수정은 해당 단계를 건너뛴다.

카탈로그 수정·통합·삭제와 공통 설정 변경은 `catalog_consistency_guard`의 전용 행을 배타적으로 잠근다. 재고 변경은 같은 행을 공유 잠금으로 잡고 해당 주문과 항목을 잠근다. 따라서 카탈로그 변경과 재고 변경은 서로를 보호하며, 서로 다른 주문의 입고는 동시에 진행할 수 있다. 같은 주문은 삭제·메타정보 변경·항목 변경·수량·환불을 직렬화한다.

V27의 `inventory_request_lock`은 같은 UUID 요청을 트랜잭션 끝까지 직렬화한다. 기존 주문/이력의 요청 해시를 그대로 비교하므로 과거 재시도와 충돌 규칙은 유지된다. 최초 요청이 실패하면 잠금 행 생성도 롤백된다. 기존 이력과 별개로 잠금 행만 만료·삭제하지 않는다.

상품별 표시 그룹과 매입 항목은 여전히 별개다. 주문 직후 보유 0·미입고 N, 입고 뒤 실제 보유 증가, 소진 뒤 0수량 행 유지, 마지막 주문 삭제 뒤 행 제거를 유지한다. 현재 상품 정보 표시, 보관 위치 제거, 여러 줄 메모 보존, 관리자용 정확한 금액 합계도 바꾸지 않는다.

## 감사와 알림

`OperationContext`는 HTTP/백그라운드 실행 구분과 UUID를 보관한다. HTTP 감사는 `X-Operation-Id`와 연결하고, 백그라운드는 가짜 관리자 계정을 만들지 않는다. V28은 기존 HTTP 감사 행을 보존하며 시스템 실행의 계정 값이 비어 있을 수 있도록 한다. HTTP 감사의 계정 필수 조건은 DB 제약으로 유지한다.

매입처 조회의 실제 저장 결과는 커밋 이후 백그라운드 감사로 남긴다. 롤백된 결과에는 완료 감사를 만들지 않는다. 수량 작업 실패 알림은 재고 페이지로 연결하며, 현재 재고 접근 권한을 잃으면 내용과 이동을 숨긴다. 외부 요청 본문이나 인증 정보를 감사·알림에 담지 않는다.

## 쿠팡 판매 연동 경계

판매 상품·주문은 쿠팡 전용이다. `molebutter-coupang`의 api가 계약, internal이 업무·저장, internal.client가 쿠팡 서명·HTTP를 담당한다. 화면·컨트롤러는 app이 담당한다. 다른 업무의 구현 클래스를 직접 참조하지 않는다.

판매 계정과 외부 상품·옵션 ID는 상품업무의 상품코드·작업본 옵션 UUID·매입처 공개 상품 ID와 구분한다. 보유 재고와 판매 가능 수량을 자동 동기화하지 않는다. 미리보기·명시적 확인·계정 및 revision 충돌 검사·중복 실행 방지·결과 불명 확인 흐름을 유지한다.

[쿠팡 구현 안내](coupang-api/coupang-wing.md), [상품 편집](coupang-api/working-copies.md), [판매 주문](coupang-api/sales-orders.md), [현재 데이터 보존 전환](coupang-intent-migration.md)을 따른다. 네이버 공개 검색과 매입처 데이터는 판매 초기화에서 보존한다.
