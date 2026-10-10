# 쿠팡 Wing — 판매 상품 조회·저장

2026-10-07 이미지 편집: 화살표·대표 설정 버튼 대신 카드 드래그로 순서와 유형을 변경한다. 추가 이미지를 대표 영역에 놓으면 기존 대표는 추가 이미지로 이동한다. 대표를 추가 영역으로 이동하면 대표는 비어 있으며 입력 검증에서 누락을 확인한다. SOURCE의 유형별 제한(대표 1개·DETAIL 9개·USED_PRODUCT 4개)을 URL·파일 추가, 드래그 이동과 입력 검증에 적용한다. 설명 콘텐츠 이미지는 이 제한과 별도다. 기존 중고 이미지 유형은 별도 영역에 보존한다. 실제 외부 저장은 이번 UI 검증에서 수행하지 않았다.

2026-10-07 옵션 편집 UI: 옵션 설정 구역을 제거하고 옵션명 버튼은 대표·추가 이미지와 설명만 전환한다. 상품주요정보·검색어·검색필터·고시는 옵션 공통 입력으로 표시하되, 사용자가 편집한 필드만 각 옵션의 API 구조에 반영한다. 속성·고시·인증은 배열 순서가 아닌 이름·유형으로 대응하며 MPN·가격·수량·상품코드·구매 속성은 이 공통 적용에서 제외한다. 기존 값이 다른 공통 필드는 ‘옵션별 값 다름’으로 표시하고 미편집 값은 유지한다. 배송·반품은 상품 루트의 공통값이다. 신규 등록의 필수 구매 속성 입력은 옵션 목록 안에 유지한다. 외부 쓰기 호출은 검증하지 않았다.

> 문서 유형: 외부 API 조사와 쿠팡 상품 조회·저장 구현 안내. 외부 사양은 조사 당시 기준이며 실제 계정 호출·연동 완료를 뜻하지 않는다. 날짜가 붙은 적용·검증 문단은 당시 기록이며 현재 범위는 마지막 구현 문단과 [공통 편집 문서](common-product-editor.md)를 따른다. 현재 코드 경계는 [모듈 구조](../modular-architecture.md)를 따른다.

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


## 구현 범위와 실행 (2026-10-05)

`molebutter-marketplace-coupang`이 쿠팡 HMAC 인증·조회·응답 해석을 담당하고 app이 화면·HTTP를 제공한다. common·identity 공개 계약만 의존하며 DB에 조회 결과를 저장하지 않는다. 관리자는 **판매 마켓**(`/marketplaces`)에서 조회를 눌러 등록상품 ID·노출상품 ID·브랜드·등록상품명·등록상태·등록일시를 확인한다. PRODUCT·VIEWER는 메뉴와 직접 URL/API 접근이 차단된다. 등록상태를 실제 판매 가능 여부로 해석하지 않는다.

내부 API는 `GET /api/marketplaces/coupang/products`이며 `maxPerPage=10|50|100`, 선택적 `nextToken`과 아래 검색 조건을 받는다. `ApiResponse.data`에는 `items`, 문자열 `nextToken`, `hasNext`가 있다. 총 건수·총 페이지 수는 만들지 않는다. 처음·이전·다음은 방문 토큰으로 다시 조회하고 새 조회·크기 변경은 이력을 초기화한다. 서버는 계정당 단일 실행을 유지하고, 외부 요청 시작 간격 1초가 남으면 대기한다. 실제 HTTP 429만 실패한 GET을 한 번 재시도하며 전체 수집은 하지 않는다.

앱 재기동 후 관리자 메뉴에서 실행한다. 통합 파일 `application-secret/marketplace.properties`를 로컬 classpath와 실행 디렉터리 외부 파일에서 읽는다. `coupang_access_id`는 vendorId, `coupang_access_key`는 Access Key, `coupang_secret_key`는 Secret Key다. 이전 개별 `coupang.properties`는 자동으로 읽지 않는다. 설정이 없으면 앱은 기동하고 조회 시에만 연결 설정 안내(503)를 반환한다. 키·서명·Authorization·원문 오류는 브라우저와 로그에 출력하지 않는다. 파일은 Git·배포 JAR에서 제외한다.

호스트는 `https://api-gateway.coupang.com`으로 고정한다. TLS 검증을 유지하고 리다이렉트를 따르지 않는다. 연결 10초, 본문 완료까지 전체 20초, 최대 5MiB를 적용하며 시간·용량 초과 시 수신을 취소한다. 서명과 실제 요청에 동일한 인코딩 query를 사용한다. HTTP 200이어도 `code=ERROR` 또는 목록 구조 오류는 실패다.

검증 명령:

```bash
./mvnw -pl molebutter-marketplace-coupang,molebutter-app -am -Dtest=CoupangClientTest,ModuleArchitectureTest,LayoutViewTest -Dsurefire.failIfNoSpecifiedTests=false test
node --test src/test/js/*.test.cjs
node scripts/test-product-ui.cjs
bash scripts/test-integration.sh '-Dtest=AuthenticationFlowIT#marketplaceRequiresCurrentAdministrator' -Dsurefire.failIfNoSpecifiedTests=false
```

실제 계정 검증은 명시적으로 실행한다. 앱이 비밀 파일을 로딩하며 임시 DB·Redis만 사용한다. 실행 디렉터리의 네트워크 출발 IP가 WING 허용 IP에 포함되어야 한다.

```bash
MOLEBUTTER_COUPANG_LIVE=true bash scripts/test-integration.sh \
  '-Dtest=AuthenticationFlowIT#coupangLivePages+coupangLiveSearch' -Dsurefire.failIfNoSpecifiedTests=false \
  '-Dspring.config.import=classpath:bootstrap-admin-test.properties,optional:classpath:/application-secret/marketplace.properties,optional:file:./molebutter-app/src/main/resources/application-secret/marketplace.properties'
```

실제 관찰: 2026-10-05 첫 페이지 외부 HTTP **403**, 업무 코드 확인 불가, 정상 목록 건수 확인 불가, 다음 페이지 여부 확인 불가. 내부 API는 **502 / COUPANG_PERMISSION**을 반환했다. 정상 연결 완료로 간주하지 않는다. 허용 IP·키 사용 권한 확인 후 다시 조회해야 한다. 원문을 공개하지 않으므로 IP와 권한 중 어느 원인인지는 확정하지 않았다. 다음 페이지는 정상 첫 응답 이후 검증한다.

가짜 키·고정 UTC 서명, 큰 ID·한글 상태·빈 목록·종료/다음 토큰·알 수 없는 필드, HTTP 오류·본문 중단·크기 초과·리다이렉트 거부·동시 호출 제한을 테스트한다. 상품 상세와 현재 가격·수량 읽기는 아래 확장 구현을 따른다. 로켓그로스 전용 조회·등록/수정·자동 동기화·내부 상품 연결은 포함하지 않는다. 제공받은 최신 `marketplace-api-docs`의 SOURCE·노트·KNOWN-ISSUES와 검수 기록을 참고하며, 문서 검수와 실제 호출 관찰을 구분한다.


### 목록 화면과 추가 응답 필드

목록은 등록상품 ID → 노출상품 ID → 브랜드 → 등록상품명 → 등록상태 → 등록일시 순서다. 식별자·브랜드·상태·일시 열 너비를 고정하고 상품명에 남은 폭을 배분한다. 긴 상품명은 줄바꿈하고 좁은 화면에서는 표만 가로 스크롤한다. 페이지 크기·조회 버튼과 페이지 이동은 한 줄로 정렬하며 화면에는 HTTP 상태·업무 코드 대신 조회 건수를 보여준다.

제공받은 `marketplace-api-docs/coupang/api/products/product-list-paging-query.md`의 공식 원문 구간과 공식 목록 규격에는 추가로 `displayCategoryCode`(노출 카테고리 코드), `categoryId`(카테고리 ID), `vendorId`(판매자 ID), `saleStartedAt`·`saleEndedAt`(판매 시작·종료일시)이 있다. 예시의 `mdId`·`mdName`은 응답 정의 표에 없는 필드이므로 안정적인 필수 계약으로 사용하지 않는다. 현재 내부 목록 DTO는 화면의 여섯 필드만 반환하고 판매자 ID는 서버에서 계정 일치 확인에 사용한다. 추가 필드는 이번 UI 변경에서 새 열로 넣지 않는다. 이미지·옵션·현재 가격·현재 수량은 목록 규격에 없으므로 해당 조회 API를 별도로 연결해야 한다.

후속 사용자 확인: 2026-10-05 실제 화면에서 10개 조회와 이전·다음 이동이 된다고 전달받았다. 위의 403은 최초 개발 환경 호출 당시 기록이며, 후속 성공 응답 원문은 이번 UI 작업에서 다시 수집하지 않았다.


### 등록상품명과 노출상품명

등록상품명 `sellerProductName`은 판매자 관리·발주서용 이름이며 목록 페이징 API가 제공한다. 노출상품명 `displayProductName`은 별도 상품 조회 API `GET /v2/providers/seller_api/apis/api/v1/marketplace/seller-products/{sellerProductId}`의 응답 필드다. 실제 쿠팡 페이지에서는 카탈로그 처리에 의해 변경될 수 있어 이 필드를 실제 페이지 제목과 항상 같다고 보장하지 않는다. 목록 조회 API에는 `displayProductName`이 정의되어 있지 않으며 현재 화면에 표시한 등록상품명을 노출상품명으로 재사용하지 않는다. 제공받은 문서의 `querying-product.md` 공식 원문 및 [공식 상품 조회](https://developers.coupang.com/ko/api/products/querying-product)를 대조했다.

헤더와 데이터는 JS의 같은 열 정의로 정렬한다. 실행 중인 앱에 이전 템플릿이 남아 있을 때도 헤더를 현재 열 순서로 맞추며, 템플릿의 JS URL에는 버전을 붙인다. 템플릿·CSS 변경을 완전히 반영하려면 앱 재기동 후 페이지를 새로고침한다.


## 등록상품 상세 조회 구현 (2026-10-05)

목록의 등록상품명을 클릭하면 `GET /api/marketplaces/coupang/products/{sellerProductId}`를 호출하고 상세 모달을 연다. 쿠팡 상품 조회 경로에 등록상품 ID를 넣으며 query 없이 실제 경로를 서명한다. 목록과 동일한 계정·관리자 최신 권한·동시 요청 제한·1초 시작 간격·시간/용량 제한을 공유한다. 결과의 vendorId와 요청한 등록상품 ID가 일치하는지 확인한다. 오류는 안전한 안내와 다시 조회 버튼으로 표시하고 모달을 닫은 뒤 도착한 응답은 반영하지 않는다.

표시 범위는 등록상품 ID·노출상품 ID, 등록상품명·노출상품명, 브랜드·등록상태·제품명·상품군, 카테고리 코드/ID, 등록일시·판매 기간 및 업체상품옵션 ID·옵션 ID·등록 옵션명이다. 임시저장 옵션의 null ID는 ‘—’로 표시한다. 원문 JSON·상세 HTML·반품지 연락처 등은 브라우저에 그대로 전달하지 않는다. 내부 상품·재고 저장은 하지 않는다. 상세 응답의 salePrice·maximumBuyCount를 현재 가격/재고로 표시하지 않으며 현재 값은 옵션별 수량/가격/상태 조회의 후속 연동 대상이다.

선행 계획의 ‘상품 상세는 후속 범위’에서 사용자 요청으로 상세 읽기 모달을 확장했다. 등록·수정·자동 동기화·현재 옵션 가격/재고 실조회·로켓그로스 전용 조회는 계속 후속 범위다. 제공 문서의 공식 원문에는 성공 코드 `SUCCES` 오타가 있으나 공식 예시의 `SUCCESS`를 기존 목록과 동일하게 검증한다. 상세 DTO/응답 구조·타 계정/다른 ID 거절·빈 query 서명·목록과 상세 호출 제한 공유, 늦은 응답·재조회·두 상품명 분리 및 데스크톱/모바일 흐름을 검증한다.


실제 상세 연동 관찰: 2026-10-05 임시 DB 실행 앱의 관리자 API로 첫 목록 HTTP **200 / SUCCESS / 10개 / 다음 페이지 있음**, 첫 상품 상세 HTTP **200 / SUCCESS / 옵션 1개**, 다음 목록 HTTP **200 / SUCCESS / 10개 / 다음 페이지 있음**을 확인했다. 최초 403 이후 실제 연결이 정상 동작함을 재검증했다. 실제 응답을 테스트 자료로 복사하지 않았으며 기록에는 상품 ID·상품명·키·서명을 포함하지 않는다.


## 상품 클릭 시 현재 가격·재고 조회 확장 (2026-10-05)

상세 모달의 서버 요청 한 번에서 상품 상세 API를 1회 호출하고, 상세의 `vendorItemId`마다 `GET /v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/{vendorItemId}/inventories`를 호출한다. 현재 판매가격 `salePrice`, 옵션 잔여수량 `amountInStock`, 판매 상태 `onSale`을 함께 표시한다. 전체 가격·수량 합계로 변환하거나 내부 보유 재고를 변경하지 않는다. `onSale=true`와 수량 0은 각각 판매 중 설정과 잔여수량 0으로 표시하며 임의로 한 상태로 합치지 않는다.

호출 수는 목록 1회, 상품 클릭은 상세 1회 + 조회 가능한 서로 다른 옵션 ID 수 N회다. 옵션 하나면 2회, 옵션 세 개면 4회다. 상세 등록 값으로 현재 값을 대체하지 않는다. null 옵션 ID는 외부 호출 없이 미확인 안내를 표시하고, 중복 옵션 ID는 한 번만 호출해 결과를 공유한다. 문서상 응답 `sellerItemId`와 요청 `vendorItemId`의 동일성이 명확하지 않으므로 이름만 보고 강제 동일 비교하지 않는다. 요청 ID는 계정 일치를 확인한 상세 응답에서만 가져오며 임의 옵션 ID를 호출하는 내부 API를 추가하지 않는다. [공식 현재 값 조회 규격](https://developers.coupang.com/ko/api/products/query-quantitypricestatus-by-product-items)과 제공받은 같은 이름의 문서 공식 원문 구간을 대조했다.

동일 계정의 단일 실행 안에서 옵션 호출을 순차 수행하고 각 외부 요청 시작 간격을 최소 1초로 유지한다. 옵션이 많으면 상세 모달 로딩이 길어질 수 있다. 각 외부 요청의 본문 제한·20초 완료 기한은 유지한다. 옵션별 실패는 고정 안내와 null 현재 값으로 반환하며 나머지 상세는 보존한다. 인증·권한·호출 제한 실패 후에는 나머지 옵션 요청을 중단하고 같은 안내를 표시한다. HTTP 429 재시도 정책은 아래를 따른다. 모달 닫기·다른 상품 선택·페이지 이탈 시 브라우저 요청을 중단하고 서버에 취소를 전달하여 간격 대기·재시도 대기·본문 수신을 중단한다.

부분 실패·중복/null 옵션, 현재 0원·0개·판매 중/중지와 구조 오류, 여러 옵션의 요청 수 및 1초 간격을 검증한다. 기존 상세 읽기 단계에서 후속 범위였던 현재 값 조회를 이번 요청으로 확장했으며 등록·수정·자동 동기화·로켓그로스 전용 연동은 계속 후속 범위다.


실제 현재 값 관찰: 2026-10-05 임시 DB 앱에서 목록 첫 페이지 **200 / SUCCESS / 10개**, 상품 상세 **200 / SUCCESS / 옵션 1개**, 그 옵션의 현재 가격·재고 조회 **200 / SUCCESS / 정상 1개**, 목록 다음 페이지 **200 / SUCCESS / 10개**를 확인했다. 실제 가격·수량·상품 ID·키를 문서와 테스트 자료에 복사하지 않았다.


화면 안내 정책: 기능 설명이나 구현 상태를 설명하는 상시 안내 문구는 생략한다. 오류·필수 입력·작업 결과·중요한 오해 방지 등 사용자 판단에 꼭 필요한 경우에만 짧게 표시한다. 목록의 등록상태 설명과 상세의 옵션 조회 설명 문구를 삭제했다. 이 기준은 이후 화면 구현에도 적용한다.

## 목록 검색과 구역별 상세 — 현재 구현 (2026-10-05)

이 절이 앞선 단계별 구현 기록보다 우선한다. 현재 가격·재고, HTML 설명, 반품 정보는 이미 읽기 기능에 포함된다.

목록 기본 조건은 `sellerProductId`, `sellerProductName`(20자 이하), `status`(7개 코드), `createdAt`(유효한 yyyy-MM-dd 단일 등록일), 페이지 크기다. 빈 값은 생략하고 실제 전송 query를 그대로 서명한다. 업체코드는 서버 설정만 사용한다. 날짜 구간 API·정렬·총 건수 추정·수신 페이지 후 필터링은 구현하지 않았다. 검색·초기화·크기 변경은 첫 페이지로 돌아가며 이전·다음은 마지막 제출 조건을 사용한다. 미제출 입력은 페이지 이동에 영향을 주지 않는다.

제공받은 `marketplace-api-docs`의 공식 원문과 최신 [목록 문서](https://developers.coupang.com/ko/api/products/product-list-paging-query), [상세 문서](https://developers.coupang.com/ko/api/products/querying-product)를 대조했다. 문서 규격과 실계정 관찰을 구분한다.

### 실제 검색·이미지 검증

실행 앱을 통한 읽기 전용 검증에서 다음을 확인했다. 실제 상품·주소·연락처·이미지 원문·키는 자료로 저장하지 않았다.

| 검증 | 결과 |
|---|---|
| 첫 목록·다음 목록 | 각각 HTTP 200 / SUCCESS / 10개 / 다음 페이지 있음 |
| 등록상품 ID | HTTP 200 / SUCCESS / 1개 |
| 등록상품명 일부 | HTTP 200 / SUCCESS / 10개 |
| ID + 상품명 일부 | HTTP 200 / SUCCESS / 1개 |
| ID + 일치하지 않는 상품명 | HTTP 200 / SUCCESS / 0개 |
| ID + 상태 + 해당 등록일 | HTTP 200 / SUCCESS / 1개 |
| ID + 해당하지 않는 등록일 | HTTP 200 / SUCCESS / 0개 |
| 상세·현재 값 | HTTP 200 / SUCCESS / 옵션 1개 / 정상 현재 값 1개 |
| 상세 이미지 URL | 8개 URL에 HEAD 요청, 모두 HTTP 200. 이미지 본문을 저장하지 않음 |

제조사·상품정보 문제 유형·AND/OR 추가 검색은 사용자 요청으로 화면, 내부 API, 공개 검색 계약, 외부 query 생성, 관련 검증 코드에서 제거했다. 상품명 일부 일치는 위 표의 요청에서 관찰했으며 모든 문자열의 일치 방식·대소문자 규칙을 일반화하지 않는다. 상세의 제조사 정보 표시는 검색 조건과 별개로 유지한다.

### 상세 구역과 안전한 표시

기본 정보·옵션 판매 / 이미지·설명·고시 / 배송·반품 / 기타 설정의 네 탭으로 나눈다. 기본 정보 아래 옵션·판매 표를 함께 표시하고, 이미지와 상품 설명·고시는 같은 옵션 선택을 사용해 함께 표시한다. 상단 상품명·등록상품 ID·상태·닫기를 고정한다. 첫 탭은 기본 정보, 첫 옵션을 기본 선택한다. 각 구역 렌더러는 받은 조회 DTO를 사용하며 탭·옵션 선택은 외부 요청을 추가하지 않는다. 상세 응답의 등록일시가 없으면 클릭한 목록 행의 등록일시를 사용한다. 조회 모달에는 등록·수정 입력을 두지 않고 별도 공통 페이지로 이동한다. 저장 요청은 없으며 조회 DTO를 수정 요청 전문으로 재사용하지 않는다.

옵션 속성·이미지·콘텐츠·고시·인증·등록 설정은 옵션 소속을 보존한다. 현재 값은 inventories 결과만 사용하고 등록 판매가·판매 수량은 기타 설정에 별도로 표시한다. 배송·반품과 기타 설정은 허용한 스칼라 필드만 전달하고 알 수 없는 중첩 객체를 공개하지 않는다. 분류·출고지 명칭 조회 API는 호출하지 않는다. 알려진 코드만 한국어로 표시하고 미지 코드값은 유지한다.

대표 이미지 우선, 이후 imageOrder 순서로 배치한다. 쿠팡 CDN 경로를 우선 사용하고 대체 경로는 자격 정보 없는 유효한 HTTPS URL만 허용한다. 이미지 없음·로드 실패는 대체 표시한다. 목록 이미지는 추가하지 않는다.

HTML 콘텐츠는 `sandbox` 허용 플래그가 없는 iframe의 srcdoc로만 표시한다. CSP는 스크립트·연결·하위 프레임·폼 전송·base URL을 차단하고 HTTPS 이미지와 인라인 스타일만 허용한다. 외부 HTML을 상위 앱 DOM에 직접 넣지 않는다. 일반 텍스트와 고시는 HTML 이스케이프한다.

Java 클라이언트·모듈 경계·템플릿, JS 회귀, 관리자 최신 권한 및 PRODUCT/VIEWER 차단을 검증했다. 데스크톱 1440px·모바일 390px에서 검색/페이지/탭/옵션/이미지/HTML 격리/현재 값 흐름을 실행했다. 검증은 임시 DB로 실행했으며 운영 DB 적용·커밋·푸시·배포는 수행하지 않았다.

### 설명 이미지 경로 보정 (2026-10-05)

실제 설명 응답 두 건을 메모리에서 확인했다. HTML 설명은 HTTP 쿠팡 CDN 이미지 10개, IMAGE 설명은 `vendor_inventory/...` 상대 경로 12개를 반환했다. HTTPS만 허용한 기존 표시에서 전자는 CSP로 차단되고 후자는 유효한 URL로 인정되지 않았다.

표시 시 HTTP 쿠팡 CDN 주소만 HTTPS로 전환하고, 이미지형 설명의 알려진 상대 CDN 경로에는 `https://img1a.coupangcdn.com/image/`를 붙인다. 임의 판매자 HTTP 주소는 허용하지 않는다. 저장된 조회 결과와 외부 응답 자체를 수정하지 않는다. 두 상품의 설명 이미지 총 22개는 보정된 HTTPS URL의 HEAD 요청에서 모두 200을 반환했다. 원문 URL·이미지·상품 정보는 검증 자료로 저장하지 않았다. HTML sandbox와 CSP의 스크립트·폼·상위 화면 접근 차단은 유지한다.

구매 속성(`EXPOSED`)은 기본 정보·옵션 판매의 옵션 표에 표시한다. 검색 속성(`NONE`)은 기타 설정의 옵션 선택에 맞춰 별도 구역에서 표시한다. 속성을 상품 전체 공통 값으로 합치지 않는다.

### 등록·수정 공통 입력 페이지 (2026-10-05)

조회 모달은 읽기 용도로 유지하며 상단 수정 버튼으로 `/marketplaces/coupang/products/{sellerProductId}/edit`에 이동한다. 목록의 상품 등록 버튼은 `/marketplaces/coupang/products/new`로 이동한다. 두 페이지는 같은 템플릿·입력 컴포넌트를 사용하고 네 구역을 세로로 배치한다. 상단 구역 이동, 하단 취소·입력 검증 버튼을 제공한다. ADMIN만 페이지와 API를 사용할 수 있다.

`CoupangEditor`는 조회용 `CoupangCatalog.ProductDetail`과 별도의 편집 초기 데이터·수정 제한·카테고리 규격 계약이다. `GET /api/marketplaces/coupang/products/{sellerProductId}/edit-data`는 상품 상세와 서로 다른 옵션의 현재 값들을 읽는다. `GET /api/marketplaces/coupang/categories/{categoryCode}/rules`는 카테고리 메타정보를 읽어 필수 속성·그룹 속성·고시·인증·구비 서류를 전달한다. 기존 단일 실행·호출 간격·서명·시간·본문 용량 제한을 공유하며 쓰기 API는 추가하지 않았다. 옵션 전환·입력 검증은 외부 요청이 없다.

신규 등록은 빈 옵션 하나로 시작하며 옵션 추가·삭제를 제공한다. 수정에서는 ID·카테고리·기존 구매 속성·옵션 구성을 잠그고, 승인된 옵션의 등록 가격·수량과 현재 판매 정보 및 변경 입력을 구분한다. 현재 조회 실패를 0원·0개로 바꾸지 않는다. 조회 규격에 포함된 알 수 없는 속성과 기존 콘텐츠·서류 대체 경로는 입력 상태에서 보존한다. 알려지지 않은 외부 중첩 객체를 브라우저에 전달하거나 조회 DTO를 쓰기 전문으로 사용하지 않는다.

이미지는 HTTPS URL 또는 로컬 JPG·PNG 파일로 추가하고 대표 선택·순서 변경·삭제·미리보기를 제공한다. 파일은 브라우저 메모리의 object URL로만 처리한다. 3MiB 이하, 500~5000px 정사각형을 검사하고 삭제·페이지 이탈 시 URL을 해제한다. 서버 업로드·공개 이미지 저장은 없다. 기존 HTML 원문과 IMAGE 콘텐츠를 편집할 수 있고 HTML 미리보기의 sandbox·CSP 격리는 유지한다.

입력 검증은 필수값·공식 형식/길이·카테고리 규격·이미지·고시·인증 및 조건부 서류를 확인하고 첫 오류 구역으로 이동한다. 성공은 `입력 검증 완료`이며 쿠팡 등록·수정 성공을 뜻하지 않는다. 파일 업로드·상품 쓰기·승인 요청을 구현하지 않았고 입력은 새로고침 후 복원하지 않는다. MPN의 구매/검색 분류와 필수 여부는 각각 `exposed`와 `required`로 판단하며 WING 등록 화면과의 차이는 실제 등록·수정 단계에서 추가 검증한다.

목록 복귀를 위해 검색 조건·페이지 크기·방문 토큰·페이지 위치·스크롤만 일회성 sessionStorage에 보관하고 복귀 시 목록을 다시 조회한다. 상품 응답·주소·연락처·입력 초안·파일은 저장하지 않는다. 변경된 입력이 있으면 취소·페이지 이탈 시 확인한다.

실행 앱의 읽기 전용 검증에서 편집 초기 데이터 HTTP 200 / SUCCESS / 옵션 1개, 카테고리 메타정보 HTTP 200 / SUCCESS / 속성 21개를 확인했다. 실제 상품·연락처·이미지 원문·비밀 값은 테스트 자료나 문서에 저장하지 않았다. 운영 DB 적용·외부 쓰기·커밋·푸시·배포는 수행하지 않았다.

## 읽기 요청의 호출 제한 재시도 (2026-10-05)

실제 HTTP 429에서만 실패한 GET을 한 번 재시도한다. 유효한 `Retry-After` 초 또는 HTTP 날짜를 사용하고 없거나 잘못된 값이면 5초 대기한다. 재시도마다 새 UTC 시각으로 서명하며 query는 원래 요청 그대로 유지한다. 상세나 이미 성공한 옵션을 다시 조회하지 않는다. 인증·권한·타임아웃·응답 구조 오류에는 자동 재시도를 하지 않는다. 재시도도 실패하면 기존 오류와 수동 다시 조회를 제공한다.

자동 대기는 최대 30초다. 더 긴 유효한 대기 지시는 즉시 제한 오류를 표시하고 계정 공통 대기 시간에 반영하여 이른 외부 재호출을 막는다. 동시 조회는 기존처럼 거절하며 대기 중에도 단일 실행을 유지한다. 각 시도에는 기존 20초 본문 완료 기한·5MiB 제한을 적용한다.

화면은 읽기 요청별 UUID를 `X-Coupang-Request-Id`로 전달한다. 내부 `POST /api/marketplaces/coupang/requests/{requestId}/cancel`은 최신 ADMIN 권한·CSRF 검사와 요청 소유자 확인을 거친다. 쿠팡 쓰기 요청은 발생하지 않는다. 브라우저에서도 오래된 응답 반영을 차단한다. 이미 쿠팡에 도착한 요청 자체를 되돌리지는 않는다.

가짜 키·로컬 HTTP 서버로 429 후 성공, 반복 429, Retry-After, 새 서명, 내부 간격 대기, 긴 대기 지시, 소유자별 취소와 본문 수신 취소를 검증했다. 실제 계정에 제한을 유발하는 호출은 실행하지 않았다.

## 쿠팡 쓰기 규격 대조 (2026-10-06)

프로젝트의 `marketplace-api-docs/coupang/api/products` SOURCE와 같은 공식 페이지를 대조했다. LLM 노트의 권장값·추정과 공식 필수 표시를 구분한다. 이 대조는 실제 등록·수정 성공 기록이 아니다.

| 변경 | 외부 요청 | 적용 기준 |
|---|---|---|
| 신규 등록 | POST `/v2/providers/seller_api/apis/api/v1/marketplace/seller-products` | 계정·WING 사용자, 판매 기간, 배송·반품, 옵션·이미지·고시·속성을 준비 |
| 상품 정보 | PUT `/v2/providers/seller_api/apis/api/v1/marketplace/seller-products` | 외부 상품·옵션 ID와 기존 카테고리를 유지 |
| 배송·반품 | PUT `/v2/providers/seller_api/apis/api/v1/marketplace/seller-products/{sellerProductId}/partial` | 승인 없이 처리 가능한 필드만 전송. 임시저장·승인대기 상태는 제외 |
| 현재 판매가 | PUT `/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/{vendorItemId}/prices/{price}` | 승인된 옵션의 가격은 별도 요청. 10원 단위와 기본 변경 비율 제한 유지 |
| 현재 판매 수량 | PUT `/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/{vendorItemId}/quantities/{quantity}` | 승인된 옵션의 잔여 판매 수량은 별도 요청 |

위 요청은 하나의 트랜잭션이 아니다. 중간 실패가 앞선 외부 변경을 되돌리지 않으므로 상품·배송·옵션별 결과를 구분한다. 등록 상품 저장과 판매 승인은 구분하며 `requested=false`는 작성 내용 저장, `true`는 판매 승인 요청을 포함한다. HTTP 성공을 판매 승인 완료로 표시하지 않는다. [상품 생성](https://developers.coupang.com/ko/api/products/product-creation), [상품 수정](https://developers.coupang.com/ko/api/products/modify-product), [배송·반품 수정](https://developers.coupang.com/ko/api/products/product-modification-approval-not-required)

가격 변경 비율 제한을 해제하는 `forceSalePriceUpdate`를 자동으로 켜지 않는다. `unitCount`는 구성 단위이며 재고와 별개다. 실제 외부 ID는 서버에서 계정과 상품의 소속을 확인하고, 브라우저 입력으로 임의 교체하지 않는다. [가격 변경](https://developers.coupang.com/ko/api/products/changing-price-of-each-item-of-a-product), [수량 변경](https://developers.coupang.com/ko/api/products/changing-quantity-of-each-product-item)

신규 등록의 `brandId`와 기존 상품 수정의 지원 범위는 다르다. 2026-10-06 공식 FAQ의 Q6을 다시 확인했으며 기존 상품의 `brandId` 직접 수정·업데이트는 OpenAPI 미지원으로 명시되어 있다. 기존 값을 보존하는 처리와 브랜드 명칭 `brand` 입력을 구분하고, 새 `brandId` 추가·교체·삭제를 지원되는 수정으로 취급하지 않는다. 근거는 프로젝트 [브랜드 관리 FAQ SOURCE](../../marketplace-api-docs/coupang/faq/products/open-api-update-strengthening-brand-management-in-coupang-faq-collection.md)와 [현재 공식 FAQ](https://developers.coupang.com/ko/faq/open-api-update-strengthening-brand-management-in-coupang-faq-collection)다.

생성 문서에는 평면 SUCCESS와 중첩 성공 예시가 공존하고, SUCCESS에도 `details`·`errorItems`가 포함된 사례가 있다. 등록상품 ID를 확보한 결과와 옵션 보완이 필요한 결과를 구분한다. 응답 원문·상품·연락처·인증 정보를 로그나 테스트 자료에 복사하지 않는다. [생성 응답 규격](https://developers.coupang.com/ko/api/products/product-creation)

로컬 업로드 이미지의 관리자 인증 URL은 외부 판매 이미지로 사용할 수 없다. 외부 전송은 쿠팡 CDN 또는 쿠팡이 인증 없이 읽을 수 있는 HTTPS 경로를 사용한다. 공개 이미지 제공의 설정·보관 범위는 [공통 상품 편집 문서](common-product-editor.md#외부-판매-이미지-경로)에서 관리한다. [상품 이미지 규격](https://developers.coupang.com/ko/api/products/product-creation)

## 공통 페이지의 쿠팡 저장 구현 (2026-10-06)

현재 등록·수정은 [공통 판매 상품 페이지](common-product-editor.md)에서 입력을 임시 저장한 뒤 `마켓에 저장`으로 미리보기를 준비하고 `확인 후 전송`으로 실행한다. 기존 조회 모달과 쿠팡 개별 편집 페이지의 읽기 기능은 유지한다. 앞선 날짜의 읽기 전용 범위 기록과 현재 외부 쓰기 구현을 구분한다.

신규 상품은 CREATE, 기존 상품은 실제 달라진 구역에 한해 PRODUCT·DELIVERY·옵션별 PRICE·STOCK 단계를 준비한다. 수정 미리보기는 쿠팡에서 새로 읽은 서버 기준값을 사용하고 전송 직전에 변경 여부를 확인한다. 브라우저가 제출한 조회 JSON을 그대로 상품 수정 전문으로 사용하지 않는다. 기존 원문의 알 수 없는 중첩 항목과 외부 ID를 서버에서 보존하며, 확인되지 않은 설정·서비스 선택지·첨부 규격은 임의로 변환하지 않고 차단한다.

승인 옵션의 현재 판매가·수량은 별도 엔드포인트로 변경한다. 가격 제한을 우회하는 force 옵션은 false로 유지한다. 초기 상품 저장과 판매 승인 요청은 `쿠팡 저장 후 판매 승인 요청` 선택으로 구분한다. 이미지와 연락처·인증 원문은 실행 결과에 포함하지 않는다.

가져온 상품의 브랜드 ID 입력과 쿠팡별 값 선택은 잠그고 WING에서 수정해야 하는 사유를 표시한다. 기존 브랜드 ID는 카테고리 조회·임시 저장에서도 보존한다. 신규 상품의 브랜드 ID 입력은 유지한다.

서버 전송 준비에서도 기존 브랜드 ID의 추가·교체·삭제를 차단한다. 가져오기 원본 유무와 별개로 기존 외부 상품에 연결된 실행 매핑에 같은 제한을 적용한다. 신규 입력과 기존 값 유지·무변경을 포함한 쓰기 어댑터 16개 검증을 통과했으며 실제 계정 쓰기 성공 기록과 구분한다.

쓰기 요청도 기존 계정 단일 실행과 1초 시작 간격, HMAC·TLS·리다이렉트 거부, 요청·응답 크기와 완료 시간 제한을 사용한다. 실제 429는 Retry-After 정책에 따라 한 번만 재시도한다. 타임아웃·5xx·중단·잘못된 응답은 결과 미확인으로 남기며 새 CREATE 요청을 자동 반복하지 않는다. 응답의 접수와 재조회로 확인한 반영을 나누고, SUCCESS에 보완 정보가 있으면 이를 유지한다. 실패한 독립 단계만 명시적으로 재시도한다.

상품이 생성되면 서버에 계정별 외부 상품·옵션 연결을 기록한다. 신규 등록 직전에 판매자 상품코드 후보를 다시 읽어 실제 전송 기록의 기준값에 저장한다. 이 조회에 실패하면 등록 POST를 보내지 않는다. 응답을 잃어 등록상품 ID를 확보하지 못한 경우 1분 후 SKU 후보를 읽되, 실행 전 후보를 제외하고 내용이 일치하는 단일 후보가 있어도 이번 요청의 결과라는 근거가 없으므로 자동 연결하지 않는다. 동시에 등록되거나 검색 반영이 늦은 별도 상품일 수 있다. 이 경우 `UNKNOWN`과 다음 쓰기 잠금을 유지하며 쿠팡에서 등록 여부 확인을 안내한다. 등록 응답에서 ID를 확보한 경우에는 해당 ID로 반영·승인을 확인한다. 등록 응답의 상품 ID와 실제 옵션 ID 매핑은 구분하며 순서만으로 외부 옵션을 새로 추정하지 않는다.

WING 사용자 ID·카테고리와 필수 등록 설정이 필요하며 로컬 이미지를 전송하려면 별도의 공개 HTTPS 이미지 주소 설정이 필요하다. 이 단계에서는 비밀 파일을 직접 열거나 수정하지 않았고 공개 이미지 호스팅·운영 배포를 실행하지 않았다. 실제 계정의 상품 등록·수정 성공은 아직 확인하지 않았다. 모의 API·격리된 DB 검증을 실호출 성공으로 기록하지 않는다.

## 쿠팡 입력 화면 재배치 (2026-10-06)

쿠팡 개별 편집과 공통 상품의 쿠팡 탭을 상품 정보·옵션 설정·옵션 목록·이미지·설명·주요정보·검색어·검색필터·고시·배송·반품 순서로 정리했다. 품번(MPN), 모델번호, 판매자상품코드를 구분하며 옵션별 값과 승인 옵션의 수정 제한을 유지한다. WING 캡처는 배치 참고로 사용하고 API에 없는 입력 규칙은 추가하지 않았다. 공통 기준 화면의 배치 개편은 후속 단계다. 자세한 입력·미디어 범위는 [공통 편집 안내](common-product-editor.md#쿠팡-입력-배치-2026-10-06), 실행 결과는 [검증 기록](../testing.md#쿠팡-등록수정-입력-화면-재배치)을 따른다.

### Brand search and locked existing products (2026-10-07)

- Internal GET `/api/marketplaces/coupang/brands?brandName=...&page=1` calls the official POST `/v2/providers/seller_api/apis/api/v1/marketplace/brands/search`. It shares ADMIN checks, account concurrency, HMAC, pacing, response limits, cancellation and 429 retry handling. Search pages contain at most 10 results.
- New registration selects both brand name and brandId from search results. Manual ID input is removed. Draft saving and transmission preparation require a selection. Server verification stores the actor's searched ID/name pairs for one hour; after expiry or restart, search and select again before preparing a new registration. Saving an unchanged previously saved selection remains supported.
- Existing Coupang editing locks brand name/ID and hides brand search. The server rejects changing a connected brand. Common reference changes do not implicitly change the connected marketplace brand.
- Product read settings now include brandId so imported IDs are preserved. Search metadata includes isUIDRequired and allowedUIDTypes; extra brand-specific UID validation is outside this change.
- Category rules use the product detail displayCategoryCode, not categoryId. Standalone editing automatically reads metadata for that existing code, with the manual button and loading/completion labels hidden. Common Coupang editing also hides manual category lookup and preserves existing inputs. New registration uses the entered code.
- Sources: [brand search SOURCE](../../marketplace-api-docs/coupang/api/brands/brand-search.md), [creation SOURCE](../../marketplace-api-docs/coupang/api/products/product-creation.md), [brand FAQ SOURCE](../../marketplace-api-docs/coupang/faq/products/open-api-update-strengthening-brand-management-in-coupang-faq-collection.md), and the current official brand search document.
- Live read verification: minimal Spring runtime loaded existing runtime configuration; brand search first page returned HTTP 200, SUCCESS, 10 items, hasNext=true. No DB initialization, product writes or running-app restart. No raw brand response or credentials recorded.

### 단독 편집 상품주요정보 선택 방식 (2026-10-07)

상품주요정보를 상품 구성 → 인증정보 → 병행수입 → 구매 연령 → 인당 최대구매수량 → 판매기간 → 부가세 순서의 라디오 선택으로 표시한다. 제조사와 기타 등록 설정은 추가 등록 설정에서 유지하며 구비서류는 별도 구역·내비게이션으로 분리했다. 옵션 공통 선택은 명시적인 입력 시 모든 옵션에 적용하고 조회만으로 서로 다른 값을 덮어쓰지 않는다.

인증 선택은 카테고리 메타정보에서 허용하는 타입을 따른다. 필수 인증이 있으면 상세페이지 표기와 인증대상 아님을 비활성화한다. 실제 인증 유형은 체크박스로 고르고 인증번호를 입력하며, 다른 방식으로 전환했다 돌아오면 입력했던 인증 원문·첨부 참조를 복원한다. 알 수 없는 기존 인증도 렌더링만으로 삭제하지 않는다.

구매 제한 없음은 maximumBuyForPerson=0, maximumBuyForPersonPeriod=1로 명시적으로 적용한다. 판매기간 없음도 API 필수 날짜를 생략하지 않고 시작일을 유지(비어 있으면 현재 시각), 종료일을 2099-12-31T23:59:59로 설정한다. 기존 상품 구성은 읽기 전용이며 신규 혼합 구성은 기존 지원 범위를 유지해 비활성화한다. 혼합 구성의 옵션·속성 제약을 이번 UI 변경으로 우회하지 않는다.

근거: [상품 생성 SOURCE](../../marketplace-api-docs/coupang/api/products/product-creation.md)의 bundleType, maximumBuyForPerson, maximumBuyForPersonPeriod, saleStartedAt/saleEndedAt 및 [카테고리 메타정보 SOURCE](../../marketplace-api-docs/coupang/api/categories/category-metadata-query.md)의 certificationType·required·dataType. 실제 계정 쓰기 검증은 수행하지 않았다.

### 인증 상세 개별 추가 (2026-10-07)

단독 쿠팡 편집의 인증 상세 체크박스 목록을 인증 유형 선택·인증번호 입력·추가 방식으로 변경했다. 추가된 인증은 각각 번호를 편집하거나 삭제할 수 있고 카테고리 필수 인증은 삭제하지 못한다. 동일 유형의 중복 추가는 기존 행에서 수정하도록 안내하며 기존 첨부 참조는 보존한다. 유형을 전환했다 돌아오면 인증 행과 번호가 복원된다.

공개 API 색인에서 WING의 인증번호 진위 확인 버튼에 해당하는 별도 경로는 확인되지 않았다. 상품 생성의 items[].certifications[]에 certificationType·certificationCode·certificationAttachments를 넣고 상품 조회 응답에서 읽는 기능과 번호 검증을 구분한다. 앱은 번호 입력·추가를 인증 완료로 표현하지 않는다. 출처는 최신 프로젝트 SOURCE와 공식 개발자 센터의 상품 생성·API 색인이며 실제 외부 인증 검증 요청은 수행하지 않았다.

### 상세설명 미리보기 세로 이미지 배치 (2026-10-07)

편집 미리보기 iframe 내부에 최대 폭 860px과 이미지 block 표시·가운데 정렬을 적용했다. 연속 img 태그도 한 장씩 세로 배치되며 화면보다 큰 이미지는 축소된다. 상품의 설명 HTML 원문은 변경하지 않고 기존 sandbox·CSP 격리와 쿠팡 이미지 HTTPS 표시 보정은 유지한다. 공통 상품 편집에서 재사용하는 동일 미리보기에도 적용된다.

### 단독 편집 정보 구역 밀도·서류·검색어·고시 (2026-10-07)

제조사를 상품주요정보 맨 위로 이동하고 주요정보 행·구비서류·검색어·검색필터·고시의 여백을 줄였다. 서류 유형과 추가 버튼은 한 행, 추가 서류는 다음 목록에 주소 입력·보기·삭제로 표시한다. 유효한 HTTPS 주소가 있는 서류만 새 창 보기 가능하며 필수 서류 검증은 유지한다.

검색어는 SOURCE의 최대 20개·각 20자 기준으로 실시간 개수·초과 안내와 항목 삭제를 제공한다. 초과 입력을 임의로 자르지 않고 검증에서 차단한다. 옵션별 기존 검색어 차이는 사용자의 공통 수정 전까지 유지한다.

고시의 전체 상품 상세페이지 참조 체크 시 모든 옵션의 고시 내용을 '상품 상세페이지 참조'로 입력한다. 조회 시 고시가 하나 이상이고 모든 옵션의 모든 고시가 해당 값이면 자동 체크한다. 개별 수정으로 체크 상태를 갱신하고 체크 해제 시 이번 입력 이전 값을 복원한다. 이미 참조 값으로 조회된 항목은 체크 해제 후 빈 입력으로 바꿔 직접 작성할 수 있다. 조회만으로 고시를 변경하지 않는다.

미리보기의 최종 크기는 가로 800px·데스크톱 높이 1200px, 모바일 높이 900px이며 좁은 화면에서는 가로를 줄인다.

### 선택한 고시 유형만 표시 (2026-10-07)

고시 유형 전환 시 이전 유형의 입력을 화면에 누적 표시하던 문제를 수정했다. 현재 선택한 유형의 고시만 렌더링하며 기존 항목의 인덱스·유형·필드명 연결을 유지한다. 다른 유형의 입력은 메모리에 보존하므로 다시 선택하면 복원된다. 전체 상품 상세페이지 참조 적용·자동 체크도 옵션별 현재 선택한 유형에 한정하며 비활성 유형의 값은 변경하지 않는다.

### 2026-10-07 배송·반품/교환 주소록 화면

쿠팡 단독 편집 화면의 배송·반품/교환은 주소 요약과 판매자 주소록 선택, 세로 행 입력으로 표시한다. 출고지 코드는 수정 화면에서 자동 조회하며, 목록은 주소록 버튼을 누를 때 페이지별로 조회한다. `GET /api/marketplaces/coupang/shipping-places/outbound/{code}`는 `placeCodes` 단건 필터를, `GET /api/marketplaces/coupang/shipping-places/{outbound|return}?page=1`은 `pageNum`·`pageSize=50`을 사용한다. 출고지는 공식 물류 v2 경로, 반품지는 `/v2/providers/openapi/apis/api/v5/vendors/{vendorId}/returnShippingCenters`를 사용한다. 반품지 문서의 예시 앞부분 `/v5/`는 경로 표와 충돌하므로 경로 표의 `/v2/`를 적용했다. 실계정 호출 확인과 문서 검증은 구분한다.

주소록 조회는 기존 관리자 권한, 서버 계정 설정, 호출 간격·서명·취소·GET 재시도 정책을 공유한다. 출고지 응답은 최상위 `content`/`pagination`, 반품지는 `code=200` 아래 `data.content`/`data.pagination`을 읽고 반품지의 판매자 식별자를 검사한다. `usable=false`인 주소는 선택할 수 없다. 여러 주소 유형은 개별 선택 항목으로 보여준다. 조회·페이지 이동·취소만으로 입력값을 변경하지 않으며, 반품지 선택 시 코드·이름·연락처·우편번호·주소·상세주소를 함께 반영한다.

출고 소요일은 `items[].outboundShippingTimeDay`에 매핑한다. 값이 다르면 기본적으로 옵션별 입력을 표시한다. 기본 입력으로 전환만 했을 때는 값을 유지하고, 실제 숫자를 입력할 때 모든 옵션에 적용한다. 배송비 유형 선택 시 무료배송은 기본배송비·무료배송 기준을 0으로, 유료·착불은 무료배송 기준을 0으로 설정한다. 기존 조회값은 렌더링으로 덮어쓰지 않는다. 왕복 반품비는 무료배송의 경우 초도배송비+반품배송비, 그 외는 기본배송비+반품배송비로 표시한다.

Wing의 당일출고 체크박스는 일반 출고 소요일과 별도 계약이다. 현재 편집 모델에 없는 `sameDayShipping`을 소요일 0으로 추정 매핑하지 않는다. 상품 생성 문서에는 당일과 다음날이 모두 1로 기재된 불일치가 있어 당일출고 설정은 이번 화면에서 제공하지 않는다.

### 단독 수정 화면 실제 저장 연결 (2026-10-07)

하단 저장은 입력 검사 → 변경값·예정 요청 확인 → 실행 접수 → 단계별 결과 표시로 진행한다. 변경 없음은 전송하지 않는다. 조회 시 `/products/{id}/edit-observation`에서 사용자·계정·상품에 묶인 10분 관찰 토큰을 발급하며 조회만으로 공통 초안을 생성하지 않는다. `/products/{id}/save-preparation`은 서버 관찰값과 허용된 변경 항목만 사용해 내부 초안 연결·편집 세션·전송 미리보기를 준비한다. 기존 공통 상품 입력은 덮어쓰지 않는다. 옵션은 sellerProductItemId로 연결하며 준비와 실행 직전 최신값을 다시 확인한다.

요청 순서는 배송·반품 partial → 정상가 ORIGINAL_PRICE → 판매가 PRICE → 재고 STOCK → 상품 정보 PRODUCT이다. 승인된 옵션의 가격·수량은 전용 요청, 나머지는 최신 조회 전문에 선택 변경만 반영한 상품 수정 PUT을 사용한다. 상품 정보 요청만 requested=true로 승인까지 요청하며 접수와 승인·반영 완료를 구분한다. 브랜드·카테고리·식별자 및 미선택 필드를 보존한다. 공통 편집은 모든 해당 옵션에 적용하고 이미지·설명은 선택 옵션만 변경하며 고시는 활성 유형만 전송한다.

실행은 기존 계정별 잠금·최소 1초 호출 간격·429 대기 정책을 공유한다. 명확한 실패 이후에는 다음 쓰기를 중단한다. 재시도는 최신값을 확인해 실패·미실행 단계만 실행하고 성공 단계는 반복하지 않는다. 충돌·계정/권한 변경은 새 미리보기가 필요하며 기존 미실행 단계를 종료한다. 응답 유실은 결과 확인 필요로 남기고 재조회 전에 동일 쓰기를 반복하지 않는다. 실행 ID는 브라우저에, 실행·요청·결과 기록은 서버에 남으며 재진입 시 기존 연결의 실행 이력도 조회한다.

파일은 기존 자산 API에 업로드하고 서버에서 전송용 공개 HTTPS 주소를 발급한다. 업로드 중·실패 파일은 저장 확인을 막고 다른 입력과 파일을 유지하며 재시도할 수 있다. blob 주소는 전송하지 않는다. 공통 초안에 저장하지 않은 업로드도 작성자 자신의 전송 준비에서만 공개할 수 있고 실행 접수 시 자산을 고정한다. 공개 origin 설정과 외부 도달 가능성은 운영 환경에서 별도로 확인해야 한다.

검증은 가짜 쿠팡 응답과 격리된 DB·PC/모바일 브라우저로 수행한다. 실제 판매 상품 변경, 신규 등록 화면, 주문 수집, 당일출고 설정은 이번 변경 대상이 아니다. 근거는 상품 수정·부분 수정·정상가 변경·판매가 변경·재고 변경 SOURCE이며 테스트 범위는 [testing](../testing.md)을 따른다.

### 저장 거절 사유와 옵션명 표시 보완 (2026-10-07)

기존 쓰기 오류는 HTTP 400 응답 본문과 HTTP 200의 ERROR 메시지를 모두 일반 REJECTED로 치환했다. 이 때문에 과거 실행 기록에서 상세 사유를 복원할 수 없다. 이제 판매가 SOURCE에 있는 가격 변경 비율, 자동생성 옵션, 삭제 옵션, 10원 단위, 유효하지 않은 옵션 ID, 자동 가격 조정 최저가/필드 쌍 오류를 안전한 고정 코드·대처 문구로 분류한다. 분류되지 않은 거절에는 HTTP 상태를 기록한다. 외부 응답의 연락처·자격 증명·판매자 데이터를 그대로 화면/로그에 복사하지 않으며, 5xx·연결 유실의 UNKNOWN 정책과 forceSalePriceUpdate=false는 유지한다.

미리보기에는 관찰값의 내부 옵션 ID→옵션명 대응을 추가하고 판매가·재고 경로를 한국어로 표시한다. 서버 실행 결과도 관찰된 옵션명을 표시한다. 실패 또는 결과 확인 대기 뒤의 QUEUED 단계는 미실행으로 안내한다. 과거 실패를 재전송해 진단하지 않고 다음 사용자가 확인한 실행부터 새 사유 분류를 적용한다.

### 빈 PUT의 HTTP 411 보완 (2026-10-07)

실제 저장 화면의 HTTP 411은 요청 길이 헤더 요구를 나타낸다(RFC 9110 §15.5.12). 로컬 JDK 21 SOURCE의 HTTP/2 Stream.headerFrame은 길이가 0이면 Content-Length를 추가하지 않고, Http1Request는 0을 추가한다. 기존 transport는 기본 HTTP/2 선호와 noBody를 사용했다. 실패 당시 실제 협상 프로토콜/헤더는 캡처하지 않았으므로 HTTP/2가 원인이었다는 판단은 이 코드 경로에 근거한 추론이다.

본문 없는 PUT/POST만 HTTP/1.1로 보내 JDK가 Content-Length: 0을 설정하게 했다. 정상가·판매가·재고의 본문·서명 경로·쿼리는 변경하지 않고 본문이 있는 상품 정보 요청과 GET은 기존 동작을 유지한다. HTTP 411 이력에는 길이 헤더 문제와 재시도 대처 문구를 표시하며 기존 이력을 읽을 때도 적용한다. 쓰기는 자동 재전송하지 않는다.

### 쿠팡 전용 신규 등록 연결 (2026-10-07)

쿠팡 목록의 상품 등록 버튼은 전용 `/marketplaces/coupang/products/new`로 연결한다. 기존 수정 화면 컴포넌트를 사용하며 신규 옵션 UUID·구매 속성 기반 자동 옵션명·옵션별 미디어와 공통 상품정보를 제공한다. 구매 속성이 없는 단일 상품은 `단일 상품`으로 옵션명을 만든다. 신규 배송·출고 소요일도 옵션 공통 입력이다. 공통 상품 편집기 및 기존 COMMON 초안 경로는 유지한다.

전용 초안 서비스는 화면 입력을 기존 내부 Document로 변환한다. 임시 저장은 내부 보관이고 저장 확인 이후에만 기존 MarketplaceSubmissions의 CREATE POST 한 단계로 전송한다. 승인 요청은 기본 선택이며 확인 창에서 저장만으로 변경할 수 있다. 상품 ID 확보와 승인완료는 구분하고, 응답 유실·접수 대기 중에는 신규 등록을 반복하지 않는다. 상세 범위는 [전용 신규 등록 안내](common-product-editor.md#쿠팡-전용-신규-등록-화면), 모의 응답 검증은 [검증 기록](../testing.md#쿠팡-전용-신규-상품-등록-2026-10-07)을 따른다. 실제 판매 상품 신규 등록은 아직 실행하지 않았다.
