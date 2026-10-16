# 쿠팡 앱 HTTP 경로

현재 컨트롤러 매핑을 기준으로 정리했다. 쿠팡 공식 API 주소와 앱 주소를 구분한다. 공개 이미지 토큰 경로 외에는 관리자 인증과 현재 권한을 확인한다. 쓰기 요청은 CSRF를 검증하며 작업본 저장은 로컬 저장, 전송 실행은 별도 미리보기·확인을 거친다. 서버가 반환한 이미지 `previewUrl`도 사용하는 경로이므로 단순 JS 문자열 검색만으로 삭제하지 않는다.

| Method | 앱 주소 | 컨트롤러 |
|---|---|---|
| GET | `/api/coupang/assets/{id}` | `CoupangAssetController` |
| GET | `/api/coupang/shipping-places/outbound/{code}` | `CoupangController` |
| GET | `/api/coupang/shipping-places/{kind}` | `CoupangController` |
| GET | `/api/coupang/brands` | `CoupangController` |
| GET | `/api/coupang/products` | `CoupangController` |
| GET | `/api/coupang/products/{sellerProductId}` | `CoupangController` |
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
