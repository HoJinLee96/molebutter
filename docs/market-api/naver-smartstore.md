# 네이버 스마트스토어 — 상품 조회·등록·수정

> 문서 유형: 현재 상품 구현 안내와 외부 API 조사 기록. 문서 대조·모의 검증과 실제 계정 호출을 구분한다. 현재 코드 경계는 [모듈 구조](../modular-architecture.md)를 따른다.

## 전용 상품 화면 (2026-10-08)

`/marketplaces`에서 스마트스토어 목록을 선택하고 `/marketplaces/naver/products/new`, `/marketplaces/naver/products/{originProductNo}/edit`로 등록·수정한다. 상품명·브랜드·제조사·카테고리, 판매가·옵션 추가금·재고, 상품 공통 이미지·상세설명, 인증·검색 속성·고시, 배송·반품과 채널 전시 설정을 입력한다. 브랜드 코드는 선택 사항이고 쿠팡 브랜드 코드를 사용하지 않는다. 1~3축 일반 조합형 옵션은 내부 UUID와 외부 옵션 ID를 구분하며 옵션 가격은 기본 판매가에 더하는 추가금이다.

임시 저장은 불완전한 입력도 서버 초안에 보관한다. 저장은 필수 입력을 확인하고 변경값과 예정 요청을 표시한다. 확인 후 기존 실행 기록 엔진이 계정별 잠금·순차 전송·결과 확인을 처리한다. 신규 상품은 `POST /v2/products` 한 번으로 등록하며 쿠팡 `requested` 승인 필드를 사용하지 않는다. 신규 원상품 상태는 `SALE`, 비노출은 채널 전시 상태 `SUSPENSION`으로 설정한다. 수정은 준비·실행 시 최신 원상품을 조회해 선택 변경 충돌을 검사하고 원문 유지값을 병합한다. 상세설명을 수정하지 않았으면 `detailContent`를 재전송하지 않아 스마트에디터 형식 전환을 피한다.

대표 1개와 추가 9개는 상품 공통이다. 기존 서버 자산 업로드와 드래그 정렬을 사용하고 전송할 파일을 네이버 `POST /v1/product-images/upload`에 올려 반환 URL로 변환한다. 로컬 자산은 공개 이미지 서버 설정 없이 직접 전송한다. 원격 이미지 입력은 운영자가 허용한 HTTPS 호스트만 서버에서 읽고 리디렉션·사설 주소를 거절한다. 허용 호스트는 아래 환경 변수로 지정한다.

등록·수정 초안과 실행은 원상품 번호·스마트스토어 채널상품 번호를 별도로 보관한다. 옵션 ID와 원본 JSON을 브라우저 입력으로 받지 않는다. 일반 CREATE에는 원격 멱등 키가 없어 응답 유실을 실패로 단정하거나 새 POST를 반복하지 않는다. 결과 확인 필요 상태에서 동일 신규 등록을 차단한다. 서버에서 상품 ID를 확인한 후 전시·승인 상태와 등록 성공을 구분한다.

현재 신규 화면은 일반 무옵션·조합형 상품을 등록한다. 기존 단독형·직접형·표준형·4축 지점형 옵션은 구조와 재고를 잠그고 서버 원문을 보존한다. 이 옵션의 재고는 스마트스토어센터에서 수정하며 일반 판매가 입력은 제공한다. 기존 상품의 옵션 유형 변경은 제한하고 일반 조합형의 행 값·추가금·재고를 편집한다. 그룹상품은 별도 그룹상품 API가 필요하므로 조회 후 이 화면의 저장을 차단한다. 쿠팡식 구비서류 첨부·인증번호 진위 확인·정상가 전용 요청은 네이버 상품 계약에 억지로 매핑하지 않는다. 인증 유형은 네이버 카테고리 메타정보를 사용하고 선택한 고시 유형의 하위 객체만 전송한다. 현재 지원하지 않는 고시 유형은 선택과 저장을 제한하고 조회 원문을 유지한다. 품절·재고 0은 문서의 수정 요청 규칙으로 품절을 유지하며 승인 대기·종료 등 상태를 임의로 판매 중으로 바꾸지 않는다. 해당 비편집 상태는 안내 후 저장을 제한한다.

전용 등록 API는 `/api/marketplaces/naver/product-registrations/drafts`의 POST, GET·PUT `/{id}`, POST `/{id}/prepare`다. 수정 API는 GET `/api/marketplaces/naver/products/{originProductNo}/observation`, POST `/{originProductNo}/prepare`이며 서버 관찰 토큰과 편집 입력을 받는다. 초안 목록은 `NAVER_REGISTRATION`을 전용 화면으로 연결한다. 기존 `COMMON`·쿠팡 초안과 실행 JSON의 호환 생성자는 유지한다. 다중 마켓 쓰기는 한 번에 한 마켓을 명시적으로 확인한다.

### 연결 설정

선택적 `application-secret/maketplace.properties`의 `naver_smart_store_*` 키를 실행 시 읽는다. 파일명은 현재 통합 파일의 `maketplace` 표기를 사용한다. 기존 `NAVER_COMMERCE_*` 환경 변수가 있으면 파일 키보다 우선하며, 네이버 공개 검색·매입처 로그인 설정과 별개다. 비밀 파일을 구현·검증 과정에서 열거나 수정하지 않는다.

| 통합 파일 키 | 우선하는 환경 변수 | Spring 프로퍼티 | 용도 |
|---|---|---|---|
| `naver_smart_store_client_id` | `NAVER_COMMERCE_CLIENT_ID` | `marketplace.naver.client-id` | 커머스 앱 ID |
| `naver_smart_store_client_secret` | `NAVER_COMMERCE_CLIENT_SECRET` | `marketplace.naver.client-secret` | 제공된 bcrypt 시크릿 |
| — | `NAVER_COMMERCE_TYPE` | `marketplace.naver.type` | `SELF` 기본 또는 `SELLER` |
| `naver_smart_store_account_id` | `NAVER_COMMERCE_ACCOUNT_ID` | `marketplace.naver.account-id` | `SELLER`의 판매자 계정 |
| — | `NAVER_COMMERCE_IMAGE_SOURCE_HOSTS` | `marketplace.naver.image-source-hosts` | 외부 이미지 exact 호스트의 쉼표 목록 |

계정 ID를 입력해도 인증 유형을 자동으로 `SELLER`로 바꾸지 않는다. 자기 스토어는 기존 기본값 `SELF`를 유지하며 위임 판매자 조회는 별도로 `NAVER_COMMERCE_TYPE=SELLER`를 지정한다.

토큰은 `expires_in`에 맞춰 메모리에 보관한다. 모든 조회·메타·이미지·쓰기 호출에 최소 1초 간격을 적용한다. 이는 앱의 보수적인 정책이며 네이버가 보장한 고정 호출 제한이 아니다. 명확한 `401 GW.AUTHN`에서 토큰을 한 번 갱신하고 `429`는 제한된 한 번만 대기한다. `GW.QUOTA_LIMIT`과 5xx·연결 단절은 쓰기를 자동 반복하지 않는다. 인증값·서명·Authorization은 초안·실행 전문·브라우저 응답에 넣지 않는다.

규격 기준은 프로젝트 [README](../../marketplace-api-docs/README.md)와 네이버 SOURCE 2.90.0(수집 2026-10-03)이며, 공식 current 2.90.1(2026-10-07)의 주요 상품·인증 규격과 대조했다. 실제 계정의 상품 등록·수정·이미지 업로드는 별도 실행 전까지 수행하지 않는다. 검증 결과는 [testing](../testing.md)에 실행 후 기록한다.

## 최초 읽기 조사 기록

확인일: 2026-10-03. 웹 문서 현재 버전 표기는 **2.90.0 / 2026-09-29**다. URL의 `current`와 공식 Markdown은 앞으로 변경될 수 있다. 실제 구현 시 버전과 원문을 다시 확인한다. [상품 목록 웹 규격](https://apicenter.commerce.naver.com/docs/commerce-api/current/search-product)

## 가입·인증

커머스API센터에 가입하고 앱을 등록해 상품 API 그룹 권한을 설정한다. 앱 ID·시크릿과 실제 서버의 허용 IP를 준비한다. 이번 자기 스토어 조회는 `SELF`를 먼저 사용한다. 다른 판매자의 위임 리소스를 다루는 `SELLER`는 `account_id`와 해당 권한이 필요하다. [소개](https://apicenter.commerce.naver.com/docs/introduction), [제약](https://apicenter.commerce.naver.com/docs/restriction), [토큰 규격](https://apicenter.commerce.naver.com/docs/commerce-api/current/exchange-sellers-auth)

```text
password = client_id + "_" + timestampMillis
hashed   = BCrypt.hashpw(password, client_secret)
client_secret_sign = Base64(UTF-8 bytes of hashed)
```

시크릿은 bcrypt의 salt 자리에 사용한다. 임의 salt를 새로 만들지 않는다. 서명을 생성한 timestamp와 제출한 timestamp가 같아야 한다. [공식 인증 가이드](https://apicenter.commerce.naver.com/docs/auth)

토큰 요청의 계약:

```text
POST https://api.commerce.naver.com/external/v1/oauth2/token
Content-Type: application/x-www-form-urlencoded

client_id=<app-id>
timestamp=<Unix milliseconds>
grant_type=client_credentials
client_secret_sign=<signature>
type=SELF
```

이는 필드 구성 예시이며 실제 본문은 URL 인코딩한 폼으로 전송한다. 토큰 발급에는 기존 Bearer 토큰이 필요하지 않다. 응답의 `access_token`, `token_type`, `expires_in`을 읽는다. timestamp 유효 시간은 5분, 토큰은 문서상 10,800초다. 같은 리소스는 잔여 30분 이상이면 기존 토큰, 미만이면 새 토큰을 반환할 수 있다. 실제 캐시는 `expires_in` 기준으로 관리한다. [토큰 상세](https://apicenter.commerce.naver.com/docs/commerce-api/current/exchange-sellers-auth)

주의: [Markdown 토큰 문서](https://apicenter.commerce.naver.com/llms/post-v1-oauth2-token.md)의 일반화된 curl에는 JSON과 Bearer가 들어 있다. 토큰 발급 예시는 그대로 복사하지 않고 위 웹 상세 규격을 따른다.

## 첫 목록 요청

```http
POST /external/v1/products/search HTTP/1.1
Host: api.commerce.naver.com
Authorization: Bearer <access-token>
Content-Type: application/json

{"page":1,"size":10,"orderType":"NO"}
```

`page`는 1부터, `size`는 최대 500이다. 첫 호출에는 날짜·상품번호·상태 필터를 생략해 접근 자체를 확인한다. 이후 판매 중 상품만 볼 때 `productStatusTypes: ["SALE"]`를 사용한다. 검색 키는 원상품·채널상품·그룹상품 번호 또는 판매자 관리 코드이며, 마켓 전체의 소비자 상품 검색 API가 아니다. [상품 목록 상세](https://apicenter.commerce.naver.com/docs/commerce-api/current/search-product), [Markdown 필드 설명](https://apicenter.commerce.naver.com/llms/post-v1-products-search.md)

응답은 `contents` 배열과 `page`, `size`, `totalElements`, `totalPages`, `first`, `last`다. 각 항목에 `originProductNo`, 선택적인 `groupProductNo`, `channelProducts`가 있다. 후속 목록 수집은 마지막 페이지 정보를 사용하고, 같은 번호를 종류별로 구분해 보존한다. 빈 목록은 성공할 수 있지만, 실제 판매 상품 연동 검증은 등록된 상품 1건과 대조한다. [목록 규격](https://apicenter.commerce.naver.com/llms/post-v1-products-search.md)

## 상세·상품 단위

| 조회 대상 | 경로 | 용도 |
|---|---|---|
| 원상품 | `GET /external/v2/products/origin-products/{originProductNo}` | 공통 상품 속성·가격·옵션 확인 |
| 채널상품 | `GET /external/v2/products/channel-products/{channelProductNo}` | 스마트스토어 등 전시 채널 속성 확인 |
| 그룹상품 | `GET /external/v2/standard-group-products/{groupProductNo}` | 여러 원상품을 묶은 그룹 확인 |

근거: [원상품 조회](https://apicenter.commerce.naver.com/docs/commerce-api/current/read-origin-product-product), [채널상품 조회](https://apicenter.commerce.naver.com/docs/commerce-api/current/read-channel-product-1-product), [그룹상품 가이드](https://github.com/commerce-api-naver/commerce-api/wiki/%EC%BB%A4%EB%A8%B8%EC%8A%A4API-%EA%B7%B8%EB%A3%B9%EC%83%81%ED%92%88-%EC%97%B0%EB%8F%99-%EA%B0%80%EC%9D%B4%EB%93%9C).

일반상품은 원상품 → 채널상품, 그룹상품은 그룹 → 원상품 → 채널상품 구조다. 원상품 번호와 채널상품 번호가 숫자로 같더라도 동일 식별자로 보지 않는다. 그룹상품의 판매옵션 조합은 원상품 단위로 구성될 수 있으므로 모든 옵션을 `optionCombinations`만으로 해석하지 않는다. [스마트스토어 상품 가이드](https://apicenter.commerce.naver.com/llms/wiki-스마트스토어-상품-가이드.md), [그룹상품 연동 가이드](https://apicenter.commerce.naver.com/llms/wiki-커머스API-그룹상품-연동-가이드.md)

일반 옵션은 원상품의 `detailAttribute.optionInfo`를 확인한다. 조합형·표준형의 `id`, 옵션값, `usable`, `stockQuantity`를 함께 읽으며 `useStockManagement`를 확인한다. 상품 관리 코드 `sellerManagementCode`와 옵션 관리 코드 `sellerManagerCode`는 이름부터 다르다. 수량 미관리·사용 불가 옵션의 숫자를 실제 보유 재고로 해석하지 않는다. [원상품 구조체](https://apicenter.commerce.naver.com/docs/commerce-api/current/schemas/%EC%9B%90%EC%83%81%ED%92%88-%EC%A0%95%EB%B3%B4-%EA%B5%AC%EC%A1%B0%EC%B2%B4)

## 오류·운영

| 응답 | 확인·대응 |
|---|---|
| 401 + `GW.AUTHN` | 토큰 재발급 후 읽기 요청을 제한된 횟수로 재시도 |
| 403 + `GW.IP_NOT_ALLOWED` | 호출 서버의 실제 외부 IP 확인 |
| 기타 403 | 상품 API 그룹·계정 권한 확인 |
| 429 | `GW.RATE_LIMIT` / `GW.QUOTA_LIMIT` 구분, 해당 제한 기준으로 대기 |
| 400 / 404 | 형식·번호 종류·계정·경로 확인, 자동 반복하지 않음 |
| 308 | 웹 상세에 정의된 리디렉션. 목적지·호스트를 확인하고 자격 증명을 임의 외부 호스트로 전달하지 않음 |
| 5xx / 시간 초과 | 읽기 요청만 제한된 백오프 재시도 |

근거: [인증](https://apicenter.commerce.naver.com/docs/auth), [문제 해결](https://apicenter.commerce.naver.com/docs/trouble-shooting), [상품 목록 응답](https://apicenter.commerce.naver.com/docs/commerce-api/current/search-product).

TLS 1.2 이상이며 호출량은 API·앱별로 유동적이다. `GNCP-GW-RateLimit-*`, `GNCP-GW-Quota-*` 헤더와 `GNCP-GW-Trace-ID`를 진단에 사용한다. 모든 API가 같은 초당 제한이라고 가정하지 않는다. [제약 사항](https://apicenter.commerce.naver.com/docs/restriction), [오류 추적](https://apicenter.commerce.naver.com/docs/trouble-shooting)

## 실제 호출 때 남길 확인

- SELF 앱과 상품 그룹·허용 IP가 준비됐는지.
- 목록 200과 정상 `contents`를 받고 스마트스토어 채널을 구분할 수 있는지.
- 목록의 번호로 상세 1건을 읽고 판매자센터 상품과 일치하는지.
- 일반·그룹·옵션 상품 중 계정이 실제 사용하는 구조와 가격·수량 의미가 무엇인지.
- 빈 응답·미입력 필드·새 enum을 오류나 0수량으로 바꾸지 않는지.

주문·정산·문의·N배송·커머스솔루션·판매자 정보 API도 제공되지만, 첫 상품 조회에서는 해당 권한과 동기화를 추가하지 않는다. 전체 도메인 목록은 [공식 인덱스](https://apicenter.commerce.naver.com/docs/commerce-api/current)에서 확인한다.

2026-10-09 모듈 적용: 네이버 인증·HTTP·카탈로그·상품 전문 구성·이미지 업로드는 `molebutter-marketplace-naver`, 초안·관찰 토큰·실행 기록은 공통 marketplace, 로컬 자산은 media의 공개 API를 사용한다. 외부 API 경로·입력·상태 해석·자격 증명 설정은 변경하지 않는다.
