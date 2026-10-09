# 판매 마켓 Open API 연동 조사

> 문서 유형: 외부 API 조사·후속 연동 제안과 현재 구현 상태. 외부 사양은 조사 당시 기준이며 실제 계정 호출 여부와 결과는 현재 구현 상태 문단 및 마켓별 검증 기록으로 구분한다. 구현 전 최신 공식 문서와 계정 권한을 다시 확인한다. 현재 코드 경계는 [모듈 구조](../modular-architecture.md)를 따른다.

최초 조사일: 2026-10-03. 대상은 **내 판매자 계정에 등록한 상품**이다. 당시 네이버 스마트스토어·쿠팡 Wing·G마켓·옥션으로 시작했으며, 2026-10-07 롯데ON을 포함한 문서 참조 기준을 반영했다. 마켓별 최초 상품 조회에서는 HTTP 성공과 업무 성공·정상 응답을 함께 확인한다.

## 문서와 조사 범위

판매 마켓 작업의 참조 기준은 프로젝트의 최신 [marketplace-api-docs](../../marketplace-api-docs/README.md)다.
2026-10-07 제공된 롯데ON 포함 업데이트의 읽기 규칙과 검수 현황을 확인했다. 원문 수집일은 문서의 `fetched_at`을 따르며 전달일과 구분한다. 롯데ON 281개 문서의 노트 검수 완료는 제공된 README의 기록이며 실제 계정 검증을 뜻하지 않는다.
에이전트의 지속적인 참고 순서는 [프로젝트 AGENTS.md](../../AGENTS.md)에 기록한다.

README → 마켓별 OVERVIEW·INDEX → 대상 문서의 SOURCE 순서로 찾는다. 공통 설계에서는 `cross-market/`도 읽고,
네이버 하위 구조체, INDEX의 관련 FAQ·공지와 해당 마켓의 `KNOWN-ISSUES.md`를 확인한다. ESM은 `endpoints`와 '엔드포인트' 열로 찾는다. 불일치는 `_raw/`와 최신 공식 문서로 대조한다.
개요·비교·노트의 검수 완료를 실계정 동작 검증으로 해석하지 않는다.

| 문서 | 내용 |
|---|---|
| [네이버 스마트스토어](naver-smartstore.md) | OAuth, 상품 목록·상세, 전용 신규·수정 화면과 초안·전송·결과 확인 |
| [쿠팡 Wing](coupang-wing.md) | 키·IP 등록, HMAC, 상품 목록·상세, 문서 불일치와 확인 방법 |
| [G마켓·옥션 / ESM](esm-gmarket-auction.md) | 사용 승인, 공통 JWT, 사이트별 상품 조회, 마스터·사이트·옵션 구분 |
| [롯데ON](lotteon.md) | 최신 문서 참조, 셀러용 범위·호스트·필수 표기·응답 차이와 후속 구현 준비 |
| [첫 조회 구현·검증 순서](first-product-query.md) | 프로젝트 적용 경계, 필요한 설정, 요청·응답 판정과 완료 조건 |

최초 조사에서는 인증·사용 신청·상품 목록·상세·옵션·조회 제한·관련 공지와 FAQ를 읽고 대조했다. 전체 API의 주문·배송·반품·정산·문의 기능은 후속 연동의 영역으로 분류했다. 모든 도메인의 모든 요청 필드까지 검증한 문서는 아니다.

최초 조사 당시 제공된 네이버 파일은 endpoint별 공식 Markdown 문서의 **목차**였다. 현재 참조 자료는 위 프로젝트 경로의 SOURCE·원문·검수 기록을 포함한 패키지다.

2026-10-03 최초 조사의 상태는 **문서 조사 완료 / 실제 계정 호출 미실행**이었다. 이후 구현과 실제 호출 결과는 아래 날짜별 구현 상태 및 마켓별 기록을 따른다. 2026-10-07 롯데ON 문서 반영 작업에서는 서비스 코드·DB·판매 상품·재고를 변경하거나 실제 API를 호출하지 않았다.

## 다섯 마켓 비교

| 마켓 | 인증 | 첫 상품 목록 조회 | 준비 |
|---|---|---|---|
| 네이버 스마트스토어 | bcrypt 전자서명으로 OAuth 토큰 발급 → Bearer | `POST https://api.commerce.naver.com/external/v1/products/search` | 앱 ID·시크릿, 상품 API 그룹, 허용 IP |
| 쿠팡 Wing | 요청마다 HMAC-SHA256 → CEA 헤더 | `GET https://api-gateway.coupang.com/v2/providers/seller_api/apis/api/v1/marketplace/seller-products` | vendorId·Access Key·Secret Key, 허용 IP |
| G마켓 | ESM HS256 JWT → Bearer | `POST https://sa2.esmplus.com/item/v1/goods/search` | ESM 승인·키·마스터 ID, G마켓 판매자 ID·허용 IP |
| 옥션 | ESM HS256 JWT → Bearer | 위와 동일, 옥션 조건으로 별도 호출 | ESM 승인·키·마스터 ID, 옥션 판매자 ID·허용 IP |
| 롯데ON | 판매자 인증키 → Bearer, 등록된 출발지 IP 검증 | `POST https://openapi.lotteon.com/v1/openapi/product/v1/product/list` | 판매자 키·등록 IP, 해당 API의 거래처 식별자·조회 조건 |

규격 근거: [네이버 인증](https://apicenter.commerce.naver.com/docs/auth), [상품 목록](https://apicenter.commerce.naver.com/docs/commerce-api/current/search-product), [쿠팡 HMAC](https://developers.coupang.com/ko/getting-started/creating-hmac-signature), [상품 목록](https://developers.coupang.com/ko/api/products/product-list-paging-query), [ESM 가이드](https://etapi.gmarket.com/pages/API-%EA%B0%80%EC%9D%B4%EB%93%9C), [상품 목록](https://etapi.gmarket.com/160).

롯데ON 근거는 [개발가이드 SOURCE](../../marketplace-api-docs/lotteon/guide/시작하기-API-개발가이드.md)와 [상품 목록 SOURCE](../../marketplace-api-docs/lotteon/api/상품/93-상품-목록-조회.md)다. 호스트·응답·호출 제한의 예외와 불일치는 [롯데ON 안내](lotteon.md)를 따른다.

G마켓과 옥션은 공통 클라이언트를 사용할 수 있지만, 한쪽의 성공이 다른 쪽의 계정·상품 접근 성공을 보장하지 않는다. 완료 기록은 다섯 마켓별로 남긴다.

## 프로젝트에 적용할 원칙

### 통합 인증 설정 (2026-10-09)

판매 마켓 인증값은 `application-secret/maketplace.properties` 한 파일에 보관한다. 파일명의 `maketplace` 표기는 현재 사용자 파일명과 일치시킨다. 로컬 classpath와 실행 디렉터리의 외부 파일을 선택적으로 읽으며, 기존 `coupang.properties`·`naver-commerce.properties` 자동 import는 제거했다. 비밀 파일은 Git·배포 JAR에 포함하지 않는다.

| 통합 파일 키 | 서버 내부 설정 |
|---|---|
| `coupang_access_id` | `marketplace.coupang.vendor-id` |
| `coupang_access_key` | `marketplace.coupang.access-key` |
| `coupang_secret_key` | `marketplace.coupang.secret-key` |
| `naver_smart_store_account_id` | `marketplace.naver.account-id` |
| `naver_smart_store_client_id` | `marketplace.naver.client-id` |
| `naver_smart_store_client_secret` | `marketplace.naver.client-secret` |
| `11st_api_key` | `marketplace.elevenst.api-key` |
| `esm_plus_api_key` | `marketplace.esm.api-key` |
| `lotteon_api_key` | `marketplace.lotteon.api-key` |

직접 지정한 `marketplace.*` 설정은 Spring의 기존 우선순위를 따른다. 네이버는 기존 `NAVER_COMMERCE_*` 환경 변수가 있으면 새 파일 키보다 우선하며 인증 유형 기본값 `SELF`를 유지한다. 11번가·ESM·롯데ON은 설정 연결만 준비한 상태이고 키 입력만으로 외부 API나 등록 기능을 활성화하지 않는다. 네이버 공개 검색·매입처 로그인 및 Cloudflare 설정은 이 통합 대상과 별개다.

현재 코드의 책임 분리와 보안 보완은 [연동 전 기반 정리](../market-integration-readiness.md)에 기록한다. 아래 원칙은 최초 단계의 설계 제안이며 실제 구현·호출 범위는 날짜별 기록을 따른다.

현재 상품 기준은 `catalog_product`, 매입처 가격·재고 관측은 `product_supplier`와 조회 이력, 실제 보유 재고는 `inventory_item`·`inventory_movement`다. 관련 기준은 [상품 조회](../product-lookup.md), [보유 재고](../inventory.md)에 있다.

판매 마켓 연동은 새로운 외부 데이터 출처다. 다음 경계를 권한다.

- 판매자 계정의 상품 조회와 기존 네이버 공개 검색·매입처 옵션 조회를 별도 작업으로 실행한다.
- 첫 단계는 인증과 읽기 요청만 구현한다. 판매가·판매 수량 수정, 주문 수집, 자동 출고는 후속 기능이다.
- 마켓 상품의 식별자는 `마켓 + 판매자 연결 + ID 종류 + 외부 ID`로 구분한다. 문자열로 다루고 내부 상품 ID와 혼용하지 않는다.
- 판매자 관리 코드·상품명·색상·사이즈는 연결 후보 자료로 사용한다. 같은 문자열만으로 자동 병합하지 않는다.
- 같은 내부 상품에 여러 마켓 판매글·옵션이 연결될 수 있다. 판매 마켓의 그룹 구조를 내부 상품 그룹 기준으로 가져오지 않는다.
- 마켓의 판매 가능 수량은 내가 가진 물건의 수량과 다른 관측값이다. 조회 응답으로 입고·출고 이력을 만들거나 실제 보유 수량을 덮어쓰지 않는다.
- API 연결 설정과 키 관리는 ADMIN 업무로 제안한다. 일반 상품 조회 권한은 구현 단계에서 정하고, 키·토큰·원본 인증 헤더를 클라이언트 응답에 포함하지 않는다.

이 내용은 **프로젝트 적용 제안**이며 새 엔티티·테이블·권한이 구현되었다는 의미가 아니다. 첫 HTTP 200을 위해 대규모 판매채널 모델이나 재고 배분 기능을 먼저 만들 필요는 없다.

## 확인된 차이와 남은 확인

| 대상 | 문서에서 확인한 점 | 구현 때 결정할 기준 |
|---|---|---|
| 네이버 | Markdown 토큰 예시와 웹 상세의 전송 형식이 다름 | 웹 상세의 폼 전송 적용. 요약 예시를 복사하지 않음 |
| 쿠팡 | 메인 페이지 예시 경로, 성공 코드 오타, 요청/응답 토큰 타입, 상태값 예시 차이 | 상품 endpoint 상세 기준으로 시작하고 실제 응답으로 보완 |
| ESM | JWT 설명의 키 용어, `aud`와 호출 호스트, 검색 필드 타입·페이지 예시 차이 | 각 항목은 ESM 문서에 근거와 미확인점을 기록 |
| 롯데ON | 가이드·FAQ의 호출 제한 단위 충돌, API별 호스트·필수 표기·응답 차이 | 대상 SOURCE·관련 FAQ·공지·원문 표로 확인하고 미해결 계약은 확정하지 않음 |
| 공통 | HTTP 성공과 업무 성공은 따로 판정해야 함 | HTTP 200, 정상 JSON 구조, 계정·사이트, 업무 오류를 함께 확인 |

쿠팡 문서의 문제를 전제로 모든 예시를 불신하지 않는다. 서로 일치하는 규격은 사용하고, 불일치 부분만 실제 호출·공식 지원 확인 대상으로 남긴다. 네이버와 ESM에도 같은 원칙을 적용한다.

## 진행 순서

1. ESM의 사용 승인 여부부터 확인한다. 승인과 API별 권한 부여가 필요하므로 개발 전에 준비한다.
2. 네이버 자기 계정 조회를 구현해 인증 → 작은 상품 목록 → 상세 1건을 확인한다.
3. 쿠팡은 작은 목록으로 서명·IP·응답 규격을 확인한다.
4. ESM 공통 인증 구현 후 G마켓과 옥션을 사이트별로 각각 확인한다.
5. 롯데ON은 새 문서의 Identity·상품 목록·상세 계약을 대조한 뒤 읽기 어댑터를 구현하고 계정별로 검증한다.
6. 다섯 마켓의 호출 증거가 생기면 상품 연결·목록 저장·주기적 동기화 범위를 다음 단계로 정한다.

현재 키가 이미 준비된 마켓이 있다면 2~5의 순서는 바꿀 수 있다. 계정 정보는 채팅이나 이 문서에 붙이지 않고 서버 설정 경로로 전달한다.


## 현재 구현 상태 (2026-10-06)

쿠팡 등록상품 조건 검색·상세 탭·이미지·옵션 현재 가격·재고 읽기를 관리자 판매 마켓 화면과 `molebutter-marketplace`에 구현했다. 실제 계정에서 HTTP 200으로 목록·다음 페이지·조건 검색·첫 상품 상세·현재 값·이미지 URL 접근을 확인했다. [쿠팡 구현 범위·실행·검증 기록](coupang-wing.md)을 따른다. 공통 초안에서 변경 미리보기·명시적 전송·실행 이력과 쿠팡 등록·상품 정보·배송·현재 가격/재고 변경 요청을 연결했다. 기존 연결 상품은 편집 세션에서 최신값을 다시 읽고 저장된 공통 참조와 분리한다. 선택한 마켓·필드만 전송 의도로 받아 실행 직전 최신 전문에 병합하며 세션·revision·기준값 변경은 이전 확인을 차단한다. 쓰기 구현은 모의·단위·통합 테스트로 검증했으며 실제 계정의 쓰기 성공과 공개 이미지 도달 가능성은 아직 확인하지 않았다. 자동 동기화와 나머지 마켓의 외부 전송은 후속 범위다.

- [공통 상품 등록·수정 페이지](common-product-editor.md): 내부 초안·마켓별 설정·원본 이미지 저장, 쿠팡 전송 실행·결과 확인.
- 작업 시 프로젝트의 [marketplace-api-docs](../../marketplace-api-docs/README.md)를 우선 참고하고 원문·노트·실제 확인을 구분한다.

## 판매 주문 구현 (2026-10-07)

롯데ON 공식 문서는 확보됐으며 [롯데ON 안내](lotteon.md)에 참조 경로를 추가했다. 문서 확보와 어댑터 구현·실계정 검증은 별개다.

[판매 주문 수집·통합 조회](sales-orders.md): 쿠팡 판매자배송 주문과 클레임을 수동 수집하고 정규화 요약을 저장한다. 배송 개인정보는 상세 조회 시에만 읽는다. 정상 6개 상태·취소·반품·교환·철회 목록·상세와 다음 페이지의 실계정 읽기를 확인했다. 네이버·G마켓·옥션·롯데ON은 준비 상태이며 외부 주문 요청을 보내지 않는다.

쿠팡 수동 주문 수집 개선: 계정별 원자적 잠금, 실패 상세만 재조회, 안전한 실패 원인과 구간 진행, 동일 클레임 날짜·유형 검색, 최신 미확인 및 과거 집계 복원 표시를 추가했다. V35와 최종 검증은 [판매 주문 기록](sales-orders.md)을 따른다.

## 모듈 적용 (2026-10-09)

쿠팡·스마트스토어 외부 어댑터를 `molebutter-marketplace-coupang`·`molebutter-marketplace-naver`로 분리했다. 공통 초안·실행·주문 이력은 marketplace, 원본 자산·발행·참조는 media가 소유한다. [현재 구조](../modular-architecture.md)를 따른다. 여섯 채널 목록은 `MarketplaceChannels`에서 관리하며 G마켓·옥션의 공급자는 ESM, 롯데ON·11번가는 준비 상태다. 원문이 확보되지 않은 11번가 규격을 추정해 구현하지 않는다.
