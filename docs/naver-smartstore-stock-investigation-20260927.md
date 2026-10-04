# 일반 스마트스토어 재고 조회 가능성

조사일: 2026-09-27 (KST). 대상: `https://smartstore.naver.com/lotte/products/13694203010`.

## 판단

- 판매자 권한이 있는 스토어: 공식 커머스API의 상품 조회로 재고 정보를 받을 수 있다. 이번에는 판매자 인증 정보가 없으므로 실제 호출하지 않았으며 문서로 확인했다.
- 외부 판매자의 일반 스마트스토어: 현재 확보한 공개 경로로는 해당 샘플의 재고 조회에 성공하지 못했다. 상품 URL/번호만으로 안정적으로 조회 가능한 API를 확보했다고 볼 수 없다.
- 이것은 일반 스마트스토어의 재고 조회가 원천적으로 불가능하다는 결론이 아니다. 구매자 화면에서 쓰는 데이터 경로는 아직 확인하지 못했다.
- 백화점/브랜드패션 쇼핑윈도에서 성공했던 `shopping.naver.com/v2/channel-products/`를 일반 스마트스토어 지원의 근거로 사용하면 안 된다.

## 익명 HTTP 조회 결과

쿠키·인증 정보 없이 `Accept: application/json`, `User-Agent: Mozilla/5.0`를 사용했다. 이번 추가 호출은 curl 기본 설정을 제외(`-q`)하고 리디렉션을 따라가지 않았다. 429 재시도는 하지 않았다.

모든 경로 끝에 샘플의 채널 상품번호 `13694203010`을 붙였다.

| 경로 | 확인 시각(KST) | 결과 | 의미 |
| --- | --- | --- | --- |
| `https://shopping.naver.com/v2/channel-products/` | 22:45:22, 앞선 조사 | 204, 빈 본문 | 기존 구현으로 재고를 받지 못함 |
| `https://shopping.naver.com/product-detail/v1/validation-products/` | 22:52:51, 이번 추가 조사 | 204, 0바이트 | 과거 shopping 경로에서도 재고 미확인 |
| `https://brand.naver.com/n/v1/validation-products/` | 22:53:03, 이번 추가 조사 | 429, HTML | 과거 brand 대체 경로는 요청 제한 응답. 일반 스마트스토어 지원 여부 판정 불가 |

앞선 익명 상품 페이지 GET도 429와 접속 불가 HTML을 반환했다. 204/429는 재고 0 또는 품절이 아니다. 204만으로 상품 삭제, 채널 미지원 등의 원인을 단정할 수 없다.

이번 브라우저 상품 페이지 분석은 `nid.naver.com` 접근에 대한 자동 승인 검토에서 거절됐다. 검토 사유는 공개 상품 분석에 로그인 도메인 접근이 직접 필요하지 않으며 브라우저 인증 세션·쿠키가 불필요하게 노출될 수 있다는 것이었다. 로그인 도메인 접근이나 다른 브라우저를 통한 재시도는 하지 않았다. 따라서 정상 구매자 화면의 네트워크 요청/응답과 HTML 내 재고 필드는 검증하지 못했다. 이 도구 차단은 네이버 API 자체의 지원 여부와 별개다.

원시 응답 파일:

- `/tmp/naver-channel-13694203010.headers`, `.json` (앞선 조사)
- `/tmp/naver-smartstore-13694203010.headers`, `.html` (앞선 조사)
- `/tmp/naver-smartstore-validation-shopping-13694203010.headers`, `.body`
- `/tmp/naver-smartstore-validation-brand-13694203010.headers`, `.body`

## at-a-glance 이력에서 확인한 점

커밋 `3ab0003`의 `NaverSmartStoreStockFetcher`는 shopping의 `validation-products`를 호출한 뒤, 실패하면 brand의 `validation-products`를 호출했다. 응답 DTO의 `stockQuantity`가 있어야 성공으로 처리했다. 이 코드는 과거에 구현된 호출 경로의 근거이며, 일반 스마트스토어의 실사용 성공 증거는 아니다.

현재 `NaverSmartStoreStockFetcher`는 `NaverShoppingChannelProductClient`로 상품번호를 전달하며, 기본 URL은 `https://shopping.naver.com/v2/channel-products/`다. 일반 스마트스토어 URL의 `/products/{번호}`를 파싱하는 테스트가 있지만, 재고 실조회 성공 검증과는 다르다.

관련 코드:

- 상품번호 추출기 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/service/PurchaseSourceProductIdExtractor.java:41`
- 재고 조회기 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/infra/product/NaverSmartStoreStockFetcher.java:35`
- 채널 상품 클라이언트 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/infra/product/NaverShoppingChannelProductClient.java:57`

## 공식 판매자용 API

문서의 상품 조회 경로:

```http
GET https://api.commerce.naver.com/external/v2/products/channel-products/{channelProductNo}
Authorization: Bearer {판매자_인증_토큰}
```

원상품번호를 알고 있다면 `GET /v2/products/origin-products/{originProductNo}`도 제공된다. 재고는 원상품 정보이며 `stockQuantity`, 조합형 옵션의 `detailAttribute.optionInfo.optionCombinations[].stockQuantity` 등으로 정의되어 있다. 상품 조회 응답에서는 원상품 객체 아래의 해당 필드를 읽어야 한다.

표준형 옵션은 `optionStandards[].stockQuantity`와 `useStockManagement` 설정도 확인해야 한다. 공식 구조체는 옵션 재고 관리를 사용하지 않으면 수량이 9,999로 설정된다고 설명한다. 따라서 수량 필드가 있더라도 항상 실물 창고 재고로 해석할 수는 없다.

공식 답변은 내스토어 애플리케이션으로 타 판매자의 데이터를 조회·수정할 수 없다고 명시한다. 본인 API 키를 발급받는 것만으로 `lotte` 같은 외부 판매자의 재고를 읽을 수 있는 것은 아니다. 권한을 가진 판매자 연동과 공개 상품 수집은 별도 경로다.

공식 근거:

- [채널 상품 조회](https://apicenter.commerce.naver.com/docs/commerce-api/current/read-channel-product-1-product)
- [원상품 조회](https://apicenter.commerce.naver.com/docs/commerce-api/current/read-origin-product-product)
- [원상품 정보 구조체](https://apicenter.commerce.naver.com/docs/commerce-api/current/schemas/%EC%9B%90%EC%83%81%ED%92%88-%EC%A0%95%EB%B3%B4-%EA%B5%AC%EC%A1%B0%EC%B2%B4)
- [인증 토큰 발급](https://apicenter.commerce.naver.com/docs/commerce-api/current/exchange-sellers-auth)
- [타 판매자 데이터 조회 관련 네이버 답변, 2025-01-15](https://github.com/commerce-api-naver/commerce-api/discussions/2234)
- [원상품·채널상품 구분](https://github.com/commerce-api-naver/commerce-api/discussions/11)

## 적용 판단과 남은 검증

현재 상태로 일반 스마트스토어를 재고 조회 지원 대상으로 확정하지 않는다. 조회 실패는 미확인으로 보존하고, 성공한 쇼핑윈도와 구분한다. 애플리케이션 코드는 이번 조사에서 변경하지 않았다.

추가 검증에는 정상적으로 열리는 일반 스마트스토어 구매자 화면에서 상품/옵션을 읽는 요청과 응답이 필요하다. 상품 식별자, 실제 재고 필드, 옵션별 수량, 인증·세션 의존성, 429 발생 조건을 확인해야 공개 수집 구현의 가능성을 판정할 수 있다. 현재는 이 요청 경로를 확보하지 못했으므로 추측한 엔드포인트를 구현에 넣지 않는다.

쇼핑윈도와 일반 스마트스토어가 같은 원상품에 연결되어 있다면 재고 정보는 공통이지만, 두 URL의 판매자·원상품 연결을 먼저 확인해야 한다. 모델명이나 스토어 이름이 같다는 이유만으로 쇼핑윈도 재고를 일반 스마트스토어 재고로 대체하면 안 된다.

위 절대경로와 줄 번호는 조사 당시의 소스 위치 기록이다. 다른 환경에서 열리는 링크가 아니며, 당시 조사 결론과 근거 위치를 보존한다.
