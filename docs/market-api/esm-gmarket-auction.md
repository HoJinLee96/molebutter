# G마켓·옥션 — ESM Trading API 판매 상품 조회

확인일: 2026-10-03. 공통 인증·상품 2.0 API를 사용하되 사이트별 판매자와 결과를 분리한다.

## 사용 신청과 권한

G마켓·옥션 판매자 회원과 ESM+ 마스터 ID를 준비하고 API 사용을 신청한다. 가이드의 공식 접수 주소는 `etapihelp@gmail.com`이다. 신청에는 개발 API 범위·마스터 ID·서비스 URL·최근 3개월 매출·개발 기간을 포함한다. 신청 승인과 API별 권한은 별개이며 거절될 수 있다. 허용 서버 IP 등록도 필요하다. 개발·테스트 후 운영 일정 협의 안내가 있으므로 운영 키·실사용 승인 여부를 먼저 확인한다. 메일은 이 조사에서 발송하지 않았다. [ESM 사용 가이드](https://etapi.gmarket.com/pages/API-%EA%B0%80%EC%9D%B4%EB%93%9C)

## JWT 인증

```json
{"alg":"HS256","typ":"JWT","kid":"<ESM-master-id>"}
```

```json
{"iss":"<issuer>","sub":"sell","aud":"sa.esmplus.com","iat":1791000000,"ssi":"G:<gmarket-seller-id>"}
```

```text
JWT = base64url(header) + "." + base64url(payload) + "." +
      base64url(HMAC-SHA256(secretKey, encodedHeader + "." + encodedPayload))
Authorization: Bearer <JWT>
```

`ssi`는 G마켓 `G:판매자ID`, 옥션 `A:판매자ID`이고 사이트당 하나씩 쉼표로 결합할 수 있다. 위 iat는 설명용 값이며 실제 요청 시 현재 Unix 초로 만든다. `iss`는 실제 발급·등록 조건을 확인하고 샘플 발행자를 복사하지 않는다. `iat`는 가이드상 선택이며 exp·토큰 유효 기간은 이 문서에서 확인되지 않았다. 임의로 필수 클레임이나 만료 시간을 만들지 않는다. [인증 가이드](https://etapi.gmarket.com/pages/API-%EA%B0%80%EC%9D%B4%EB%93%9C)

문서는 public/private key라고 표현하지만 알고리즘과 예시는 공유 시크릿 기반 **HS256**이다. RSA 키 쌍을 생성하는 규격으로 해석하지 않는다. 상품 호출 호스트는 `sa2.esmplus.com`이어도 `aud`는 가이드의 `sa.esmplus.com`이다. Secret Key의 실제 발급 형식·issuer·시간 허용 범위는 승인 안내와 첫 응답으로 확인한다. 가정용 문자열 예시를 실제 JWT로 사용하지 않는다. [가이드](https://etapi.gmarket.com/pages/API-%EA%B0%80%EC%9D%B4%EB%93%9C)

## 사이트별 첫 목록

```text
POST https://sa2.esmplus.com/item/v1/goods/search
Authorization: Bearer <JWT>
Content-Type: application/json
```

G마켓은 G 판매자 JWT와 다음 본문으로 시작한다.

```json
{"query":{"siteId":[2],"siteSellerId":["<gmarket-seller-id>"]},"pageIndex":1,"pageSize":10}
```

옥션은 A 판매자 JWT와 다음 본문으로 별도 호출한다.

```json
{"query":{"siteId":[1],"siteSellerId":["<auction-seller-id>"]},"pageIndex":1,"pageSize":10}
```

필터는 `query` 아래, `pageIndex`·`pageSize`는 최상위다. 최대 페이지 크기는 500이며 실제 조회 샘플은 1페이지부터 시작한다. 키워드·판매 상태·관리 코드·등록일·수정일 검색이 가능하다. 첫 연결에서는 조건을 늘리지 않는다. [상품 목록](https://etapi.gmarket.com/160)

정상 응답은 `totalItems`, `pageIndex`, `pageSize`, `items[]`다. 각 항목에 `goodsNo`, `siteGoodsNo.gmkt/iac`, `siteSellerId.gmkt/iac`, 상품명·이미지·관리 코드·브랜드·사이트별 가격·재고·판매 상태가 있다. 목록 성공에 공통 `resultCode=0`이 반드시 있다고 가정하지 않는다. 실패 예시는 `resultCode`·`message`, 인증 실패는 `status.status_code` 구조다. HTTP 200이라도 오류 본문이면 실패로 처리한다. [목록 응답](https://etapi.gmarket.com/160), [인증 오류](https://etapi.gmarket.com/pages/API-%EA%B0%80%EC%9D%B4%EB%93%9C)

같은 마스터 상품에 두 사이트 번호가 함께 있을 수 있다. 응답 항목을 두 사이트 판매글로 해석하되, 요청한 사이트와 판매자에 속한 값만 해당 연결 결과에 포함한다.

## 상품 번호·상세·옵션

| 값·API | 구분 |
|---|---|
| `goodsNo` | ESM 2.0 마스터 상품 번호 |
| `siteGoodsNo.gmkt` | G마켓 사이트 상품 번호 |
| `siteGoodsNo.iac` | 옥션 사이트 상품 번호. 문자 포함 가능 |
| `managedCode` | 판매자 관리 코드 |
| `GET /item/v1/goods/{goodsNo}` | 마스터 상품 상세 |
| `GET /item/v1/goods/{goodsNo}/status` | 마스터 기준 사이트 번호 확인 |
| `GET /item/v1/site-goods/{siteGoodsNo}/goods-no` | 사이트 번호 → 마스터 번호 확인 |
| `GET /item/v1/goods/{goodsNo}/recommended-options` | 옵션 구조·옵션별 정보 조회 |

근거: [상품 2.0 상세](https://etapi.gmarket.com/20), [번호 변환](https://etapi.gmarket.com/30), [옵션 관리](https://etapi.gmarket.com/26).

옵션은 선택형·조합형·텍스트형·계산형으로 나뉜다. `optSeq`와 추천 옵션·선택값 코드를 구분하고 재고 관리 사용 여부와 사이트별 값을 확인한다. 카테고리 추천 코드만 내부 SKU 키로 사용하지 않는다. [옵션 관리](https://etapi.gmarket.com/26)

전체 상품 번호는 문자열로 보존한다. 1.0 → 2.0 전환과 같은 번호 변환은 조회로 확인하며 첫 연결 검증 중 상품 전환을 실행하지 않는다. 상세 페이지는 등록·수정·전환·조회 설명이 함께 있어 등록 성공 응답을 조회 DTO로 복사하면 안 된다. [번호 API](https://etapi.gmarket.com/30), [공통 상세 문서](https://etapi.gmarket.com/20)

## 최신 호출 제한과 불일치

**상품 목록 조회는 2026-08-10 08:00부터 분당 최대 20회**다. 초과 시 1분 후 다시 조회하도록 안내한다. 제한 집계의 정확한 키가 해당 공지에 명시되어 있지 않아 G마켓·옥션 합산으로 보수적으로 관리하도록 제안한다. 승인된 계정의 적용 기준은 지원 측에 확인한다. [최신 제한 공지](https://etapi.gmarket.com/220)

| 문서에서 확인한 차이 | 처리 기준 |
|---|---|
| siteId·sellStatus 타입 설명은 문자열, 조회 예시는 숫자 배열 | 첫 호출은 사이트 조회 예시의 숫자 siteId 사용. 상태 필터는 후속 확인 |
| 일반 JSON 예시는 pageIndex/pageSize가 0, 실제 조회 샘플은 1/10 | 실행 예시 1/10 사용. 0은 스키마 자리 표시로 취급 |
| 목록 goodsNo 응답은 문자열, 다른 상품 문서는 int | 입력·응답 ID는 문자열로 손실 없이 보관 |
| 목록 정상·업무 실패·인증 실패가 다른 모양 | API별 응답 검증. 하나의 공통 성공 코드를 강제하지 않음 |

근거: [목록 규격·예시](https://etapi.gmarket.com/160), [상세](https://etapi.gmarket.com/20), [인증](https://etapi.gmarket.com/pages/API-%EA%B0%80%EC%9D%B4%EB%93%9C).

## 실제 호출 때 남길 확인

- 승인·권한·키·master ID·issuer·허용 IP·운영 환경 확인.
- G와 A를 각각 호출하여 해당 사이트 상품 번호·판매자 확인.
- 각 사이트에서 알고 있는 상품 1건을 ESM+와 대조하고 상세 확인.
- 빈 목록·실패 본문의 HTTP 상태와 페이지 종료 조건 확인.
- 미사용 사이트 데이터의 null/빈 값, 옵션·재고 관리 정책 확인.
- 1.0 상품이 있는 계정은 목록 포함 여부·조회 방법을 공식 지원으로 확인.

주문·배송·클레임·정산·CS·스타배송은 후속 기능이다. 두 사이트에 동시에 영향을 주는 수정·삭제 API도 있으므로 쓰기 연동은 사이트별 적용 범위를 결정한 뒤 별도 설계한다. [공식 기능 목록](https://etapi.gmarket.com/pages/API-%EA%B0%80%EC%9D%B4%EB%93%9C), [상품 관리](https://etapi.gmarket.com/category/%EC%83%81%ED%92%88API/%EC%83%81%ED%92%88%EA%B4%80%EB%A6%AC%20API)
