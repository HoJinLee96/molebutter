# 롯데ON 상품·브랜드 연동

2026-10-14 신규 등록 재확인: 응답 spdNo를 조회하고 모든 옵션 UUID와 sitmNo의 일대일 대응 및 fnlAprvYn=Y를 확인한다. 과거 입력값의 일치나 현재 편집 지원 여부를 등록 완료 조건과 혼합하지 않는다. 승인 N·누락·반려와 단품 연결 미확인은 구분한다. UPDATE 미지원 유형은 승인 대기로 표현하지 않고 자동 확인 불가로 안내한다. [공통 결과 확인 검증](../verification/2026-10-14-market-registration-confirmation.md).

문서 반영일: 2026-10-07. 사용자가 교체한 프로젝트의 [API 문서 README](../../marketplace-api-docs/README.md)를 기준으로 한다. 패키지의 원문 수집 기준일은 2026-10-03이며, 각 문서의 `fetched_at`을 따로 확인한다.

제공된 README에는 롯데ON API 115개·가이드 2개·공지 100개·FAQ 64개, 총 281개 문서의 노트 검수 완료가 기록되어 있다. 이 수치는 제공된 검수 현황이며 이번 작업에서 281개 계약을 전수 검증하거나 실제 계정을 호출한 결과가 아니다. 2026-10-14 상품 조회·등록·승인 상품 수정과 통합 편집을 연결했다. 주문은 미구현이다. 실제 계정 검증과 모의 검증은 아래 기록에서 구분한다.

## 읽기 순서

1. [최신 README](../../marketplace-api-docs/README.md), [롯데ON OVERVIEW](../../marketplace-api-docs/lotteon/OVERVIEW.md), [INDEX](../../marketplace-api-docs/lotteon/INDEX.md)를 읽는다. 패키지는 README 기준 셀러용 SL이며 운영자용 OP는 수집 범위 밖이다. V2 등 버전과 계열사 전용 조건을 구분한다.
2. INDEX에서 대상 API와 같은 주제의 FAQ·공지를 찾고 각각 SOURCE를 확인한다. OVERVIEW·노트의 관련 링크만으로 FAQ를 모두 확인했다고 간주하지 않는다.
3. [KNOWN-ISSUES](../../marketplace-api-docs/lotteon/KNOWN-ISSUES.md)의 관련 항목을 읽고 충돌·중첩 구조는 `_raw/lotteon/api_html/`에서 대조한다. `_outline/`의 평면 텍스트는 계층 확인에 적합하지 않다.
4. 여러 마켓의 공통 모델을 설계할 때는 [인증·공통 규격](../../marketplace-api-docs/cross-market/01-인증-공통규격-비교.md), [상품·가격·재고](../../marketplace-api-docs/cross-market/02-상품-가격-재고-매핑.md), [주문·배송·클레임](../../marketplace-api-docs/cross-market/03-주문-배송-클레임-매핑.md)을 길잡이로 읽고 연결된 SOURCE로 대응 관계를 확인한다.

## 최초 읽기 구현의 근거

| 대상 | 확인할 문서 | 확인 범위 |
|---|---|---|
| 인증 | [API 개발가이드](../../marketplace-api-docs/lotteon/guide/시작하기-API-개발가이드.md), [이용안내](../../marketplace-api-docs/lotteon/guide/이용안내.md) | 판매자 인증키의 Bearer 헤더, 등록된 출발지 IP와 필요한 헤더 |
| 계정 확인 | [207 Identity](../../marketplace-api-docs/lotteon/api/공통/207-Identity.md) | 계정 식별자와 해당 API의 실제 응답·업무 성공 판정 |
| 상품 목록 | [93 상품 목록 조회](../../marketplace-api-docs/lotteon/api/상품/93-상품-목록-조회.md) | `POST https://openapi.lotteon.com/v1/openapi/product/v1/product/list`, 요청 필수값·조회 범위·페이징 |
| 상품 상세 | [94 상품 상세조회](../../marketplace-api-docs/lotteon/api/상품/94-상품-상세조회.md) | 상품·단품 식별자와 마켓별 필드·옵션 구조 |
| 호출 제한 | [개발가이드](../../marketplace-api-docs/lotteon/guide/시작하기-API-개발가이드.md), [FAQ 122](../../marketplace-api-docs/lotteon/faq/공통/122-API-호출횟수가-제한되어-있나요.md) | 가이드의 분당 10,000회와 FAQ의 10초당 10,000회 서술 충돌. 확정 제한으로 적용하기 전 확인 필요 |

HTTP 200과 업무 성공·정상 구조·대상 계정 일치는 별도로 확인한다. 가이드의 예시를 모든 API의 성공 응답 계약으로 복사하지 않는다. POST라는 이유로 조회를 외부 쓰기로 분류하지 않는다.

## 브랜드 조회 구현 (2026-10-09)

`molebutter-marketplace-lotteon`은 `LotteonBrands` 공개 계약으로 브랜드 후보 조회만 제공한다. app의 `GET /api/marketplaces/lotteon/metadata/brands?name=`가 권한을 확인하고 `{id:string, name:string}` 목록을 반환한다. 공통 기준의 통합 브랜드 창에서 쿠팡·스마트스토어와 같은 검색어로 조회하되 롯데ON 결과를 별도로 직접 선택한다. 선택 이름은 `markets.LOTTEON.overrides.brand`, ID는 `markets.LOTTEON.lotteon.brandId`에 보관하며 기존 초안은 이 설정이 없어도 읽는다. 선택 적용과 초안 저장은 롯데ON 상품 쓰기 요청을 보내지 않는다.

호출은 [204 브랜드 조회 SOURCE](../../marketplace-api-docs/lotteon/api/상품속성/204-브랜드-조회.md)의 고정 주소를 사용한다.

```text
GET https://onpick-api.lotteon.com/cheetah/econCheetah.ecn?job=cheetahBrnd&skip=0&limit=100&mf_1=<URL 인코딩한 검색어>
Accept: application/json
Accept-Language: ko
X-Timezone: GMT+09:00
Authorization: Bearer <런타임 인증키>
```

기존 `lotteon_api_key` → `marketplace.lotteon.api-key` 설정을 실행 시 주입하며 비밀 파일을 열거나 키·헤더를 응답과 로그에 복사하지 않는다. 인증·헤더는 [개발가이드 SOURCE](../../marketplace-api-docs/lotteon/guide/시작하기-API-개발가이드.md), 상품속성의 인증 전환은 [2024-08-28 공지](../../marketplace-api-docs/lotteon/notices/102879-상품속성-OpenAPI-인증-방식-및-Response-항목-변경-안내.md), 중간 일치 `mf_1` 검색은 [2024-10-16 공지](../../marketplace-api-docs/lotteon/notices/104708-브랜드-조회API-변경-안내.md)와 대조했다. 상품 API의 `openapi.lotteon.com`으로 호스트를 통일하지 않는다.

현재는 `skip=0`, `limit=100` 한 번으로 후보를 가져오며 다음 페이지를 제공하지 않는다. UI는 결과 개수와 관계없이 최대 100개 조회 및 검색어 구체화 안내를 표시한다. `itemList[].data`의 활성 항목(`use_yn=Y`, 삭제 제외)만 선택 가능하며 `brnd_id`·`brnd_nm`의 타입·길이와 중복 ID의 이름 일치를 검사한다. 정상 목록으로 확인할 수 없는 응답이나 인증·권한 오류를 빈 검색 결과로 숨기지 않는다.

외부 호출은 동시 실행 잠금, 최소 1초 간격, 5분 메모리 캐시, 20초 응답 제한과 1MiB 본문 상한을 사용한다. 리다이렉트를 따르지 않는다. 429의 `Retry-After` 대기 상태를 보관하고 대기 중 재호출을 제한하며 자동으로 쓰기나 재시도를 만들지 않는다. 이 간격은 앱의 보수적인 정책이며 상충하는 공식 호출 제한을 확정한 값이 아니다.

조회 `brnd_id`의 길이 12와 [87 상품 등록 SOURCE](../../marketplace-api-docs/lotteon/api/상품/87-상품-등록.md)의 `spdLst[].brdNo` 길이 7 차이는 미해결이다. 이름·ID 선택은 등록 가능 보증이 아니다. 새 브랜드 코드가 7자를 넘으면 자르지 않고 전송을 차단한다. 실제 계정의 키·허용 IP·응답 성공은 별도로 확인해야 한다. 모의 검증 결과는 [testing](../testing.md)의 기록을 따른다.

## 후속 구현에서 지킬 경계

- 대상 SOURCE의 Method·전체 URL과 버전을 확인한다. 상품속성의 별도 호스트, 일부 전시 문서의 test 호스트 등 예외는 [README](../../marketplace-api-docs/README.md)의 안내를 따라 개별 SOURCE로 대조한다. 운영 URL을 추측해 바꾸지 않는다.
- 필수 표기 `X`·`Δ`, 판촉 구분 코드와 평면화된 중첩 필드는 범례·설명이 없는 의미를 만들어내지 않는다. 원문 표와 조건 설명을 확인한다.
- 상품·옵션·계정·주문 식별자와 상태값을 다른 마켓의 계약으로 대체하지 않는다. 주문 조회와 연동완료·배송·클레임 통보는 별도 계약과 실행 권한으로 설계한다.
- 어댑터 구현은 [공통 상품 편집](common-product-editor.md), [판매 주문](sales-orders.md), [테스트 기록](../testing.md)을 코드와 함께 대조한다. 기존 쿠팡 테스트 결과를 롯데ON 검증으로 간주하지 않는다.
- 제공된 SOURCE·원문·검수 자료는 보존한다. 제어문자를 처리할 필요가 있으면 소비용 사본에서 처리한다. 자격 증명은 기존 런타임 설정으로 연결하며 비밀 파일을 직접 열거나 인증값을 기록하지 않는다.


## 상품 등록·수정 연결 진행 상황 (2026-10-10)

[구현 현황과 남은 연결 작업](partner-product-implementation.md)을 참고한다.
이 절의 2026-10-10 기록은 과거 기반 구현이다. 현재 범위는 다음 절을 따른다.

## 상품 통합 구현 (2026-10-14)

- `/marketplaces/lotteon/products/new`, `/marketplaces/lotteon/products/{spdNo}/edit`와 공통 롯데ON 탭은 `common-marketplace-editor.html`·제어 코드와 `LotteonProductForm`을 재사용한다. `LOTTEON_REGISTRATION` 초안과 사용자 공통 상품을 구분하며, 단독 편집만으로 공통 상품을 만들지 않는다. 입력 Enter는 저장·전송하지 않는다.
- 인증된 `/api/marketplaces/lotteon/products`, `/products/{productId}`, `/schema`, `/metadata/{kind}`를 제공한다. metadata 종류는 categories, display-categories, attributes, codes, outbound, return, shipping-policies다. 상품 목록은 등록일 기준 최근 30일·20개가 기본이며 상품번호 직접 조회도 지원한다. 속성 메타데이터는 100개 단위로 페이지를 이동한다.
- 계정은 런타임 `lotteon_api_key`와 Identity 207의 거래처로 판정한다. 일반 셀러(SR)의 국내 일반 새 상품만 편집한다. 출고·반품지·배송비 정책은 계정·활성 여부·적용 기간을 확인하며 하위 거래처가 있으면 154의 거래 중 상태도 확인한다. 주소·정책 생성 요청은 없다.
- 상품·거래처·공통 코드는 `openapi.lotteon.com`, 카테고리·속성은 SOURCE의 `onpick-api.lotteon.com`을 사용한다. 브랜드와 상품 호출은 계정별 잠금·최소 1초 간격을 공유하며 429 대기를 보존한다. 자동 쓰기 재시도는 없다.
- `LotteonInput`은 코드가 있는 상품/단품 속성, 단품별 이미지·유형, 대표 단품을 UUID로 보관한다. 기존 단순 입력 JSON도 읽지만 신규 선택형 옵션은 카테고리에 연결된 속성·값 코드를 선택해야 한다. 기존 옵션명·값 변경과 삭제는 거절한다. 전체 구성이 확인된 경우 코드가 있는 새 단품을 추가한다.
- 공식 최대 500개 단품을 조회한다. 앱의 공통 옵션 제한은 200개이므로 201~500개 상품은 원본 단품 목록 전체를 표시하되 편집·공통 연결을 제한한다. 미승인·반려·특수 유형도 조회만 지원한다.
- 등록 87·승인 수정 90은 허용 항목으로 전문을 새로 만든다. 원본 응답·미선택 단품의 이미지·가격·재고·파일 삭제 배열을 복사하지 않는다. 기존 이미지와 다른 설명 유형은 보존하며 필수 보존값 미확인은 차단한다. 90 표에 없는 기존 카테고리·판매 유형 등의 변경은 거절한다.
- 기존 단품 가격은 91, 재고는 86, 상품·단품 상태는 92·111로 분리한다. 공통 정상가 128000/판매가 87000은 `slPrc=87000`이다. 별도 판매자할인을 생성·수정·삭제하지 않는다. 기존 가격 변경 전 135를 조회하여 할인 주의를 표시하며, 실행 전 할인 구성이 달라지면 중단한다. 할인 조회 실패·불명확 구조는 가격만 차단한다.
- 135는 필수 번호·날짜 필드와 예시·응답 계층이 불일치한다. 현재는 표의 날짜 필드와 `data.prList` 정상 배열만 사용하고 미확인을 빈 할인으로 해석하지 않는다. 실계정 계약 확인 전 가격 변경 성공을 보장하지 않는다.
- 단품 이미지 1~10개·대표 1개, JPG/PNG·공개 HTTPS·200자 URL을 검사한다. IMG_SQRE와 IMG_LNTH의 형태를 구분하고 쿠팡 픽셀 제한을 적용하지 않는다. 10MiB·2500만 픽셀은 앱 처리 제한이다. 상세설명에는 `alt`를 요구하며 별도 이미지 기준을 사용한다. R2와 서버 편집본을 재사용하고 편집본은 공개 토큰 URL로 보낸다.
- 총괄·개별 결과 코드를 모두 확인한다. 응답의 spdNo와 재조회한 sitmNo를 연결하며 승인 대기·반려·반영 미확인을 구분한다. 응답을 잃은 신규 등록은 상품명·SKU로 상품을 찾아 재등록하지 않는다. 반환 상품번호가 없으면 결과 미확인으로 남긴다.
- 새 공통 가져오기·기존 공통 연결은 사용자의 옵션 대응표로만 연결한다. 다른 마켓은 최초 탭 클릭 때 조회하며, 옵션별 가격·수량·이미지 개별값과 상속·보관 변경을 임시 저장·재진입 후 유지한다.

### 검증 경계

모의 JSON 전문, 격리 MySQL/Redis, 실제 HTML의 Chrome 1440/390px 화면으로 검증했다. [테스트 기록](../testing.md)의 2026-10-14 절을 참고한다. 인증값 추가 전의 최초 실계정 진단은 런타임 API 키가 비어 CONFIGURATION에서 종료됐다. 이후 설정 추가와 재검증 결과는 아래 실계정 읽기 재검증 절을 따른다. 비밀 파일은 열거나 수정하지 않았다. 실제 마켓 쓰기·이미지 전송·R2 변경·운영 DB 변경·서버 재시작은 하지 않았다.

## 연동 재검토 — 2026-10-14

카테고리·전시 카테고리·속성 선택창은 `GET /api/marketplaces/lotteon/metadata/{kind}/page`의 `{items,page,hasNext}`를 사용한다. 기존 목록 API는 유지한다. 원본 응답 100건에 삭제·비활성 항목이 포함되면 표시 목록이 비어도 다음 페이지가 있을 수 있으므로 `hasNext`는 필터 전 응답 개수로 판정한다. 조회 실패 시 직전 목록과 페이지를 유지한다. `LotteonMetadataPagingTest`와 Chrome PC·모바일 검사에서 빈 표시 목록 이후 다음 페이지 및 실패 후 선택 보존을 확인한다.

HTTP 200이라도 `returnCode` 또는 단일 상품의 `resultCode`가 누락·미확인이면 `RESPONSE_UNKNOWN`으로 유지한다. 응답 코드가 없다는 이유만으로 확정 실패·재전송 대상으로 바꾸지 않는다. `LotteonProductMappingTest`에서 실제 변경 뒤 코드 없는 응답을 모의하고, 쓰기 반복 없이 조회로 반영을 확인했다. 인증값 추가 전의 실계정 읽기는 진단 런타임의 CONFIGURATION 오류로 확인하지 못했으며, 이후 결과는 다음 절을 따른다.

## 인증 설정 후 실계정 읽기 재검증 — 2026-10-14

사용자가 기존 `marketplace.properties`에 인증값을 추가한 뒤 재검증했다. 개발 실행 classpath와 실제 서버 작업 디렉터리에서 Spring ConfigData의 `lotteon_api_key` 존재, `marketplace.lotteon.api-key` 해석, 두 값의 일치를 값 출력 없이 확인했다. 설정 연결 코드는 변경하지 않았다. 비밀 파일은 직접 열거나 수정하지 않았으며, DB·워커·웹서버를 띄우지 않는 최소 컨텍스트에서 실제 provider client와 catalog를 실행했다.

| 실계정 읽기 | 결과 |
|---|---|
| Identity 207 | 인증·업무 성공 `0000` 확인 |
| 출고지·반품지 150 | 정상 조회, 각각 0건; 원본 주소 응답도 0건 |
| 배송비 정책 166 | 정상 조회, 현재 적용 가능한 정책 0건 |
| 상품 목록 93 — 최근 30일 | 정상 조회, 0건·다음 페이지 없음 |
| 상품 목록 93 — 2010-01-01~2026-10-14 | 정상 조회, 0건·다음 페이지 없음 |
| 실제 상품 상세 94 | 조회 가능한 상품번호가 없어 미실행 |

실제 빈 상품 응답은 `returnCode: "0000", dataCount: 0, data: null`이었다. 앱이 `data` 배열만 허용하여 정상 빈 목록을 RESPONSE 오류로 처리하는 문제를 재현·수정했다. 성공 코드와 명시적인 숫자 0건이 있는 null/생략 data만 정상 빈 페이지로 변환한다. 개수 미확인·양수 개수와 null의 불일치·객체 data·업무 오류는 빈 결과로 숨기지 않는다. `LotteonEmptyProductListTest` 6개 사례와 격리 HTTP 통합 검사에서 확인했다.

`Lotteon*Test`와 `MarketplaceCredentialConfigurationTest` 합계 46개, `MarketplaceEditingFlowIT` 36개 통과. JAR 재빌드, diff 공백 검사 통과. 실제 상품 등록·수정·이미지 전송·주소 생성·운영 DB 변경·서버 재시작은 하지 않았다.

현재 확인한 인증 파일은 개발 실행의 classpath 설정이다. 배포 JAR는 프로젝트의 기존 보안 정책에 따라 비밀 파일을 포함하지 않는다. JAR 단독 실행은 실행 디렉터리의 `application-secret/marketplace.properties` 또는 기존 환경 설정을 사용해야 하며, 개발용 classpath 파일을 수정하는 것만으로 JAR에 인증값이 포함되지는 않는다. 이번 검증에서 JAR 단독 설정 진단은 해당 외부 설정 없이 수행되어 키가 비어 있었다.

## 등록·수정 선택값 조회 보강 (2026-10-14)

공통 주소 선택 창에서도 롯데ON 출고지·반품지를 조회한다. `dvpNo`의 `PLO`/`PLE` 등 접두어를 보존해 공통 초안에 저장하고, 신규 입력의 `owhpNo`·`rtrpNo`로 투영한다. 기존 개별 입력과 UPDATE 조회값은 상속으로 덮어쓰지 않는다. 하위 거래처 주소·정책은 롯데ON 탭의 조회 코드에 `lrtrNo`를 지정하며 서버도 `afflLrtrCd`로 요청한다.

표준 카테고리 조회는 페이지 이동·현재 페이지 이름 검색·상위 분류 클릭 시 하위 탐색을 지원한다. `GET /api/marketplaces/lotteon/metadata/{kind}/page`의 선택적 `parent`를 205/206 SOURCE의 `filter_2`로 전달한다. 전시 카테고리는 선택한 표준 카테고리의 `disp_list` 중 `LTON` 코드를 실제 재조회해 사용 중인 최하위 후보만 표시한다. 전체 전시 목록의 비활성 페이지를 상품에 맞는 후보로 간주하지 않는다.

롯데ON 탭의 브랜드 검색도 기존 204 브랜드 API를 사용한다. 속성 선택은 해당 카테고리의 모든 P/I 속성 종류를 이름으로 조회한 뒤 사용자가 종류·값을 직접 지정한다. 첫 번째 속성에 고정되지 않는다. 상품 속성 삭제, 카테고리 고시 유형(`PD_ITMS_CD`), 안전인증 유형(`SFTY_ATHN_TYP_CD`), 수입대행 구분(`IMP_PRX_CD`)과 기존 원산지·택배사·배송 코드 조회를 제공한다. 코드·속성 조회도 기존 계정별 호출 간격을 공유한다. 승인 수정에서 지원하지 않는 표준 카테고리 등은 입력을 잠근다.

실계정 조회 결과와 남은 계정 준비 사항은 [검증 기록](../verification/2026-10-14-market-registration-lookups.md)에 구분했다. 실제 상품 쓰기는 수행하지 않았다.


## 공통 배송·판매·A/S 적용 (2026-10-14)

공통 배송 유형·택배사·발송예정일수·판매기간·과세·A/S를 연결한다. A/S는 `epnLst.AS_CNTS`로 보내고 다른 설명 유형은 보존한다. 공통 배송비 적용 의도인 `deliveryIntent`는 앱 내부에만 보관하고 선택한 정책의 실제 금액과 비교한다. **선택한 정책 금액 사용**은 그 마켓의 개별값으로 저장한다. 이전 초안에 적용 의도가 없으면 공통 금액을 새로 강제하지 않는다. [구현·검증 기록](../verification/2026-10-14-common-delivery-sales-service.md).
