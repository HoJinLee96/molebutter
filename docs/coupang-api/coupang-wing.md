# 쿠팡 판매 연동 구현 안내

2026-10-16 현재 코드 기준이다. 공식 계약은 [쿠팡 문서 읽기 순서](../../coupang-api-docs/README.md)의 SOURCE로 확인한다. 문서 수집·모의 검증과 실제 계정 호출의 성공은 구분한다. 현재 사용 흐름은 [상품과 작업본](working-copies.md), [주문](sales-orders.md), [전송 복구](submission-recovery.md)를 따른다.

## 코드 책임

| 위치 | 책임 |
|---|---|
| `coupang.api` | 쿠팡 조회·작업본·수정 의도·전송·주문 공개 계약 |
| `coupang.internal` | 작업본·서버 편집 세션·계정과 revision·전송 임대·증거 저장 |
| `coupang.internal.client` | 최신 원문 조회, 허용한 수정의 전문 생성, HMAC·HTTP·응답 판정 |
| app | 권한·CSRF·화면·HTTP 변환·설정·Flyway |
| media / storage | 이미지 원본·공개 발행·참조 보호 / 공용 R2 인프라 |

`molebutter-coupang` 하나를 사용한다. 판매용 내부 상품·연결 후보·기준값·비교 서비스나 마켓 선택·공급자 레지스트리를 두지 않는다. 작업본의 옵션 UUID와 외부 옵션 ID 매핑은 전송 안전을 위해 유지한다. 상품업무·매입·실물 재고는 별도 업무다.

## 조회와 저장

- 상품 목록·상세·브랜드·카테고리 규격·출고지·반품지 조회는 `/api/coupang` 아래에서 제공한다. 현재 가격·판매 수량은 상세 등록값으로 대신하지 않고 옵션별 현재 값으로 읽는다.
- 작업본의 진입·목록·생성·조회·저장은 `/api/coupang/working-copies`로 통일한다. 별도 edit-data·검증·세션 갱신·연결·비교 API는 제공하지 않는다.
- 새 편집기를 여는 것만으로 작업본을 만들지 않는다. 임시 저장은 로컬 작업본과 이미지 참조만 변경하며 쿠팡 쓰기를 실행하지 않는다.
- 조회 원문·계정·권한·상품 및 옵션 식별자는 서버 소유다. 브라우저 요청으로 교체하지 않는다. 계정·revision·식별자·권한 변경 시 입력을 보존하고 재준비한다.

## 전송 전문과 실행

일반 상품 수정은 전송 직전 GET 원문에 명시한 수정만 패치한 PUT이다. 직접 수정한 값은 최신 외부 값보다 우선한다. 고시·인증·서류의 수정은 행·필드 단위이며 미수정 행을 DTO로 다시 만들지 않는다. 이미지·상세설명은 옵션별 목록 단위다. 가격·수량·정상가는 옵션 전용 PUT, 배송은 승인 불필요 PUT을 사용한다. 정확한 제한은 각 SOURCE와 현재 서버 권한으로 확인한다.

신규 등록은 CREATE POST(`requested=false`)와 APPROVAL PUT이다. 승인 필요 상품 수정도 저장 후 승인을 요청한다. 전송 준비는 완성된 단계 목록을 한 번 반환하며 별도 승인 준비 API나 선택 플래그를 두지 않는다. 실제 요청 직전 콜백으로 전송 전문을 기록한다.

새 전문은 schemaVersion 4다. 과거 1~3 전문·해시·키는 재작성하지 않고 조회·결과 확인에 사용한다. 이전 형식으로 재쓰기하지 않으며 새 변경 확인을 요구한다. 중복 실행 방지, 계정 임대·하트비트, 부분 성공과 UNKNOWN·ACCEPTED 보호를 유지한다. 응답을 잃었다고 등록·수정을 다시 보내지 않는다. GET과 PUT 사이 외부 변경을 원자적으로 막는 기능은 보장하지 않는다.

## 복구와 이미지

세션 만료·앱 재시작 후 신규 입력과 옵션 UUID를 유지한다. 등록상품은 최신 조회값에 명시한 수정과 새 미저장 입력만 재적용한다. 서버 세션이 알고 있던 미완료 의도를 대조해, 완료 직후 저장이 전송한 값을 다시 의도로 남기지 않게 한다. 새 값과 입력은 보존한다.

같은 이미지 URL·설명을 여러 번 사용하거나 외부에서 목록을 변경해도 미디어 ID를 중복 배정하지 않는다. 원본·공개 토큰·편집본·실행 증거·감사 참조의 수명은 media가 관리한다. 공용 R2 삭제와 매입 브라우저 프로필 정리는 판매 검증에 포함하지 않는다.

## 문서·설정·검증

- 공식 수정 계약: [상품 수정 SOURCE](../../coupang-api-docs/coupang/api/products/modify-product.md), [수정 가능 상태 FAQ](../../coupang-api-docs/coupang/faq/products/when-is-the-status-that-can-be-modified-by-the-modify-product-api.md), [구매옵션 공지](../../coupang-api-docs/coupang/notices/notice-mandatory-purchase-option-entry-and-api-specification-changes-effective-f.md), [KNOWN-ISSUES](../../coupang-api-docs/coupang/KNOWN-ISSUES.md).
- 실행 설정은 기존 런타임에서 읽는다. `application-secret/` 파일은 직접 열거나 수정하지 않는다. 키·서명·Authorization·연락처·요청 원문을 공개 응답이나 로그에 복사하지 않는다.
- 현재 스키마는 [V48 보존 전환](../coupang-intent-migration.md), 작업본 구조는 [V47 전환](../coupang-working-copy-migration.md)을 따른다. 적용된 V1~V48과 감사 증거는 보존한다.
- 재현 명령은 [테스트 안내](../testing.md), 최신 결과는 [2026-10-16 재검토](../verification/2026-10-16-coupang-rereview.md), 이전 적용 결과는 [V48 검증](../verification/2026-10-15-coupang-intent.md)에 둔다. 현재 안내에서 제거한 날짜별 중복 설명은 기존 검증 기록과 이번 수정 전 소스 백업으로 추적한다.

상품 목록·상세는 사용자 동작으로 조회한다. 외부 ID는 문자열로 유지하고 실패·부분 응답을 삭제나 0수량으로 해석하지 않는다. 작업본 저장은 상품업무·매입·보유 재고를 자동 변경하지 않는다. 기존 별도 첫 조회 안내는 이 계약과 [작업본](working-copies.md)에 통합했다.
