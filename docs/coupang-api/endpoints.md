# 쿠팡 앱 HTTP 경로

현재 컨트롤러 매핑을 기준으로 정리했다. 쿠팡 공식 API 주소와 앱 주소를 구분한다. 공개 이미지 토큰 경로 외에는 관리자 인증과 현재 권한을 확인한다. 쓰기 요청은 CSRF를 검증하며 작업본 저장은 로컬 저장, 전송 실행은 별도 미리보기·확인을 거친다. 서버가 반환한 이미지 `previewUrl`도 사용하는 경로이므로 단순 JS 문자열 검색만으로 삭제하지 않는다.

| Method | 앱 주소 | 컨트롤러 |
|---|---|---|
| GET | `/api/coupang/assets/{id}` | `CoupangAssetController` |
| GET | `/api/coupang/shipping-places/outbound/{code}` | `CoupangController` |
| GET | `/api/coupang/shipping-places/{kind}` | `CoupangController` |
| GET | `/api/coupang/brands` | `CoupangController` |
| GET | `/api/coupang/products` | `CoupangRegisteredProductController` |
| GET | `/api/coupang/products/{productId}` | `CoupangRegisteredProductController` |
| GET | `/api/coupang/categories` | `CoupangController` |
| GET | `/api/coupang/categories/{categoryCode}/rules` | `CoupangController` |
| POST | `/api/coupang/requests/{requestId}/cancel` | `CoupangController` |
| GET | `/api/coupang/image-edits/source` | `CoupangImageEditController` |
| GET | `/api/coupang/image-edits/source/{token}` | `CoupangImageEditController` |
| POST | `/api/coupang/image-edits` | `CoupangImageEditController` |
| GET | `/api/coupang/orders` | `CoupangOrderController` |
| GET | `/api/coupang/orders/{orderId}/detail` | `CoupangOrderController` |
| GET | `/api/coupang/orders/collections` | `CoupangOrderController` |
| POST | `/api/coupang/orders/collections` | `CoupangOrderController` |
| GET | `/api/coupang/orders/collections/{id}` | `CoupangOrderController` |
| POST | `/api/coupang/orders/collections/{id}/cancel` | `CoupangOrderController` |
| POST | `/api/coupang/orders/collections/{id}/retry` | `CoupangOrderController` |
| GET | `/coupang/images/{token}` | `CoupangPublicImageController` |
| GET | `/api/coupang/r2-images` | `CoupangR2ImageController` |
| GET | `/api/coupang/r2-images/resolve` | `CoupangR2ImageController` |
| POST | `/api/coupang/submissions/prepare` | `CoupangSubmissionController` |
| POST | `/api/coupang/submissions/{previewId}/execute` | `CoupangSubmissionController` |
| GET | `/api/coupang/submissions/{id}` | `CoupangSubmissionController` |
| GET | `/api/coupang/submissions` | `CoupangSubmissionController` |
| POST | `/api/coupang/submissions/{id}/retry` | `CoupangSubmissionController` |
| POST | `/api/coupang/submissions/{id}/reconcile` | `CoupangSubmissionController` |
| POST | `/api/coupang/submissions/{id}/revise` | `CoupangSubmissionController` |
| GET | `/api/coupang/working-copies` | `CoupangWorkingCopyController` |
| GET | `/api/coupang/working-copies/entry` | `CoupangWorkingCopyController` |
| GET | `/api/coupang/working-copies/{id}` | `CoupangWorkingCopyController` |
| POST | `/api/coupang/working-copies` | `CoupangWorkingCopyController` |
| PUT | `/api/coupang/working-copies/{id}` | `CoupangWorkingCopyController` |

이전 다중 마켓·drafts·edit-data·연결·비교 경로와 사용하지 않는 multipart `POST /api/coupang/assets`는 제공하지 않는다. 이미지 자르기는 R2 원본을 조회해 서버에서 검증하며 `GET /api/coupang/image-edits/source/{token}`와 기존 자산 읽기·공개 이미지 경로는 유지한다. [작업본](working-copies.md), [주문](sales-orders.md), [전송 복구](submission-recovery.md)를 따른다.

등록상품 화면: `GET /coupang/products` (이전 `/coupang`은 404).

| Method | 등록상품 API | 동작 |
|---|---|---|
| GET | `/api/coupang/products/brands` | DB 브랜드 선택지 |
| GET | `/api/coupang/products/{productId}/history` | 변경 이력 페이지 |
| POST | `/api/coupang/products/collections` | 전체/선택/기간+최근 N개 수집 시작 (RECENT 조건은 등록상품 문서 참조) |
| GET | `/api/coupang/products/collections/latest` | 현재 계정의 최근 수집 |
| GET | `/api/coupang/products/collections/{id}` | 수집 상태 |
| POST | `/api/coupang/products/collections/{id}/cancel` | 수집 취소 |
| POST | `/api/coupang/products/collections/{id}/resume` | 재개·실패 재시도 |
| POST | `/api/coupang/products/{productId}/conflicts/resolve` | 버전·revision 확인 후 값 선택 |
| POST | `/api/coupang/products/{productId}/deletion-confirmation` | 로컬 삭제 후보 확정·거절 |
| POST | `/api/coupang/products/{productId}/actions/prepare` | 판매중지·재개·삭제 미리보기 |

목록 조건은 `id`, `brand`, `name`, `sku`, `stock`, `stockComparison`, `saleStatus`, `shippingDays`, `shippingComparison`, `deleted`, `group`, `page`, `size`다. 전체 가져오기는 `sellerProductIds: []`, 선택 갱신은 등록상품 ID 목록을 보낸다. 실제 전송은 기존 `/api/coupang/submissions/{previewId}/execute`를 사용한다.

목록 응답의 `counts`는 검색과 무관한 현재 계정의 옵션 행 집계다. `group=ALL|SELLING|SOLD_OUT|STOPPED`이며 삭제 카드에는 `deleted=true&group=ALL`을 사용한다. 작업본 목록은 선택적인 `mode=CREATE|UPDATE`를 지원하며 해당 조건으로 건수와 페이지를 계산한다. 신규 임시저장 모달은 `mode=CREATE&size=20`을 사용한다.

### 등록 준비와 검색어

- `POST /api/coupang/working-copies/search-tags`: 편집 권한과 CSRF를 검사하는 검색어 비저장 제안. `title`·`brand`·`context {gender, material, category, colors}`를 받아 `tags`와 `excluded [{value, reason}]`를 반환한다. 쿠팡 호출·작업본 저장·전송을 실행하지 않는다. 최초 등록 준비도 같은 생성기를 사용하며 준비 응답의 선택적 `searchTagContext`와 기존 검색어 필드를 사용한다.

### 옵션 빠른 수정과 계산 설정

- `GET /api/coupang/products/{id}/options/{vendorItemId}/quick-edit?kind=PRICE|STOCK&internalProductId=...`: 최신 편집 문맥·단일 옵션의 현재 가격과 재고(`currentInventory`, 기존 `currentValue` 유지)·저장된 의도·선정 근거·계산 설정. 재고 후보에 전체 옵션 수와 이전 조회 여부·확인 시각을 포함한다.
- `GET /api/coupang/products/{id}/options/{vendorItemId}/quick-edit/history?kind=PRICE|STOCK&page=0&size=20`: DB에서 해당 옵션·항목만 추출한 변경·저장·전송 이력. 전후 숫자값과 상태를 반환하며 쿠팡 조회를 실행하지 않는다.
- `POST /api/coupang/products/{id}/options/{vendorItemId}/quick-edit/prepare`: kind/value/sessionId/revision/observedVersion/productId/fingerprint/pricingRevision/purchaseCost. 해당 한 항목을 저장하고 기존 submissions 미리보기를 반환한다. 실제 전송은 기존 execute·reconcile·revise를 사용한다.
- `POST /api/coupang/products/{id}/bulk-edit/context`: kind·options[{vendorItemId,internalProductId}]·선택적 sessionId. 같은 상품의 1~100옵션 DB 문맥과 공통 편집 세션·설정·관측을 묶어 반환한다. 쿠팡 조회·수정 의도 저장은 하지 않는다. 기존 상품 권한을 적용한다.
- `POST /api/coupang/products/{id}/bulk-edit/options/{vendorItemId}/prepare`: 기존 빠른 수정 입력을 서버 DB 관측으로 단일 항목 준비한다. 쿠팡 조회는 실행 직전으로 모으고 최신값·코드·충돌·중복 전송 보호와 기존 submissions API를 유지한다.
- `GET/PUT /api/settings/coupang-pricing`: 앱 공통 revision/shippingCost/commissionPercent/marginPercent. 조회는 기존 공통설정 권한, 저장은 ADMIN·CSRF·revision 검증과 운영 감사를 적용한다.

- `POST /api/coupang/working-copies/brand-logo`: 노출상품명으로 현재 계정의 저장 로고를 판별한다. 권한·CSRF 검사를 적용하며 설정/작업본 저장과 쿠팡 호출은 하지 않는다. 요청/응답은 등록 준비 안내의 브랜드 로고 계약을 따른다.
