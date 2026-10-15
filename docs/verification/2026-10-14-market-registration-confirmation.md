# 마켓 신규 등록 결과 확인 분리

2026-10-14. 쿠팡·스마트스토어·롯데ON·11번가의 신규 등록 확인을 검토했다. ESM은 기존 범위대로 제외한다. 실제 마켓 상품 등록·수정·이미지 업로드와 운영 DB 변경·서버 재시작은 수행하지 않았다.

## 실제 원인과 수정 대조

쿠팡 실행 `898114170316135292`, 공통 초안 `897779631605467192` revision 7, 등록상품 `16414267189`를 읽기 전용 SELECT와 인증 GET으로 확인했다. 상품은 `승인완료`이고 옵션 1개의 `sellerProductItemId`·`vendorItemId`도 발급되어 있었다. 기존 코드는 다음 차이 때문에 `createApplied=false`로 판단하여 결과 확인을 반복해도 ACCEPTED로 돌아갔다.

- 판매 시작일: 요청 `2026-10-13T18:32:58`, 조회 `2026-10-14T16:47:46`.
- 판매 종료일: 요청 `2099-12-31T23:59:00`, 조회 `2099-12-31T00:00:00`.
- 대표이미지의 원본 URL과 조회 `vendorPath`/`cdnPath`가 다름.
- 상세설명 이미지 주소가 다름.

수정된 실제 어댑터의 `reconcile`에 같은 실행 스냅샷과 새 인증 GET을 대조했다. `CONFIRMED / CREATED_VALUES_DIFFER`, 상품 ID 동일, 옵션 연결 1개, 최초 전송 시각 보존을 확인했다. 이는 메모리에서 판정한 결과이며 운영 실행 상태를 직접 완료로 바꾸지 않았다. 서버 재시작 후 기존 실행의 **결과 확인**으로 적용한다. 같은 CREATE를 다시 보내지 않는다.

## 공통 정책과 마켓별 적용

신규 등록 성공 응답에서 받은 상품 ID에 대한 인증 조회, 현재 상품/옵션 식별자, 마켓의 승인·판매 상태를 함께 확인한다. 과거 등록 전문과 현재 값이 완전히 같은지는 등록 완료의 필수 조건으로 사용하지 않는다. 확인되지 않은 상품 ID를 판매자 코드나 상품명 검색으로 대신 연결하지 않는다.

공용 `MarketplaceCreationConfirmation.completeOptions`는 모든 기대 옵션 UUID가 정확히 한 번 대응하는지, 외부 옵션 ID가 누락·중복되지 않는지 검사한다. 쿠팡 승인완료에는 `vendorItemId`까지 요구한다. 각 어댑터가 실제 응답의 ID·개수를 확인한 뒤 사용하며 이 검사만으로 계정 소유권이나 승인을 가정하지 않는다.

| 마켓 | 신규 등록 확인 | 계속 확인이 필요한 경우 |
|---|---|---|
| 쿠팡 | 상품 ID·계정·옵션 대응과 승인완료 또는 승인 미요청 임시저장을 확인. 값 차이는 `CREATED_VALUES_DIFFER`로 별도 안내 | 승인 대기·부분 승인·반려, 알 수 없는 상태, 옵션 누락·중복·기존 ID 변경 |
| 스마트스토어 | 원상품/채널 관계와 조합 옵션 ID·개수, `SALE`/`OUTOFSTOCK`/`SUSPENSION`/`CLOSE`를 확인. 선택 변경을 적용한 옵션 UUID로 검사 | `WAIT`/`UNADMISSION`/`REJECTION`/금지·삭제·미확인 상태, 옵션 대응 누락, 조회 미확인 |
| 롯데ON | 응답 상품번호와 단품 대응, `fnlAprvYn=Y` 확인. 편집 지원 여부를 승인 여부와 분리 | 최종 승인 N/누락, 반려, 단품 대응 누락·중복. UPDATE 미지원 유형은 승인 대기로 표현하지 않음 |
| 11번가 | 응답 상품번호와 재고번호 대응, `103`/`104`/`105`/`106` 확인. 한번 확인한 재고번호를 우선하여 판매자 코드 변경에도 연결 유지 | `101`/`102`, 미확인·금지·강제종료 상태, 옵션 누락·추가·중복 |

공통 실행 기록은 **등록·옵션 연결 확인 완료**, **현재 상품값이 등록 요청과 다름**, **승인 대기**, **승인 반려**, **옵션 연결 미확인**, **결과 조회 실패**를 구분한다. 공통 기준을 외부 값으로 덮어쓰지 않으며 상품 수정/재진입은 기존 최신 조회 흐름을 사용한다.

기존 상품 UPDATE는 선택한 변경값·전용 가격/재고 반영을 계속 검사한다. 등록 확인 정책으로 UPDATE를 자동 성공시키지 않는다. 11번가 수정 이미지가 변환되어 비교되지 않는 경우의 `IMAGE_REVIEW_REQUIRED`와 응답 유실의 무재전송 정책도 유지한다.

## 근거

- [쿠팡 상품 조회 SOURCE](../../marketplace-api-docs/coupang/api/products/querying-product.md): 상태, 옵션 식별자, CDN 경로, 속성 보강, 등록 직후 조회 지연. [ID 정의 FAQ](../../marketplace-api-docs/coupang/faq/products/i-dont-understand-what-each-id-means.md).
- [네이버 원상품 조회 SOURCE](../../marketplace-api-docs/naver/api/product/get-v2-products-origin-products-originProductNo.md), [등록 응답](../../marketplace-api-docs/naver/schemas/상품-등록-수정-응답.md), [원상품 상태](../../marketplace-api-docs/naver/schemas/원상품.md).
- [롯데ON 상품 등록 SOURCE](../../marketplace-api-docs/lotteon/api/상품/87-상품-등록.md), [상품 상세조회](../../marketplace-api-docs/lotteon/api/상품/94-상품-상세조회.md): 상품/단품 번호, 처리 코드, 최종 승인 정보.
- [11번가 등록 SOURCE](../../marketplace-api-docs/11st/api/상품/상품관리/1003-상품등록-신규상품등록.md), [신규상품 조회](../../marketplace-api-docs/11st/api/상품/상품조회/1620-신규상품조회.md), [재고 조회](../../marketplace-api-docs/11st/api/상품/재고처리/1623-신규상품재고정보조회.md). 수정 성공 응답과 실제 반영이 다를 수 있다는 [FAQ](../../marketplace-api-docs/11st/faq/개발가이드/018-상품-수정-후-정상처리되었다는-메시지를-확인했지만-수정한-값이-반영되지-않았어요.md) 때문에 UPDATE 확인은 유지한다.

## 검증

- 단위/어댑터 203개 통과: CoupangWriteGatewayTest 67, NaverWriteGatewayTest 52, LotteonProductMappingTest 24, ElevenstIntegrationTest 23, SubmissionExecutionTest 30, NaverSubmissionRoutingTest 7.
- 격리 MySQL·Redis 통합 51개 통과: MarketplaceSubmissionFlowIT 33, NaverProductFlowIT 18. ACCEPTED → QUEUED → SUCCEEDED, 값 차이 안내, DB 연결과 완료 revision, 공통 기준 보존, 외부 최신값 재진입, CREATE 한 번만 전송을 검사했다.
- 모듈 경계 4개 통과. 모의 Chrome 1440/390px에서 등록값 차이 안내, 성공 후 결과 확인 버튼 해제, 추가 실행 없음과 가로 넘침 없음을 확인했다. 최신 실행 JAR 패키징도 통과했다. JAR의 네 마켓 어댑터 포함과 비밀 설정 파일 제외를 확인했다.
- 실제 계정은 쿠팡 인증 GET과 운영 DB SELECT만 실행했다. 다른 마켓의 신규 쓰기 검증은 모의 API로 수행했으며 실계정 등록·수정 성공을 뜻하지 않는다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q -pl molebutter-app -am '-Dtest=CoupangWriteGatewayTest,NaverWriteGatewayTest,LotteonProductMappingTest,ElevenstIntegrationTest,SubmissionExecutionTest,NaverSubmissionRoutingTest' -Dsurefire.failIfNoSpecifiedTests=false test
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home MOLEBUTTER_COUPANG_LIVE=false MOLEBUTTER_ORDER_LIVE=false CLOUDFLARE_R2_ENABLED=false bash scripts/test-integration.sh -pl molebutter-app -am -Dtest=MarketplaceSubmissionFlowIT,NaverProductFlowIT -Dsurefire.failIfNoSpecifiedTests=false
NODE_PATH=/Users/lee/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules /Users/lee/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node scripts/test-marketplace-submissions.cjs
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -q -pl molebutter-app -am -Dtest=ModuleArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false package
```
