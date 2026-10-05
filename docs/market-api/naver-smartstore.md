# 네이버 스마트스토어 — 판매 상품 조회

> 문서 유형: 외부 API 조사와 미구현 연동 제안. 외부 사양은 조사 당시 기준이며 실제 계정 호출·연동 완료를 뜻하지 않는다. 구현 전 최신 공식 문서와 계정 권한을 다시 확인한다. 현재 코드 경계는 [모듈 구조](../modular-architecture.md)를 따른다.

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
