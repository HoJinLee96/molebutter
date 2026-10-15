# 롯데ON·ESM 상품 연결 구현 현황

2026-10-10 기반 기록. 현재 롯데ON 구현은 [롯데ON 상품 연동](lotteon.md), 11번가는 [11번가 안내](11st.md)를 따른다. 아래 비활성 설명과 38개 검증 결과는 당시 기반 구현 기록이다. ESM은 여전히 상품 전송 비활성이다.

2026-10-11에 11번가 판매자 API 문서가 추가되었다. 이 문서의 ESM·롯데ON 매핑 기반에 11번가 구현이 포함된 것은 아니다. 11번가 XML·인증·이미지·수정 계약과 미구현 범위는 [11번가 안내](11st.md)를 따른다. `ELEVENST`의 기능 표시나 외부 전송을 문서 갱신만으로 활성화하지 않는다.

## 2026-10-10 기반 구현 당시 상태

**상품 목록·상세 API, 등록·수정 화면과 실행 경로는 아직 연결하지 않았다.**
`MarketplaceChannels`의 롯데ON·G마켓·옥션 상품 읽기·쓰기 지원 표시는 그대로 비활성이다.
이번에 추가한 매핑 클래스는 Spring 서비스가 아니며 네트워크 요청을 하지 않는다.
기존 쿠팡·스마트스토어 화면은 유지한다. 전송 어댑터는 검사·응답 판정의 내부 역할만 분리했다.

완료한 기반:

- `molebutter-marketplace-esm` 모듈을 분리하고 app 의존성·모듈 경계 검사에 포함.
- 공개 계약을 `molebutter-marketplace-contracts`로 분리하고 네 공급자의 공통 저장 구현 의존을 제거.
- 상품 조회·선택값 조회·입력 변환 포트를 분리. 상품 매핑과 ESM 인증은 각각 내부 product/auth 영역에서 관리.
- ESM HS256 인증 구성. `kid=마스터 ID`, `iss=명시한 발행자`, `sub=sell`,
  `aud=sa.esmplus.com`, `ssi=A:옥션 판매자,G:G마켓 판매자`.
  기존 `esm_plus_api_key`를 비밀키나 JWT로 해석하지 않는다.
- 공통 초안의 ESM·롯데ON 설정에 전용 입력과 상속 상태를 보관할 필드 추가.
  기존 생성자와 JSON의 필드 생략을 허용한다.
- 전용 입력 허용 목록, 옵션 UUID, 이미지 자산 참조, 변경 비교와 외부 변경 충돌 비교.
- 롯데ON 단품 UUID↔서버 단품번호 매핑, 기존 옵션명·옵션값 변경·삭제 거절,
  새 단품에는 기존 ID를 넣지 않는 요청 구성.
- 롯데ON 기존 옵션별 이미지와 관련 없는 설명·미편집 항목 보존.
- ESM 일반 무옵션 상품의 공동 등록 전문 구성, 대소문자와 `addtional` 철자 보존,
  정상 판매가 범위·10원 단위·기본 재고 1~99999 검사.
- ESM 수정 불가 상품명, 신규 요청의 구형 전환용 `siteGoodsNo`와 판매상태 제외.

ESM 옵션·인증, 개별 배송비·직접 배송, 롯데ON 중고·특수 상품은 현재 매핑에서
명시적으로 거절한다. 이 범위를 지원한다고 안내하거나 전송 버튼을 활성화하면 안 된다.

## 설정

일반 `application.properties`가 아래 이름을 기존 비밀 설정에서 읽는다.
비밀 파일은 이번 작업에서 열거나 수정하지 않았다.

```
esm_plus_secret_key=
esm_plus_master_id=
esm_plus_issuer=
esm_plus_gmarket_seller_id=
esm_plus_auction_seller_id=
```

issuer는 임의 기본값을 정하지 않는다. 실제 계약에 맞는 값이 필요하다.
인증 구성과 모의 서명 검증은 실제 계정 권한 확인을 뜻하지 않는다.

## 남은 연결 작업

1. 계정 단위 호출 간격·목록 조회 제한·429 처리를 공유하는 읽기/쓰기 전송 계층.
2. 롯데ON Identity·코드·카테고리·속성·주소록·정책과 ESM 사이트별 메타데이터 조회.
3. ESM 선택형·조합형의 추천 코드/값 매핑, 인증 및 배송 방식별 계약 검증.
4. 상품 목록·상세와 서버 소유 관찰 토큰, 안정적인 옵션 연결·상속 규칙.
5. 전용 등록/수정 화면과 공통 편집 탭의 동일 폼 재사용.
6. 초안 revision·자산 소유권·공개 주소 변환, 준비/실행 직전 외부 충돌 검사.
7. 공동 ESM 요청의 한 번 실행과 사이트별 ID 연결·마스터 단위 중복 보호.
8. 기존 MarketplaceSubmissions를 통한 순차 전송·응답 유실 확인·승인 대기 확인.
9. 격리 DB 및 PC/모바일의 입력부터 결과 재조회까지 검증.

공통 초안의 기존 옵션 수 제한(200)과 공급자 폼의 옵션 한도(500)도 실제 연결 단계에서
호환성 검증과 함께 조정해야 한다. 현재 기반만으로 500개 옵션 저장 지원을 주장하지 않는다.

## 근거

- [ESM 인증 SOURCE](../../marketplace-api-docs/esm/guide/API-가이드.md)
- [ESM 상품 SOURCE](../../marketplace-api-docs/esm/api/상품API/상품관리-API/020-상품등록-수정-전환-조회-API.md)
- [롯데ON 상품 등록 SOURCE](../../marketplace-api-docs/lotteon/api/상품/87-상품-등록.md)
- [롯데ON 승인 상품 수정 SOURCE](../../marketplace-api-docs/lotteon/api/상품/90-승인-상품-수정.md)
- [롯데ON 상품 상세 SOURCE](../../marketplace-api-docs/lotteon/api/상품/94-상품-상세조회.md)
- 롯데ON FAQ 127·128·130: 기존 옵션 수정 제한·중복 옵션명·승인과 노출 구분.

검증은 가짜 키와 서버 응답 JSON으로 수행한다. 실제 마켓 조회·등록·수정, R2 쓰기,
운영 DB 변경, 서버 재시작은 수행하지 않는다.

## 이번 검증 결과

격리 출력 경로 `target/partner-integration`에서 다음 38개 테스트를 통과했다.

- PartnerFormSupportTest 7: 명시적 빈값·0, 외부 식별자 허용 목록 거절,
  UUID·blob 검사, 외부 변경 충돌, 잘못된 변경 경로·중첩 입력, 기존 생성자 호환성.
- LotteonProductMappingTest 4: UUID 기준 재정렬, 기존 옵션별 이미지·미편집값 보존,
  기존 옵션명 변경·삭제 거절, 새 단품 ID 제외, 중복 단품번호 거절.
- EsmCredentialsTest 4: 모의 HS256 서명, 공동 사이트 claim, 자격 증명 누락·구분자 주입 거절,
  사이트 공유 계정 범위와 키 교체.
- EsmProductMappingTest 4: 공동 등록 요청 구성, 가격·기본 재고 제약,
  안정적인 기본 옵션 UUID, 상품명 수정 제한, 다른 사이트 판매 상태·미편집 필드 보존.
- DraftValidationTest 17, ModuleArchitectureTest 2: 기존 검증·모듈 경계 회귀.

실행 명령:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
JAVA_TOOL_OPTIONS=-javaagent:/Users/lee/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar \
./mvnw -o -pl molebutter-app -am test \
-Dtest=ModuleArchitectureTest,PartnerFormSupportTest,LotteonProductMappingTest,EsmCredentialsTest,EsmProductMappingTest,DraftValidationTest \
-Dsurefire.failIfNoSpecifiedTests=false \
-Dmolebutter.build-directory=target/partner-integration
```

현재 테스트는 매핑·검증·서명·모듈 경계만 확인한다. 실제 HTTP 응답, 실행 기록,
응답 유실 복구, 계정 잠금, 이미지 소유권 통합, DB 마이그레이션과 PC/모바일은
이번 신규 기능의 검증 완료 범위에 포함하지 않는다.
