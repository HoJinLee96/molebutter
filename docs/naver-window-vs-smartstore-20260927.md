# 네이버 쇼핑윈도와 스마트스토어의 차이

조사일: 2026-09-27. 공식 문서, 제공된 두 상품의 실제 HTTP 응답, at-a-glance의 현재 소스와 Git 이력을 대조했다. 애플리케이션 코드는 변경하지 않았다.

## 결론

1. `shopping.naver.com/window-products/department/...`는 **백화점 쇼핑윈도** 상품이다.
2. `smartstore.naver.com/lotte/products/...`는 **스마트스토어의 lotte 스토어 경로**다.

네이버는 스마트스토어를 `STOREFARM`, 쇼핑윈도를 `WINDOW` 전시 채널로 구분한다. 두 형태는 같은 판매 관리 체계와 연결될 수 있지만 채널·상품번호·구매자 화면은 구별된다. [네이버 커머스API 공식 답변](https://github.com/commerce-api-naver/commerce-api/discussions/2942)

쇼핑윈도 자체는 최근 생긴 개념이 아니다. 네이버의 2021년 반기보고서는 쇼핑윈도를 2014년 출시 서비스로 설명한다. 다만 현재 `window-products` URL 형식이 도입된 시점은 이번에 확인하지 않았다. [네이버 반기보고서](https://www.navercorp.com/navercorp_/ir/auditReport/2021/2Q21_consolidated_kor.pdf)

## 공식 상품 구조

- 원상품: 상품명·가격·재고·옵션 등 공통 상품 정보.
- 채널상품: 원상품에 채널별 전용 이름·전시 상태 등 정보가 더해진 것.
- 한 상품에 원상품번호 1개와 채널상품번호 1개 이상이 발급될 수 있다. 스마트스토어 채널상품번호와 쇼핑윈도 채널상품번호도 구분한다.
- 동일 원상품의 공통 정보 변경은 각 채널에 반영되지만, 서로 다른 상품 등록이나 판매자끼리 재고가 공유된다는 의미는 아니다.

근거: [원상품과 채널상품 공식 설명](https://github.com/commerce-api-naver/commerce-api/discussions/11). 판매자가 두 채널을 모두 선택한 경우 쇼핑윈도에서 삭제해도 스마트스토어 노출은 유지될 수 있다는 [판매자센터 안내](https://help.sell.smartstore.naver.com/faq/content.help?faqId=3432)도 있다.

**제공된 두 링크가 같은 판매자 계정이나 같은 원상품인지는 확인되지 않았다.** URL 형태와 `lotte`라는 문자열만으로 판매자·지점·재고를 통합하면 안 된다.

## 실제 호출 비교

두 상품 모두 기존 코드와 동일한 주소에 각각의 채널 상품번호를 넣었다. 헤더는 `Accept: application/json`, `User-Agent: Mozilla/5.0`, 쿠키·인증 없음.

| 구분 | 1번 쇼핑윈도 | 2번 스마트스토어 |
| --- | --- | --- |
| 사용자 제공 주소 | `shopping.naver.com/window-products/department/12220827624` | `smartstore.naver.com/lotte/products/13694203010` |
| 호출 주소 | `https://shopping.naver.com/v2/channel-products/12220827624` | `https://shopping.naver.com/v2/channel-products/13694203010` |
| 응답 시각(KST) | 2026-09-27 22:45:06 | 2026-09-27 22:45:22 |
| HTTP 결과 | 200 JSON | 204, 본문 없음 |
| 응답 채널 서비스 타입 | `contents.channelServiceType=WINDOW` | 확인 불가 |
| 세부 채널 타입 | `channel.verticalType=DEPARTMENT` | 확인 불가 |
| 판매 채널 이름 | `channel.name=헤지스핸드백` | 확인 불가 |
| 백화점·지점 | `channel.storeCategory.wholeNames=[롯데백화점, 본점]` | 확인 불가 |
| 모델 코드 | `HIWA5F450BK` | 확인 불가 |
| 재고 | `contents.stockQuantity=77` | **미확인**, 0으로 해석 불가 |
| 옵션 | `contents.optionUsable=false` | 확인 불가 |

1번은 추가로 `contents.productNo=12165045770`, `contents.epInfo.syncNvMid=89765338329`를 반환했다. 요청 상품번호·원상품 관련 번호·검색 ID를 섞지 않아야 한다. 두 v2 응답 모두 `Cache-Control: max-age=120`이 있었다.

2번의 사용자 제공 상품 페이지도 직접 GET으로 확인했으나 **HTTP 429**와 접속 불가 HTML을 반환했다. 추가 반복 요청은 하지 않았다. 따라서 일반 스마트스토어의 자체 화면/API 응답 구조와 현재 재고는 이번에 확보하지 못했다.

**확정할 수 있는 것:** 이번 일반 스마트스토어 상품은 기존 쇼핑윈도 v2 호출로 재고를 받지 못했다.

**확정할 수 없는 것:** 모든 STOREFARM 상품이 해당 API에서 조회 불가인지, 204의 원인이 채널 미지원인지 상품 상태나 다른 조건인지. 따라서 "일반 스마트스토어 재고 조회 자체가 불가능하다"고 결론내릴 수 없다.

## at-a-glance의 실제 처리 방식

현재 소스에서는 양쪽을 모두 `PurchaseSource.NAVER_SMART_STORE`로 분류한다.

- 상품번호 추출기 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/service/PurchaseSourceProductIdExtractor.java:41`는 쇼핑윈도의 `/window-products/department/`, `/window-products/brandfashion/`과 스마트스토어의 `/products/`를 모두 인식한다.
- 번호 추출 테스트 — `/Users/leehj/workspace/at-a-glance/src/test/java/store/korcokrlee/ataglance/service/PurchaseSourceProductIdExtractorTest.java:139`에도 일반 스마트스토어 URL이 있다. 다만 이 테스트는 URL 파싱 검증이며 실제 재고 API 지원 검증은 아니다.
- 재고 조회기 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/infra/product/NaverSmartStoreStockFetcher.java:35`는 추출된 상품번호를 공통 `NaverShoppingChannelProductClient`에 전달한다.
- 공통 클라이언트 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/infra/product/NaverShoppingChannelProductClient.java:57`는 `https://shopping.naver.com/v2/channel-products/`를 사용한다. `WINDOW`와 `STOREFARM`에 따라 요청 주소를 나누는 구현은 확인하지 못했다.
- HTTP 204는 해당 클라이언트에서 `unavailable("status=204")`로 처리한다.

즉 **코드의 분류명과 URL 수용 범위는 넓지만, 현재 재고 조회 구현은 쇼핑윈도에서 검증된 단일 API에 의존**한다. 이 대화에서 성공한 기존 네이버 샘플들도 `DEPARTMENT` 또는 `BRAND_FASHION` 쇼핑윈도였다.

### Git 이력

로컬 Git에 남아 있는 해당 파일 이력을 확인했다.

- `2d415f7` (2026-05-18): 재고 API 호출 없이 `-1`(확인 불가)을 반환하는 임시 구현.
- `3ab0003` (2026-06-18) 시점: `shopping.naver.com/product-detail/v1/validation-products/`와 `brand.naver.com/n/v1/validation-products/`를 순서대로 시도하는 구현.
- `c46e787` (2026-06-18): brand 요청의 429 반복에 대한 일시 중단 로직 추가.
- `b8df225` (2026-06-27): `NaverShoppingChannelProductClient` 기반의 현재 v2 방식으로 전환.

따라서 "처음부터 1번만 지원하도록 만들었다"는 설명은 정확하지 않다. 여러 경로를 시도한 이력이 있으며 이후 현재 단일 v2 방식으로 정리됐다. 과거 경로들이 지금도 동작하는지는 이번에 재호출하지 않았다.

## molebutter에 적용할 설계 판단

현재 조사 결과에 따른 제안이며 아직 구현하지 않았다.

1. 사용자 표시와 내부 판별에서 `네이버 쇼핑윈도`와 `네이버 스마트스토어`를 구분한다. 쇼핑윈도 안에서는 `DEPARTMENT`와 `BRAND_FASHION`을 별도로 보존한다.
2. 플랫폼, 전시 채널 유형, 채널 상품번호, 판매 채널 ID/이름, 백화점·지점, 쇼핑 검색 ID를 별도 정보로 다룬다.
3. 쇼핑윈도 재고 API 성공을 일반 스마트스토어 지원 성공으로 표시하지 않는다. 일반 스마트스토어는 별도 샘플·요청 경로 검증이 필요하다.
4. 같은 상품처럼 보여도 판매자·원상품 연결이 확인되기 전에는 재고를 공유하거나 합산하지 않는다.
5. 204·429·HTML 오류·필드 누락은 조회 미확인/제한으로 보존한다.

공식 판매자용 커머스API는 별도 체계다. 판매자 계정 권한이 있는 연동에서 채널 정보를 확인할 수 있지만, 지금처럼 외부 판매자의 상품을 읽는 프런트엔드 API와 동일시하면 안 된다. [공식 계정별 채널 조회](https://apicenter.commerce.naver.com/docs/commerce-api/current/get-channels-by-account-no-sellers)

위 절대경로와 줄 번호는 조사 당시의 소스 위치 기록이다. 다른 환경에서 열리는 링크가 아니며, 당시 조사 결론과 근거 위치를 보존한다.
