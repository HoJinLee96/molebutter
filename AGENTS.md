# 프로젝트 에이전트 지침

## 판매 마켓 API 문서

판매 마켓의 설계·분석·구현·수정 작업은 항상 프로젝트 안의 최신
[`marketplace-api-docs/README.md`](marketplace-api-docs/README.md)를 먼저 확인한다.
기준 경로는 `/Users/lee/workspace/molebutter/marketplace-api-docs`이며,
이전 Downloads 사본이나 대화에서 기억한 요약을 현재 규격의 근거로 사용하지 않는다.
사용자가 문서를 업데이트했다고 알리면 관련 안내·색인·대상 문서를 다시 읽는다.

### 문서 찾기와 근거 확인

1. README의 구조·읽기 규칙·검수 현황을 확인한다.
2. 해당 마켓의 `OVERVIEW.md`로 인증·식별자·흐름을 파악한다.
   여러 마켓을 함께 다루면 `cross-market/`의 인증, 상품·가격·재고, 주문·배송·클레임 비교 자료도 읽는다.
3. 마켓의 `INDEX.md`에서 Method·Path·제목으로 대상 문서를 찾는다.
   ESM은 별도 Method·Path 열 대신 '엔드포인트' 열과 front matter의 `endpoints`를 사용한다.
   `referenced_endpoints`는 참조 경로이며 대상 API의 호출 주소로 간주하지 않는다.
4. 대상 문서 상단의 LLM 노트로 주의점을 파악한 뒤, 정확한 필드·타입·필수 조건·enum·제약은
   `SOURCE:BEGIN`부터 `SOURCE:END`까지의 공식 원문 변환본에서 확인한다.
5. 네이버의 `↳ 하위 구조` 참조는 `naver/schemas/`의 해당 구조체를 이어서 확인한다.
   대상 API를 정한 뒤 INDEX의 같은 주제 FAQ·공지도 찾아 SOURCE를 읽는다.
   API 노트의 관련 링크나 OVERVIEW만으로 FAQ의 동작 변경·제약을 모두 확인했다고 간주하지 않는다.
6. 관련 `KNOWN-ISSUES.md`에서 표·예시·문서 간 불일치를 확인하고, 모호한 항목은 `_raw/` 원문과 대조한다.
   현재 적용 여부가 중요하거나 충돌이 남으면 최신 공식 문서와 필요한 실제 호출로 확인한다.

### 신뢰 수준과 마켓 구분

- `OVERVIEW.md`, `cross-market/`, `_notes/`, `KNOWN-ISSUES.md`는 LLM이 작성한 길잡이다.
  그 안의 주장과 마켓 간 대응 관계는 연결된 SOURCE에서 확인하며, 추정을 확정 규격으로 바꾸지 않는다.
- `review: llm-notes-verified`는 노트의 독립 검수를 뜻한다. 실제 API 호출 성공이나 계정 권한 확인을 뜻하지 않는다.
  `llm-notes-unverified`, `source-only`도 구분한다.
- 문서 전달·갱신일과 `fetched_at`의 원문 수집일을 구분한다. 문서의 기계 검증·LLM 검수·실계정 검증을 각각 기록한다.
- 네이버는 `naver/`, 쿠팡은 `coupang/`, G마켓·옥션은 공용 `esm/`, 롯데ON은 `lotteon/`을 참고한다.
  ESM의 공용 API와 사이트별 필드·계정·상품번호 차이를 구분한다.
- 롯데ON 자료는 제공된 README 기준 셀러용(SL) 범위이며 V2 등 하위 버전은 별도 문서로 확인한다.
  운영자용(OP)·계열사 전용 조건이나 다른 마켓의 규격을 일반 셀러 계약에 대신 적용하지 않는다.
- 공통 입력값이 같아도 마켓별 상품·옵션 ID, 수량 범위, 필수값, 등록·수정 제한과 응답 의미가 같다고 가정하지 않는다.

### 롯데ON 문서 적용 시 주의

- 호출 Method·전체 URL은 대상 SOURCE에서 확인한다. 상품속성 조회의 별도 호스트와
  전시 257의 test 호스트 등 예외를 공통 `base_url`로 덮어쓰지 않는다. 운영 주소가 불명확하면 확인 대상으로 남긴다.
- 필수 표기의 `X`·`Δ`와 판촉 `구분:C|CU|UD|CUD`는 범례가 없는 부분을 임의 해석하지 않는다.
  필드 설명·관련 FAQ·조건을 함께 확인한다. `ㄴ` 접두어·병합 셀·평면 불릿의 계층은
  `_raw/lotteon/api_html/`의 원문 표와 대조한다. `_outline/`은 여백이 사라져 계층 판단 근거로 쓰지 않는다.
- HTTP POST에도 조회 API가 있다. 읽기·쓰기 구분, 성공 코드와 응답 구조는 대상 API별로 판정하고
  공통 응답 모델을 모든 도메인에 강제하지 않는다. 문서에 남은 중단 API도 현재 사용 가능하다고 가정하지 않는다.
- 호출 제한 등 가이드·FAQ·표·예시의 충돌은 기록하고 임의로 한쪽을 확정하지 않는다.
  원문에 남은 비가시 제어문자는 소비용 사본의 파싱·예시에서 처리하며 SOURCE·원문을 수정하지 않는다.

### 프로젝트 적용

- 현재 구현 범위는 [`docs/market-api/README.md`](docs/market-api/README.md),
  [`common-product-editor.md`](docs/market-api/common-product-editor.md),
  해당 마켓 안내와 [`docs/testing.md`](docs/testing.md)를 코드와 함께 확인한다.
  롯데ON의 문서 참조·후속 구현 안내는 [`docs/market-api/lotteon.md`](docs/market-api/lotteon.md)를 따른다.
- 문서 갱신만으로 기존 코드·검증 결과를 새 규격에 적합하다고 간주하지 않는다.
  관련 계약을 대조하고 확인된 차이만 구현·검증 기록에 반영한다.
- 사용자가 제공한 `marketplace-api-docs`의 SOURCE·원문·검수 자료를 앱 구현 작업 중 임의로 수정하거나 수집·빌드 스크립트로 재생성하지 않는다.
  프로젝트의 지침·구현 안내는 `AGENTS.md`와 `docs/`에서 관리한다.
- `**/application-secret/**`의 비밀 파일은 직접 열거나 수정하지 않는다.
  자격 증명은 애플리케이션 실행 시 기존 설정에서 읽도록 연결하고, 키·서명·Authorization을 로그·응답·테스트 자료에 복사하지 않는다.

지침 갱신: 2026-10-07. API 규격의 날짜는 각 원문 문서에서 확인한다.
