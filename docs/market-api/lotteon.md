# 롯데ON 문서 참조와 구현 준비

문서 반영일: 2026-10-07. 사용자가 교체한 프로젝트의 [API 문서 README](../../marketplace-api-docs/README.md)를 기준으로 한다. 패키지의 원문 수집 기준일은 2026-10-03이며, 각 문서의 `fetched_at`을 따로 확인한다.

제공된 README에는 롯데ON API 115개·가이드 2개·공지 100개·FAQ 64개, 총 281개 문서의 노트 검수 완료가 기록되어 있다. 이 수치는 제공된 검수 현황이며 이번 작업에서 281개 계약을 전수 검증하거나 실제 계정을 호출한 결과가 아니다. 롯데ON 상품·주문 어댑터와 실제 계정 읽기·쓰기 검증은 후속 작업이다.

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

## 후속 구현에서 지킬 경계

- 대상 SOURCE의 Method·전체 URL과 버전을 확인한다. 상품속성의 별도 호스트, 일부 전시 문서의 test 호스트 등 예외는 [README](../../marketplace-api-docs/README.md)의 안내를 따라 개별 SOURCE로 대조한다. 운영 URL을 추측해 바꾸지 않는다.
- 필수 표기 `X`·`Δ`, 판촉 구분 코드와 평면화된 중첩 필드는 범례·설명이 없는 의미를 만들어내지 않는다. 원문 표와 조건 설명을 확인한다.
- 상품·옵션·계정·주문 식별자와 상태값을 다른 마켓의 계약으로 대체하지 않는다. 주문 조회와 연동완료·배송·클레임 통보는 별도 계약과 실행 권한으로 설계한다.
- 어댑터 구현은 [공통 상품 편집](common-product-editor.md), [판매 주문](sales-orders.md), [테스트 기록](../testing.md)을 코드와 함께 대조한다. 기존 쿠팡 테스트 결과를 롯데ON 검증으로 간주하지 않는다.
- 제공된 SOURCE·원문·검수 자료는 보존한다. 제어문자를 처리할 필요가 있으면 소비용 사본에서 처리한다. 자격 증명은 기존 런타임 설정으로 연결하며 비밀 파일을 직접 열거나 인증값을 기록하지 않는다.
