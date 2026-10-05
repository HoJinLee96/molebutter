# 쿠팡 Wing — 판매 상품 조회

> 문서 유형: 외부 API 조사와 미구현 연동 제안. 외부 사양은 조사 당시 기준이며 실제 계정 호출·연동 완료를 뜻하지 않는다. 구현 전 최신 공식 문서와 계정 권한을 다시 확인한다. 현재 코드 경계는 [모듈 구조](../modular-architecture.md)를 따른다.

확인일: 2026-10-03. 판매자 Open API의 상품 목록·상세와 인증·FAQ·공지 기준이다. 쿠팡 Partners·Travel API와 구분한다. 현재 포털과 기존 샘플이 혼재하므로 필요한 부분만 채택한다.

## 키·사용 준비

사업자 판매자 계정을 만들고 WING에서 **자체개발**로 Open API 키를 발급한다. `vendorId`, Access Key, Secret Key와 허용 IP가 필요하다. IP는 문서상 최대 10개다. 최초 접근 권한은 24시간 이상 걸릴 수 있고, 키 재발급·연동 정보 변경 반영은 최대 30분 안내가 있다. 별도 테스트 환경은 제공하지 않는다고 명시되어 있다. 키 유효 기간은 180일이며 재발급 시 Secret Key가 바뀐다. [키 발급 상세](https://developers.coupang.com/ko/getting-started/issue-open-api-keynew)

첫 검증은 운영 계정의 읽기 API로 수행한다. 메인 페이지의 mock 호출 안내를 독립 sandbox가 있다는 근거로 사용하지 않는다. [포털 개요](https://developers.coupang.com/ko), [키 발급](https://developers.coupang.com/ko/getting-started/issue-open-api-keynew)

## HMAC 계약

```text
signedDate = 현재 UTC, yyMMdd'T'HHmmss'Z'
message = signedDate + uppercaseMethod + path + queryWithoutQuestionMark
signature = lowercaseHex(HMAC-SHA256(secretKey, UTF-8(message)))

Authorization: CEA algorithm=HmacSHA256, access-key=<accessKey>, signed-date=<signedDate>, signature=<signature>
```

호스트와 `?`는 message에 포함하지 않는다. 최종 URL에서 전송할 인코딩된 query 문자열을 한 번 만들고 같은 값을 서명에 사용한다. 재정렬·이중 인코딩·서명 후 필드 변경을 하지 않는다. 공식 Python·PHP·C# 예제의 서명 순서와 헤더를 대조했다. [HMAC 가이드](https://developers.coupang.com/ko/getting-started/creating-hmac-signature)

서명은 최대 5분 유효하므로 매 호출·재시도마다 생성한다. UTC와 서버 시간, vendorId·키·실제 경로를 함께 확인한다. [만료 FAQ](https://developers.coupang.com/ko/faq/specified-signature-is-expired-401-error-return), [잘못된 서명 FAQ](https://developers.coupang.com/ko/faq/an-invalid-signature-error-is-returned-hmac-error)

## 첫 목록 요청

```text
GET https://api-gateway.coupang.com/v2/providers/seller_api/apis/api/v1/marketplace/seller-products?vendorId=<vendorId>&maxPerPage=10
Authorization: <위의 CEA 헤더>
Accept: application/json
```

첫 요청은 `nextToken`을 생략한다. 문서의 필수 필드는 `vendorId`이며 페이지 크기 기본 10·최대 100이다. 응답은 `code`, `message`, `data[]`, `nextToken`이다. 목록에는 등록상품 ID·상품명·브랜드·등록상태·노출상품 ID 등이 있고, 상세 옵션은 별도로 읽는다. `nextToken` 빈 문자열이면 마지막이다. 반환된 토큰을 다음 요청에 그대로 사용한다. [목록 상세](https://developers.coupang.com/ko/api/products/product-list-paging-query)

필터 없는 호출은 임시저장·심사 등 등록상품을 포함할 수 있다. `status=APPROVED`는 승인 상태 필터이며 개별 옵션이 현재 판매 가능한지는 별도 판정이다. 등록상품 상태와 판매 여부를 하나의 상태로 합치지 않는다. [목록](https://developers.coupang.com/ko/api/products/product-list-paging-query), [옵션 수량·가격·상태](https://developers.coupang.com/ko/api/products/query-quantitypricestatus-by-product-items)

## 상세와 식별자

| 값 | 역할 |
|---|---|
| `vendorId` | 판매자 계정 |
| `sellerProductId` | 판매자가 등록한 상품 단위. 목록 → 상세에 사용 |
| `sellerProductItemId` | 등록상품의 아이템. 옵션 정보 수정에 사용 |
| `vendorItemId` | 판매 옵션 단위. 현재 가격·수량·판매 여부 조회에 사용 |
| `productId` | 소비자 노출상품. 결합·분리로 바뀔 수 있어 연결의 유일 키로 사용하지 않음 |
| `externalVendorSku` | 판매자가 넣은 옵션 관리 코드. 내부 상품 연결 후보 |

근거: [Open API 단위 설명](https://developers.coupang.com/ko/getting-started/coupang-open-api), [상품 상세](https://developers.coupang.com/ko/api/products/querying-product).

```text
GET /v2/providers/seller_api/apis/api/v1/marketplace/seller-products/{sellerProductId}
GET /v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/{vendorItemId}/inventories
```

상세는 `items`의 옵션 ID·속성·이미지·관리 코드 등을 확인하는 데 사용한다. 옵션 현재 조회는 `data.sellerItemId`, `amountInStock`, `salePrice`, `onSale`를 반환한다. 응답의 `sellerItemId` 이름을 `sellerProductItemId`와 같다고 가정하지 않는다. 조회에 사용한 `vendorItemId`와 대조한다. [상세](https://developers.coupang.com/ko/api/products/querying-product), [옵션 현재 정보](https://developers.coupang.com/ko/api/products/query-quantitypricestatus-by-product-items)

목록 구간 조회는 생성일 기준 최대 10분 범위다. 전체 상품 최초 수집은 페이징 조회로 시작한다. 수정사항 수집을 생성일 구간 조회로 대체하지 않는다. [구간 조회](https://developers.coupang.com/ko/api/products/product-list-ranging-query)

로켓그로스 목록은 같은 경로에 `businessTypes=rocketGrowth`를 추가하는 규격이 있다. 생략하면 기존 마켓플레이스·혼합 상품 범위이고 로켓그로스 전용 상품을 모두 포함한다고 볼 수 없다. 첫 호출 후 실제 계정의 운영 형태를 확인한다. [로켓그로스 목록](https://developers.coupang.com/ko/api/rocket-growth/product-list-paging-query-rocket-growth-rocket-growthmarketplace-hybrid-products)

## 문서 불일치와 적용 기준

| 관찰한 부분 | 근거 | 적용·확인 |
|---|---|---|
| 메인에 `api.coupang.com` 표기 및 축약 조회 경로 | [개요](https://developers.coupang.com/ko) | endpoint 상세의 `api-gateway.coupang.com`과 seller-products 경로 사용 |
| 상세 결과 설명의 `SUCCES`, 성공 예시의 `SUCCESS` | [상세](https://developers.coupang.com/ko/api/products/querying-product) | `SUCCESS`로 시작. 오타를 임의 정상 코드로 허용하지 않음 |
| 목록 요청 nextToken은 number, 응답은 string | [목록](https://developers.coupang.com/ko/api/products/product-list-paging-query) | 반환 문자열을 query로 전달. 자체 증가하지 않음 |
| URL 예시 변수명 `maxPerSize`, 파라미터명 `maxPerPage` | [목록](https://developers.coupang.com/ko/api/products/product-list-paging-query) | 실제 query 이름은 `maxPerPage` |
| 목록 statusName 설명은 영문 상태, 예시는 한국어 | [목록](https://developers.coupang.com/ko/api/products/product-list-paging-query) | 원본 값 유지, 실제 응답으로 표시 매핑 확인 |
| 위반 필터의 표 이름과 URL query 이름이 다름 | [목록](https://developers.coupang.com/ko/api/products/product-list-paging-query) | 첫 요청에서 제외. 후속 사용 전 필드명 확인 |
| businessTypes 필수 표시와 생략 가능 설명이 함께 있음 | [로켓그로스 목록](https://developers.coupang.com/ko/api/rocket-growth/product-list-paging-query-rocket-growth-rocket-growthmarketplace-hybrid-products) | 실제 마켓플레이스/그로스 범위를 따로 검증 |
| Python HMAC 샘플이 TLS 검증을 끄고 인증 헤더를 출력 | [HMAC](https://developers.coupang.com/ko/getting-started/creating-hmac-signature) | 서명 규칙만 사용. TLS 검증 유지·인증 헤더 로그 제외 |

문서 오류와 실제 API 동작은 구분한다. 위 표는 **문서에서 확인한 차이**이며, 운영 API가 그 오타·타입으로 응답했다고 주장하는 것이 아니다.

## 실패 처리·호출 제한

HTTP 200뿐 아니라 목록·상세의 `code=SUCCESS`와 기대 `data` 구조를 확인한다. 401은 키·시계·서명, 403은 허용 IP·접근 권한, 400은 파라미터·ID 종류를 확인한다. 인증 실패를 계속 재시도하지 않는다.

전체 API 제한 공지는 vendorId 기준 초당 5회 이상 호출 제한을 설명하고, 후속 공지는 상품 관련 제한이 계정·시스템에 따라 변경될 수 있다고 한다. 첫 검증은 직렬·소량 호출로 시작하며 이 수치를 고정 처리량 보장으로 보지 않는다. [도입 공지](https://developers.coupang.com/ko/notices/introduction-of-open-api-rate-limit-policy), [강화 공지](https://developers.coupang.com/ko/notices/notice-on-strengthening-openapi-speed-limit-policy-october-12-2023)

반복 오류로 발생하는 `Sorry! Access denied`는 일반 IP 미등록 오류와 다르다. 공식 FAQ는 약 10분 요청 중단을 안내한다. 429·403을 짧은 간격으로 계속 재시도하지 않고 계정 단위 호출을 멈추어 원인을 확인한다. [2026-04-30 FAQ](https://developers.coupang.com/ko/faq/how-to-resolve-sorry-access-denied-http-403-error-when-calling-coupang-openapi)

## 실제 호출 때 남길 확인

- WING의 키·vendorId·허용 IP·만료일·권한 반영 여부.
- 첫 페이지 200 + SUCCESS, 알려진 등록상품 ID와 상세 1건의 일치.
- 다음 페이지 토큰·등록상태의 실제 타입과 빈 목록 형태.
- 옵션 ID별 현재 판매 가격·재고·판매 가능 여부.
- 로켓그로스 전용·혼합 상품 보유 여부와 조회 범위.

주문·배송·반품·교환·프로모션·물류·문의·정산은 별도 API 계열이다. 판매 옵션 조회 성공을 해당 기능 전체의 연동 성공으로 간주하지 않는다. [공식 API 목록](https://developers.coupang.com/ko/api)
