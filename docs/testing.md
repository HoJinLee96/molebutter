# 테스트 실행과 유지 기준

2026-10-10 PR #32 네이버 수정 회귀 검증: 재고 0 전송의 최종 `OUTOFSTOCK` 기대값, 실행 직전 재고 변경과 실제 요청 저장, 과거 미확인 요청의 읽기 확인, 채널번호 없는 기존 상품의 원상품·채널 동시 변경과 외부 충돌, 출시일 최초 입력 및 기존 날짜 수정·삭제 제한을 검증했다. `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw -o -pl molebutter-app -am test '-Dtest=NaverWriteGatewayTest,NaverGatewayTest,NaverEditPatchTest,NaverProductDocumentsTest,NaverWorkflowServicesTest,NaverProductControllerTest,NaverSubmissionRoutingTest,MarketplaceSnapshotCompatibilityTest' -Dsurefire.failIfNoSpecifiedTests=false -Dmolebutter.build-directory=target/pr32-naver-fixes`로 72개가 통과했다. `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home MOLEBUTTER_COUPANG_LIVE=false MOLEBUTTER_ORDER_LIVE=false CLOUDFLARE_R2_ENABLED=false bash scripts/test-integration.sh -pl molebutter-app -am '-Dtest=NaverProductFlowIT,MarketplaceSubmissionFlowIT' -Dsurefire.failIfNoSpecifiedTests=false`로 격리 MySQL·Redis 통합 37개가 통과했다. 통합 검증에는 최초 가져오기→준비→실행→DB 기대값 저장→완료→다음 변경 준비가 포함된다. 외부 응답은 모의 gateway를 사용했으며 실제 마켓·R2 쓰기·운영 DB·배포는 실행하지 않았다.

2026-10-09 네이버 원상품 상세조회 식별자 보정: 공식 GET 응답에 상품번호가 없는 경우 서버가 조회 경로의 원상품 번호를 보관하도록 수정했다. 모의 응답으로 조회→전용 편집 데이터 변환, 상품별 옵션·이미지 UUID 안정성, 64비트 상품번호, 판매중지·채널 전시 상태 보존, 응답 번호 불일치 거절, 수정 PUT 식별자 제외와 기존 결과 확인을 검증했다. `NaverGatewayTest` 14개, `NaverEditPatchTest` 7개, `NaverWriteGatewayTest` 14개, `NaverProductDocumentsTest` 3개, `NaverWorkflowServicesTest` 9개, `NaverProductControllerTest` 5개(총 52개)가 통과했다. 실행은 `mvn -o -Dmolebutter.build-directory=target/naver-detail-verification -pl molebutter-app -am -Dtest=NaverGatewayTest,NaverEditPatchTest,NaverWriteGatewayTest,NaverProductDocumentsTest,NaverWorkflowServicesTest,NaverProductControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`이며 판매중지 회귀 추가 후 `NaverGatewayTest`를 재실행했다. 사용자 지정 실상품 1건도 기존 애플리케이션 설정 로더와 수정된 조회 어댑터로 GET 및 편집 데이터 변환에 성공했다. 실제 응답은 원상품 `SUSPENSION`, 채널 전시 `ON`이며 중첩 상품번호가 없었다. 비밀 파일을 직접 열거나 응답 전문을 기록하지 않았고 상품 등록·수정·이미지 업로드·운영 서버 재시작은 수행하지 않았다. 실제 브라우저 화면과 상품 저장 성공을 검증한 기록은 아니다.

2026-10-08 상품 이미지 도구 우측 제어 이동: 사이즈 입력·편집·분류·추가 버튼을 상품정보 위로, 상품정보 이미지 추가 버튼을 상품정보 제목 옆으로 이동했다. 기존 ID·JavaScript 동작을 유지했다. `LayoutViewTest` 3개와 `scripts/test-product-images-ui.cjs`의 Chrome 2560·1440·390px 검증이 통과했다. 제어의 위치·순서·가로 넘침 없음과 기존 사이즈 편집·상품정보 스냅샷 추가·정렬·휴지통·ZIP 다운로드를 모의 API로 확인했다. 공개/비공개 버킷은 프로젝트 Cloudflare 안내와 대조하고 상품 이미지 `products/{상품코드}/{UUID}.{확장자}` 경로를 문서화했다. 실제 R2 업로드·LF몰 요청·판매 마켓 쓰기는 수행하지 않았다.

2026-10-08 사이즈 PNG 파일명·R2 CRUD 인프라: 이미지 모듈 162개, 저장소 모듈 45개, 앱 HTTP·모듈 경계·템플릿 8개, 격리 MySQL·Redis 통합 3개(총 218개)가 통과했다. ZIP에서 사이즈 생성 종류를 서버 메타데이터로 구분해 `사이즈.png`, `사이즈_2.png`로 저장하며 원본·고시·혼합 순서와 이전 다운로드 입력 호환을 확인했다. R2는 조건부 생성·ETag 교체·제한된 조회 스트림·단건 삭제·페이지 목록·공개/비공개 URL·오류 비밀값 제거·없는 버킷 구분·비활성 기동을 검증했다. 실제 AWS SDK 요청도 메모리 전송기로 서명·경로 방식·Content-Length·비청크 전송·재시도 없음까지 확인했다. 앱 기동의 SDK 클라이언트 미생성과 기존 이미지 도구 권한/CSRF·마켓 관리자 경계를 확인했다.

검증 명령은 `mvn -o -pl molebutter-storage,molebutter-imaging -am test`, `mvn -o -pl molebutter-app -am -Dtest=ProductImageControllerTest,ModuleArchitectureTest,LayoutViewTest -Dsurefire.failIfNoSpecifiedTests=false test`, `MOLEBUTTER_COUPANG_LIVE=false CLOUDFLARE_R2_ENABLED=false bash scripts/test-integration.sh -pl molebutter-app -am '-Dtest=AuthenticationFlowIT#r2InfrastructureStartsDisabledWithoutACloudClient+productImageWorkspaceRequiresProductAccessAndCsrf+marketplaceRequiresCurrentAdministrator' -Dsurefire.failIfNoSpecifiedTests=false`다. 최종 `mvn -o -DskipTests package`도 통과했으며 실행 JAR의 저장소 모듈 포함과 비밀 설정·테스트 JAR 제외를 확인했다. Cloudflare 버킷·도메인은 읽기 전용으로 재조회했고 실제 객체 쓰기·LF몰/판매 마켓 요청·서버 재시작은 수행하지 않았다. 기존 파일의 R2 이전은 포함하지 않는다. [R2 설정과 CRUD 계약](r2-storage.md)을 참고한다.

2026-10-08 상품정보 이미지 편집·목록 추가: 상품정보 포함 체크박스를 PNG 스냅샷 추가 버튼으로 변경하고 최종 렌더러와 같은 카드 배치를 편집 화면에 적용했다. 하단 표기 사이즈는 사진·표본 이미지 생성과 재편집에 연결한다. 이미지 모듈 테스트 157개, 앱 HTTP 매핑·모듈 경계·실제 템플릿 테스트 8개, 임시 MySQL·Redis 권한/CSRF 및 기존 마켓 권한 통합 테스트 2개가 통과했다. 항목값 병합과 원본 보존, A/S·빈값 제외, 긴 항목 배치, 생성 이미지의 사용자 소유권·만료·불변성과 ZIP 혼합 순서, 이전 포함 옵션 호환을 확인했다. 2560·1440·390px 브라우저 모의 검증에서 긴 값 자동 높이·포커스 유지·늦은 배치 응답 무시·생성 실패 시 값 보존·처리 중 편집 잠금·표기 사이즈 재편집 보존·상품정보 추가 시 다운로드 없음·정렬/휴지통 복구 및 명시적 ZIP 다운로드를 확인했다. 최종 JAR 패키징 성공. 외부 LF몰·판매 마켓 요청 없음.

2026-10-08 상품 이미지 도구 공통 UI 적용: 별도 내부 폭·화면 높이 제한과 입력·버튼 스타일 덮어쓰기를 제거하고 공통 `panel`, `toolbar`, `field`, `btn primary`를 사용한다. LayoutViewTest 3개와 2560·1440·390px 브라우저 검증이 통과했다. 제목과 패널 정렬·전체 폭·공통 입력/버튼 규격·썸네일 두 줄·화면 높이 변경에도 유지되는 패널 높이·가로 넘침 없음 및 기존 이미지 편집/다운로드 흐름을 모의 응답으로 확인했다. 최종 JAR 패키징 성공. 외부 LF몰 요청 없음.

2026-10-08 상품 이미지 도구 통합: `molebutter-imaging`의 치수·분류·렌더·상품 조회·사용자별 조회 목록·생성 이미지 소유권·만료·ZIP 혼합 순서·외부 응답/리디렉션/픽셀 제한·캐시 동시성 테스트 141개 통과. 다른 사용자의 재조회, 접수 후 목록 변경, 원본 순번과 URL 불일치도 검증했다. 앱 HTTP 매핑 2개, 모듈 경계 2개, 실제 Thymeleaf 레이아웃 3개, 임시 MySQL·Redis의 새 메뉴 권한/CSRF 및 기존 마켓 권한 통합 테스트 2개 통과. 이미지 순서·각도 JS 16개와 공통 API/프런트 안전성 JS 16개 통과. 1440·390px 브라우저에서 정렬·휴지통 이동/복구·페이지 전환·사진/표본 사이즈 생성·인증 갱신·ZIP 다운로드를 모의 응답으로 확인했다. 모바일 편집창의 버튼 가림을 수정했다. 기존 공통 상품 편집 브라우저 검증도 1440·390px에서 통과했으며 모바일 최신값 조회 클릭은 최초 시간 초과 후 재시도에서 통과했다. 외부 LF몰 실조회와 판매 마켓 쓰기는 실행하지 않았다. [기능 안내](product-image-workspace.md)를 참고한다.

2026-10-07 롯데ON 문서 참조·에이전트 지침 갱신: [AGENTS.md](../AGENTS.md)와 [마켓 안내](market-api/README.md), [롯데ON 안내](market-api/lotteon.md), 공통 상품·판매 주문·연동 준비 문서를 최신 패키지 기준으로 수정했다. 변경 문서의 로컬 링크 존재 여부와 `git diff --check`를 확인했다. 제공된 `marketplace-api-docs` 5,737개 파일의 경로·SHA-256 목록은 작업 전후 동일하다. 문서 작업으로 Java·JS·브라우저 테스트와 실제 마켓 호출은 실행하지 않았으며, 기존 검증 기록을 새 API 규격 적합성 검증으로 재분류하지 않았다.

2026-10-07 이미지 드래그: JS 테스트 16개 및 LayoutViewTest 통과. 가짜 이미지로 2560·1440·390px 브라우저에서 대표 교체, 대표→추가 이동, 빈 대표 영역으로 이동, 추가 9개 초과 차단을 확인했다. 390px에서는 CDP 실제 터치 이벤트로 이동과 고스트 정리를 확인했다. 순서 변경·유형별 개수 제한·이동 거절 시 원본 보존·비동기 파일 참조 보존은 모델 테스트로 검증했다. 외부 쓰기 요청 없음.

2026-10-07 쿠팡 옵션명 버튼 통합: JS 14개와 LayoutViewTest 통과. 로컬 브라우저 2560·1440·390px에서 옵션 설정 제거, 이미지·설명 버튼 전환, 공통 입력의 옵션 전환 독립성, 입력 보존 및 추가 외부 호출 없음 확인. 공통값의 옵션별 적용·배열 순서 변경·미편집 차이 보존·신규 옵션 상속은 모델 테스트로 검증했다. 외부 응답은 가짜 GET 자료를 사용했다.

> 문서 유형: 현재 구현 안내. 2026-10-05 로컬 코드와 대조했다. 날짜가 붙은 적용·검증 문단은 당시 기록이며 현재 정책과 구분한다.

핵심 로직은 Java·JavaScript 테스트로 검증하고, 브라우저에서는 실제 템플릿과 화면 이벤트가 연결되는 대표 흐름만 확인한다. 기본 검증은 운영 DB·계정과 외부 쇼핑몰을 사용하지 않는다. 쿠팡 실제 읽기 호출은 명시적으로 활성화하는 별도 검증만 허용하며 [실행·실패 기록](market-api/coupang-wing.md)을 따른다.

## 유지하는 검증

| 영역 | 유지 범위 |
|---|---|
| 인증·권한 | 계정 상태, 역할, CSRF, 세션 재시도, 금액·결제 정보 비노출 |
| 재고·매입 | 수량 관계, 상품 집계, 입고·취소·반품, 정확한 금액, 동시 처리, 중복 요청, 삭제 제한 |
| 상품·외부 조회 | 상품·채널 식별, 옵션·재고 해석, 검색 실패·재시도·큐 상태, 오래된 응답 차단 |
| 데이터 | 마이그레이션, 기존 값·이력·요청 호환, 트랜잭션 롤백 |
| 화면 | 상품·설정, 매입·입고·취소, 알림의 대표 흐름을 1440px·390px에서 검증 |

화면 문구·색상 클래스·픽셀 크기·컨트롤 개수를 고정하는 테스트, 개인 자료 경로가 필요한 사례, 수동 외부 실조회와 별도 시제품 실행기는 제거했다. 링크 검증과 HTML 이스케이프는 `frontend-safety.test.cjs`에서 유지한다. 모든 모달의 세부 정렬은 자동 회귀 범위에 포함하지 않는다.

## 실행

JDK 21, Node.js, MySQL·Redis 실행 도구가 필요하다. 브라우저 검증에는 기존 Playwright 설치와 Chrome을 사용한다.

```sh
# 임시 MySQL·Redis에서 단위·통합·마이그레이션 테스트 실행.
bash scripts/test-integration.sh

# JavaScript 로직 검증.
node --test src/test/js/*.test.cjs

# 통합 테스트 실행 결과에 실제 Thymeleaf 화면(molebutter-app/target/ui-fixtures)이 생성된다.
# UI 검증만 실행할 때는 먼저 ./mvnw -Dtest=LayoutViewTest -Dsurefire.failIfNoSpecifiedTests=false test 를 실행한다.
NODE_PATH=/path/to/node_modules node scripts/test-product-ui.cjs
NODE_PATH=/path/to/node_modules node scripts/test-product-images-ui.cjs
NODE_PATH=/path/to/node_modules node scripts/test-marketplace-editor.cjs
NODE_PATH=/path/to/node_modules node scripts/test-common-marketplace-editor.cjs
NODE_PATH=/path/to/node_modules node scripts/test-marketplace-submissions.cjs
NODE_PATH=/path/to/node_modules node scripts/test-marketplace-cancellation.cjs
NODE_PATH=/path/to/node_modules node scripts/test-inventory-ui.cjs
NODE_PATH=/path/to/node_modules node scripts/test-notifications-ui.cjs
```

`test-integration.sh`는 Bash 스크립트다. 별도 임시 MySQL·Redis를 시작하고 테스트 종료 시 종료·삭제한다. 기본 실행에서 Python은 사용 가능한 포트를 찾는다. 선택적인 실행 JAR 검증을 켜면 Python 기반 기동·HTTP 검증 도구도 사용한다. Maven은 기본적으로 오프라인 캐시를 사용하며, 의존성을 처음 받아야 하면 `MOLEBUTTER_TEST_MAVEN_OFFLINE=false`를 지정한다.

브라우저는 실제 화면 코드와 로컬 모의 API를 검증한다. 모의 API의 수량 결과는 서버 동시성 검증을 대신하지 않으며, 해당 검증은 Java 통합 테스트가 담당한다. 외부 응답 자료는 `test-fixtures/product` 아래에서 관리하고 필요한 검증에서만 참조한다. 브라우저 스크린샷은 무시되는 `target/ui-check`에 생성한다. 쿠팡 편집 페이지 검증은 `/tmp/molebutter-editor-{1440,390}.png`, 공통 초안 편집은 `/tmp/molebutter-common-editor-{1440,390}.png`에 합성 데이터 화면을 기록한다.

## 마켓 연동 전 기반 정리 검증

`InventoryFlowIT`는 같은 요청의 동시 주문 생성·입고 재시도와 서로 다른 주문의 병렬 처리, 카탈로그 변경 대기, 재고 실패 알림의 역할 변경, 미등록 API의 기본 거절을 확인한다. `InventoryInputTest`는 역할별 응답 계약에서 알려지지 않은 열을 제외하는지 확인한다. `ExceptionPrivacyTest`는 거절 입력·예외 원인·쿼리 문자열이 응답과 로그에 노출되지 않고, 애플리케이션이 작성한 입력·충돌 안내의 400·409 응답은 유지되는지 확인한다.

`AuthenticationFlowIT`는 HTTP 감사의 요청 식별자·실제 계정과 백그라운드 감사의 커밋/롤백을 확인한다. `ProductFlowIT`는 SUCCESS·PARTIAL·SOLD_OUT·과거 NO_MATCH의 완료 감사와 FAILED·STALE의 실패 감사, 중복 완료 방지를 확인한다. 근태 정정 승인·취소의 알림 검증은 하나의 흐름으로 합치고, 실패 롤백·중복 요청·입력 오류 및 상품 API 권한 검증은 유지한다. `MigrationUpgradeIT`는 V26 설치에서 V27 요청 직렬화와 V28 감사 컨텍스트를 적용하고 기존 수량·단가·HTTP 감사 행이 보존되는지 검증한다. 실제 판매 마켓 계정은 이 테스트에서 호출하지 않는다.

## 다중 모듈 검증

2026-10-04 전환 당시 Java 검증 442개와 JS 검증 47개를 기준으로 기능 검증을 유지했다. 이 수치는 전환 기준점이며 현재 테스트 개수는 실행 결과로 확인한다. 단위 테스트는 소유 모듈, 전체 통합·화면 검증은 app에서 실행한다. `ModuleArchitectureTest`는 허용 의존성·공개 API 접근·업무 패키지 순환을 확인한다. `MigrationUpgradeIT`는 V28→V29→V30 이전과 활성 작업 거절, 값 불일치 시 삭제 거절, JSON null·버전·삭제/통합 상품·재고 값 보존을 함께 확인한다. 전체 구조와 운영 적용 절차는 [업무 모듈과 DB 책임](modular-architecture.md)을 따른다.

최종 JAR을 먼저 `./mvnw -DskipTests package`로 생성한 후 `MOLEBUTTER_TEST_PACKAGE_SMOKE=true bash scripts/test-integration.sh`를 실행하면 같은 임시 DB·Redis에서 실행 JAR의 HTTP 기동·자원·마이그레이션 로딩과 두 상태 복구 명령의 미리보기까지 검증한다. 비밀 설정·테스트 자료·테스트 JAR·ArchUnit이 배포 JAR에 포함되지 않았는지도 확인한다. 초기화·예약 워커와 외부 메일 발송은 실행하지 않는다.

전환 전후의 실제 실행 결과와 자료 대조는 [다중 모듈 전환 검증](modular-transition-verification.md)에 기록했다.

## 모듈 경계 보강 검증

`ProductFlowIT`는 공개 catalog 계약을 직접 호출해 코드·브랜드·중복·버전·통합 대상 검증을 확인한다. 잠금 없는 호출, 읽기 전용 변경, 공유→배타 승격, 가드 행 누락과 잘못된 DataSource를 거절하고 `REQUIRES_NEW`·롤백 후 상태가 섞이지 않는지도 확인한다. 브랜드만 추론할 때 과거 상품코드와 조회 기준은 유지한다. `InventoryFlowIT`는 상품 통합의 마지막 참여 단계에서 실패해도 상품·매입처·재고·이력이 모두 복원되는지 확인한다. `ModuleArchitectureTest`는 알 수 없는 패키지·모듈 소유 불일치·제네릭 내부 타입 노출을 탐지하는 음성 사례를 포함한다.

보강 전후 실행 결과와 유지 범위는 [모듈 경계 보강 검증](module-boundary-reinforcement.md)에 기록한다.

## 실행 범위와 기록 해석

루트 `./mvnw test`·`verify`의 기본 Surefire 실행은 `*IT` 전체 통합 테스트를 포함하지 않는다. 전체 Java 검증은 임시 서비스와 integration-tests 프로필을 사용하는 `scripts/test-integration.sh`로 실행한다. 일부 클래스만 선택할 때는 다른 모듈에 해당 클래스가 없어도 reactor가 계속 실행되도록 다음처럼 지정한다.

```sh
JAVA_HOME=/path/to/jdk21 bash scripts/test-integration.sh \
  -Dtest=InventoryFlowIT,ProductFlowIT,MigrationUpgradeIT,LayoutViewTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Mockito 에이전트는 Maven 설정을 사용한다. 개인 캐시 경로를 argLine으로 덮어쓰지 않는다. 브라우저 검증은 템플릿 생성 이후 순서대로 실행하며, 동시에 clean을 실행해 화면 자료를 지우지 않는다. 전환·경계 보강 문서의 건수와 성공 결과는 각 검증 당시 기록이다. 이번 변경의 검증은 [문서 정합성 정리](documentation-alignment.md)에 별도로 기록한다.

## 공통 판매 상품 편집 검증

2026-10-06 초안 편집 단계 완료 당시 다음 검증을 통과했다. 자료는 합성 상품·로컬 이미지·모의 외부 응답을 사용했다. 비밀 파일 접근과 실제 마켓 쓰기는 수행하지 않았다. 이후 전송 구현의 결과는 아래 전송 검증 기록에서 구분한다.

| 실행 | 결과 |
|---|---|
| `./mvnw -o test` | Java 단위·모듈 경계 297개 통과 |
| `node --test src/test/js/*.test.cjs` | JS 79개 통과 |
| `MarketplaceDraftFlowIT`, `AuthenticationFlowIT`, `MigrationUpgradeIT` | 24개 통과, 명시적 실제 계정 조회 4개 비활성화 |
| 공통 편집·쿠팡 개별 편집·요청 취소·상품 UI | 1440px·390px 모두 통과 |

격리된 DB 통합 실행 명령은 다음과 같다.

```sh
JAVA_HOME=/path/to/jdk21 bash scripts/test-integration.sh \
  -Dtest=MarketplaceDraftFlowIT,AuthenticationFlowIT,MigrationUpgradeIT \
  -Dsurefire.failIfNoSpecifiedTests=false
```

초안 생성·재조회·수정 버전 충돌·CSRF·최신 ADMIN 상태·미저장 이미지 소유권·참조 유지와 정리·쿠팡 중복 가져오기·원본 및 수정 제한을 확인했다. 문서 기반 마켓별 가격·수량·이미지 규격, 채널 설정의 미입력/false 구분, 미확인 결과와 오류를 구분하는 검증도 포함한다. 화면에서는 상속/명시적 빈값/0, 마켓 해제 후 입력 보존, 옵션 삭제 참조 정리, 업로드와 이미지 순서, HTML 격리, 저장 충돌 입력 보존, 카테고리 조회 중 저장 차단을 확인했다. 공통 초안과 이미지 저장 API 이외의 외부 쓰기 요청은 발생하지 않았다.

## 판매 마켓 전송 검증

`scripts/test-marketplace-submissions.cjs`는 실제 Thymeleaf 화면과 로컬 합성 API로 전송 미리보기·실행·결과 확인을 검증한다. 마켓별 원격 엔드포인트를 요청하면 실패하므로 실제 상품 등록·수정이 발생하지 않는다. 캡처는 `/tmp/molebutter-submission-{1440,390}.png`에 합성 자료만 기록한다.

2026-10-06 1440px·390px 두 크기에서 모두 통과했다. 승인 요청 여부, 미리보기 취소 시 무전송, 전송 중 중복 제출·닫기 차단, 실패 재시도, 부분 완료 후 성공 단계 보존, UNKNOWN의 쓰기 재시도 차단·결과 확인, 응답 유실 후 같은 멱등 키 재확인, 미연동 네이버 실행 차단, 12건 이력의 이전·다음 이동을 확인했다. 공통 편집과 쿠팡 개별 편집의 기존 브라우저 흐름도 두 크기에서 다시 통과했다.

같은 코드에서 `node --test src/test/js/*.test.cjs`는 83개 모두 통과했다. 이전 초안 단계의 79개 기록에 전송 상태·실패 재시도·접수/미확인 정책과 변경 항목의 한국어 표시 검증 4개를 추가한 결과다. 전송 브라우저 검증도 옵션 UUID 경로가 옵션명·현재 판매가·현재 재고로 표시되는지 확인한 뒤 두 크기에서 다시 통과했다.

최종 `./mvnw -o test`는 Java 단위·모듈 경계 330개를 모두 통과했다. 모듈별 건수는 identity 4개, catalog 11개, procurement 185개, inventory 7개, marketplace 70개, app 53개이며 모듈 경계 2개를 포함한다.

| 실행 | 결과 |
|---|---|
| Java 단위·모듈 경계 | 330 / 330 통과 |
| JS 회귀 | 83 / 83 통과 |
| 선택 DB 통합 | 39개 실행, 35개 통과, 실제 계정 읽기 4개 비활성화 |
| 전송·공통 편집·쿠팡 개별 편집·요청 취소·상품 UI | 5개 스크립트 각각 1440px·390px 통과 |

```sh
NODE_PATH=/path/to/node_modules node scripts/test-marketplace-submissions.cjs
```

Java의 `SubmissionExecutionTest`는 미리보기 만료·revision 충돌·멱등 키·부분 결과·최신 권한·실행 잠금 유실·복구와 조회 확인 정책을 검증한다. 쿠팡 쓰기 어댑터는 가짜 인증 값과 모의 HTTP 응답만 사용한다. 실제 계정 쓰기와 공개 이미지 다운로드 성공은 별도 확인 항목이며 이 검증으로 완료했다고 간주하지 않는다.

격리된 DB의 선택 통합 검증은 아래 39개 중 35개를 통과하고 명시적 실계정 읽기 4개를 비활성화했다. 새 동시 실행 사례를 포함한 전송 10개 최종 실행과 나머지 클래스 결과를 합산한 수치이며, 앞선 전송 9개 실행을 중복 합산하지 않았다.

| 통합 클래스 | 실행 / 통과 / 비활성화 |
|---|---|
| `MarketplaceSubmissionFlowIT` | 10 / 10 / 0 |
| `MarketplaceDraftFlowIT` | 6 / 6 / 0 |
| `MigrationUpgradeIT` | 1 / 1 / 0 |
| `AuthenticationFlowIT` | 22 / 18 / 4 |

```sh
JAVA_HOME=/path/to/jdk21 bash scripts/test-integration.sh \
  -Dtest=MarketplaceSubmissionFlowIT,MarketplaceDraftFlowIT,AuthenticationFlowIT,MigrationUpgradeIT \
  -Dsurefire.failIfNoSpecifiedTests=false
```

실행 접수 전 무전송, 최신 권한·CSRF, 미리보기 만료·revision 충돌, 외부 매핑 유지, 일부 단계만 재시도, 중단 복구 후 무재전송, 같은 초안의 다른 미리보기 동시 실행, 이미지 전송 참조 보존과 만료된 무실행 미리보기 정리를 확인했다. 원본 이미지의 ADMIN 조회와 공개 토큰 GET의 범위도 모의 자산으로 확인했다.

기존 상품의 `brandId` 수정 미지원 정책을 반영한 뒤 공통 편집 브라우저만 1440px·390px에서 추가 실행했다. 가져온 상품의 브랜드 ID 입력·개별값 선택은 비활성화되고 WING 수정 사유를 표시하며, 카테고리 규격 조회와 임시 저장 이후에도 기존 값이 보존됨을 확인했다.

같은 제한을 보강한 `CoupangWriteGatewayTest` 16개를 별도로 다시 통과했다. 신규 브랜드 ID 입력·기존 같은 값 유지와 변경 없는 요청은 허용하고, 기존 상품의 추가·교체·삭제는 차단한다. 가져오기 원본 없이 실행 매핑으로 기존 상품이 확인된 경우에도 제한이 적용됨을 검증했다. 기존 테스트의 검증문을 보완한 재실행이므로 Java 330개에 중복 합산하지 않는다.

## 내부 IP HTTP 호환 검증

2026-10-06 UUID 생성 경로를 `AppUI.uuid()`로 통합했다. 보안 컨텍스트에서는 브라우저의 `crypto.randomUUID()`를 사용하고, 내부 IP HTTP처럼 이 메서드가 없는 환경에서는 `crypto.getRandomValues()`로 UUID v4를 생성한다. 보안 난수가 없으면 요청 전에 실패하며, `Math.random()`이나 시간으로 요청 식별자를 대신하지 않는다. 재고·전송 확인의 재시도 키와 조회 취소 식별자는 기존 수명과 재사용 규칙을 유지한다.

`node --test src/test/js/*.test.cjs`는 92개 모두 통과했다. UUID 형식·비트·독립 요청의 새 식별자, CSRF·세션 재시도 중 식별자 유지, 호출자가 지정한 식별자 보존, HTTP 마켓 조회 취소와 초안 옵션·이미지 식별자 보존을 포함한다.

`scripts/test-inventory-ui.cjs`는 기본 localhost 신뢰 조건과 비보안 HTTP 조건에서 각각 1440px·390px 검증을 통과했다. 매입 생성·부분 입고·응답 유실 후 같은 키 재시도·취소·기록 취소·PRODUCT 권한을 확인했다.

공통 편집과 전송 UI도 비보안 HTTP에서 각각 1440px·390px 검증을 통과했다. 옵션·이미지·설명 식별자가 서로 다른 UUID v4로 저장되고, 전송 확인에서 응답을 놓친 뒤 재시도해도 같은 키와 실행 건수를 유지함을 확인했다. 모든 저장·전송 결과는 로컬 합성 API에서만 처리했다.

아래 선택 모드는 Chrome의 합성 호스트를 로컬 fixture 서버에 연결한다. `isSecureContext=false`, `crypto.randomUUID` 부재, `getRandomValues` 제공을 확인하므로 localhost HTTP만 실행하는 검증과 구분된다. 운영 앱·DB·실제 마켓 요청은 사용하지 않는다.

```sh
MOLEBUTTER_UI_HTTP_LAN=true NODE_PATH=/path/to/node_modules node scripts/test-inventory-ui.cjs
MOLEBUTTER_UI_HTTP_LAN=true NODE_PATH=/path/to/node_modules node scripts/test-common-marketplace-editor.cjs
MOLEBUTTER_UI_HTTP_LAN=true NODE_PATH=/path/to/node_modules node scripts/test-marketplace-submissions.cjs
```

## 쿠팡 등록·수정 입력 화면 재배치

2026-10-06 쿠팡 개별 편집 페이지와 공통 상품의 쿠팡 탭을 상품 작성 순서로 재배치했다. 기존 공통 기준 입력 배치는 유지했다. 이번 UI 변경 후 JS 전체 102개와 `LayoutViewTest` 3개를 통과했다. 아래 브라우저 검증은 Thymeleaf로 렌더링한 화면과 로컬 합성 API만 사용했으며 운영 앱·실제 DB·외부 마켓 쓰기·비밀 파일 접근은 수행하지 않았다.

| 검증 | 결과 |
|---|---|
| 쿠팡 개별 편집 | 1440px·390px 통과 |
| 공통 상품 쿠팡 탭 | 1440px·390px, localhost와 비보안 HTTP 모두 통과 |
| JS 단위·렌더러 회귀 | 102 / 102 통과 |
| Java 화면 렌더링 | 3 / 3 통과 |

상품 정보의 필드 순서, 옵션 표의 MPN·모델번호·판매자상품코드 분리, 승인 정상가·자동 가격 설정·카테고리 잠금, 검색어 칩 변경, 선택한 옵션의 전송 의도를 확인했다. 설명만 수정한 미리보기에는 가격·수량 변경을 넣지 않으며 0과 현재값 미확인, 재조회 실패, 외부 변경 확인, 입력 취소 및 보존을 구분했다. 옵션 전환 시 주요정보·고시·검색어를 보존하고 이미지 선택과 대표·순서·삭제 동작도 확인했다.

신규 대상의 상품 단위 수량은 `productQuantity` 입력으로 연결된다. 공통 대표 이미지·설명의 읽기 전용 미리보기, 옵션 추가 이미지의 대표 중복 방지, 업로드 중 대상 전환·재조회 차단도 확인했다. 공통 미디어 적용은 옵션별로만 허용하고 공통 기준 저장 후 신규 대상의 상속값을 갱신한다. HTML sandbox를 유지하며 모바일에서는 넓은 옵션 표만 가로 스크롤한다. 이 검증을 기존 전체 Java·DB 통합 기록에 더하지 않는다.

```sh
JAVA_HOME=/path/to/jdk21 ./mvnw -o -pl molebutter-app -am test \
  -Dtest=LayoutViewTest -Dsurefire.failIfNoSpecifiedTests=false
node --test src/test/js/*.test.cjs
NODE_PATH=/path/to/node_modules node scripts/test-marketplace-editor.cjs
NODE_PATH=/path/to/node_modules node scripts/test-common-marketplace-editor.cjs
MOLEBUTTER_UI_HTTP_LAN=true NODE_PATH=/path/to/node_modules node scripts/test-common-marketplace-editor.cjs
```

## 쿠팡 검색어가 있는 상품의 가져오기 회귀

2026-10-06 옵션 `searchTags`를 추가 설정 병합 때 다시 수집하던 변환 오류를 수정했다. 검색어는 옵션 등록 설정에 한 번만 보존하며 중복 설정을 거절하는 저장 검증은 유지한다. 가짜 키·합성 상품·로컬 HTTP 응답으로 관련 Java 69개를 통과했다: `CoupangClientTest` 20개, `DraftValidationTest` 15개, `CoupangWriteGatewayTest` 34개. 실패·오류·비활성화는 0개다.

검색어 없음·하나·여러 개와 복수 옵션에서 가져오기 및 최신 조회가 정상 변환되는지 확인했다. 검색어 변경은 선택한 옵션에만 적용하며 다른 옵션의 최신 검색어를 보존한다. 쉼표 뒤 공백 차이는 같은 검색어 배열로 비교하고, 이미 반영된 검색어는 상품 정보 재전송 없이 후속 가격 변경만 실행한다. 선택한 검색어가 외부에서 다른 값으로 바뀌면 기존 충돌 검사를 유지한다. 실제 계정 호출·외부 쓰기·비밀 파일 접근은 수행하지 않았다.

```sh
JAVA_HOME=/path/to/jdk21 ./mvnw -o -pl molebutter-marketplace -am test \
  -Dtest=CoupangClientTest,DraftValidationTest,CoupangWriteGatewayTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

프론트엔드만 변경했으며 Java·DB·인증 설정은 변경하지 않았다. 기존 Java 검증 기록을 이번 HTTP 브라우저 검증 결과로 다시 계산하지 않는다.

## 최신 조회 편집 세션과 선택 변경 검증

2026-10-06 편집 세션을 추가한 뒤 격리된 MySQL·Redis와 가짜 쿠팡 전송으로 아래 34개를 통과했다. 편집·전송·초안 33개와 업그레이드 1개를 각각 실행한 최종 결과이며 앞선 재실행을 중복 합산하지 않았다. 테스트 애플리케이션의 쿠팡 Access Key·Secret Key는 빈 값으로 강제하고, 외부 상품 읽기는 합성 DTO, 쓰기는 네트워크를 사용할 수 없는 테스트 게이트웨이로 처리했다. 실제 계정의 등록·수정이나 비밀 파일 접근은 수행하지 않았다.

| 통합 클래스 | 실행 / 통과 |
|---|---|
| `MarketplaceEditingFlowIT` | 15 / 15 |
| `MarketplaceSubmissionFlowIT` | 12 / 12 |
| `MarketplaceDraftFlowIT` | 6 / 6 |
| `MigrationUpgradeIT` | 1 / 1 |

세션 API의 ADMIN·CSRF·최신 계정 상태·작성자 한정 접근, 10분 만료·다른 화면의 revision 변경·재조회 뒤 이전 세션과 확인 차단을 검증했다. 공통 참조 저장은 기존 관찰값을 유지하고, 신규 대상은 변경한 공통 가격·수량을 기본값으로 사용한다. 현재 가격·수량 각각의 미확인과 0을 구분하며 조회 실패나 옵션 식별자 변경이 신규 등록으로 바뀌지 않는다.

연결된 상품에서 가격만 선택하면 `PRICE` 한 단계만 실행되고 수량과 저장된 공통 참조는 유지된다. 선택하지 않거나 같은 값을 선택하면 실행할 외부 작업을 만들지 않는다. 생성된 외부 연결로 다시 가져오면 같은 초안을 반환하며 별도 상품을 만들거나 추가 상세 조회를 보내지 않는다.

서로 다른 미리보기의 동시 실행에서 신규 등록 한 건만 접수되는지 다시 확인했다. `ACCEPTED` 단계 뒤의 가격 쓰기는 대기하고, 읽기 확인으로 반영을 확인한 뒤 잔여 단계를 진행한다. `UNKNOWN`은 쓰기 재시도를 차단한다. 실행 직전 최신값으로 병합한 실제 요청 스냅샷을 시도 기록에 남기고 브라우저의 실행 DTO에서는 공개하지 않는다. `결과 확인`은 최초 확인 화면의 전문 대신 마지막 실제 쓰기 시도의 body·expected 값을 사용하며 저장 단계 ID는 유지한다. 기존 멱등 키·부분 실패 재시도·중단 복구·이미지 참조 유지와 정리도 함께 통과했다.

검증 중 발견한 nullable 현재 수량·가격 처리, Spring 저장소 프록시 기동, MySQL 반복 읽기에서의 동시 작업 조회를 수정한 뒤 동일 회귀를 다시 통과했다. 잠금 순서를 일치시킨 뒤 공통 참조 저장과 전송 접수의 동시 요청이 500 없이 처리됨을 확인했다. 전송이 먼저 접수되면 이후 내부 참조 저장을 허용하되 고정된 전송 의도는 유지하고, 참조 저장이 먼저 완료되면 이전 전송 접수를 409로 거절한다. 쿠팡 옵션 설정이 없는 신규 공통 상품도 초기 등록 기본값을 만든 후 선택한 속성을 입력할 수 있다. 최종 명령은 다음과 같다. Mockito JAR 경로는 실행 환경의 설치된 버전으로 지정한다.

```sh
JAVA_HOME=/path/to/jdk21 bash scripts/test-integration.sh \
  -Dtest=MarketplaceEditingFlowIT,MarketplaceSubmissionFlowIT,MarketplaceDraftFlowIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine=-javaagent:/path/to/mockito-core.jar
```

`SubmissionExecutionTest` 20개도 별도로 통과했다. 세션 없는 이전 미리보기·폐기된 세션·접수/미확인 진행 중의 추가 실행을 차단하며, 선택 의도가 없는 과거 대기 쓰기는 실행하지 않는다. 과거 실행의 읽기 확인과 같은 멱등 키의 결과 재조회는 유지한다. 최신 권한, 외부 실행 이후 예외·결과 저장 실패의 `UNKNOWN` 처리, 잠금 유실과 외부 연결 변경 차단도 포함한다.

업그레이드 회귀는 기존 V1~V30 전환 흐름 끝에 V31 초안·이미지, V32 외부 연결·미리보기·미확인 실행·시도 기록을 삽입하고 V33으로 전환했다. 공통·마켓별 JSON 원문, 큰 외부 ID, 옵션 연결, revision, 멱등 키, 상태와 시각, 이미지 참조·발행을 보존한다. 새 세션 ID·실제 요청 컬럼은 이전 기록에서 null이고 새 세션은 정상 저장된다. 과거 공통 문서와 미리보기는 현재 타입으로 읽을 수 있으며 0원·미확인 수량·옛 네이버 선택 필드의 null도 유지한다. 기존 사용자와 보유 재고도 보존하고 마이그레이션 재실행은 추가 변경을 만들지 않는다.

```sh
JAVA_HOME=/path/to/jdk21 bash scripts/test-integration.sh \
  -Dtest=MigrationUpgradeIT \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine=-javaagent:/path/to/mockito-core.jar
```

이 34개는 현재 세션 변경의 선택 통합 실행 결과다. 위의 과거 Java 330개·JS·브라우저 기록에 중복 합산하지 않으며, 실제 계정 쓰기와 네이버·ESM 어댑터 완료를 뜻하지 않는다.

최종 전체 Java 재실행은 단위·모듈 경계 353개를 모두 통과했다. 쿠팡 선택 변경 어댑터 33개, 실행 서비스 20개, 과거 JSON 호환 2개와 모듈 경계 2개를 포함하며 별도 재실행 수치를 더하지 않는다. API에서 상품명·브랜드·제조사·배송·SKU 별칭을 우회해 기준값 변경 검사를 건너뛰거나 같은 항목을 중복 지정하는 요청도 거절한다. 현재값 미확인 옵션의 가격·수량 변경을 차단하고, 설명만 수정하면 준비·실행 모두 재고 조회와 가격·수량 쓰기 요청이 0회임을 확인했다. 선택한 현재값의 재검증과 결과 확인에서는 그 값만 필수로 검사하며 미선택 값의 누락·null을 0으로 대체하지 않는다. 일반 재고 응답 파서의 구조 검증은 유지한다. JS 전체 100개도 다시 통과했다.

| 최종 검증 | 결과 |
|---|---|
| Java 단위·모듈 경계 | 353 / 353 통과, 실패·오류·비활성화 0 |
| JS 회귀 | 100 / 100 통과 |
| 격리 DB 선택 통합·업그레이드 | 34 / 34 통과 |
| 공통 편집 브라우저 | 1440px·390px, localhost와 일반 LAN HTTP 모두 통과 |
| 전송 브라우저 | 일반 LAN HTTP 1440px·390px 통과 |

브라우저 검증은 공통/마켓 선택과 같은 네 구역, 명시적 공통 적용, 최신 조회값 복원, 실패·만료·재조회·이탈 처리와 전송 확인을 합성 자료로 검증했다. 실제 쿠팡 쓰기·비밀 파일 접근·운영 배포는 0회다. 공개 이미지의 외부 도달 가능성과 나머지 마켓 어댑터는 이 결과에 포함하지 않는다.

```sh
JAVA_HOME=/path/to/jdk21 ./mvnw -o test
node --test src/test/js/*.test.cjs
NODE_PATH=/path/to/node_modules node scripts/test-common-marketplace-editor.cjs
MOLEBUTTER_UI_HTTP_LAN=true NODE_PATH=/path/to/node_modules node scripts/test-common-marketplace-editor.cjs
MOLEBUTTER_UI_HTTP_LAN=true NODE_PATH=/path/to/node_modules node scripts/test-marketplace-submissions.cjs
```


## 판매 주문 수집 회귀 검증 (2026-10-07)

[구현 범위와 실제 계정 읽기 기록](market-api/sales-orders.md).

- Java 단위·모듈 경계 전체: 377개 통과, 실패·오류·비활성화 0.
- JS 전체: 110개 통과, 실패·비활성화 0.
- 격리 MySQL·Redis 전체 통합: 실행 240개 통과, 실패·오류 0. 총 245개 중 기존 인증 테스트 4개와 명시 활성화 전용 실계정 읽기 1개는 건너뜀. 단위 테스트를 포함한 해당 빌드 전체는 622개 중 617개 통과·5개 건너뜀.
- 브라우저 주문 수집·검색·페이지 이동·상세·취소·재시도: 합성 API로 1440px·390px, localhost·내부 IP 일반 HTTP 통과.
- 실계정 읽기: 정상 주문 6개 상태, 취소·반품·교환·반품철회 목록, 주문 상세, 다음 토큰 이동 정상. HTTP 200·업무 코드 200. 실계정 쓰기 요청 0회.

주문 테스트는 미완료 클레임 재조회·철회·부분 실패·커서 유지·응답 유실 멱등성·불완전 스냅샷 보존·최신 관리자 권한·개인정보 미저장과 주문 전용 스케줄러 분리를 포함한다. 실제 주문·연락처·응답 원문은 테스트 자료에 저장하지 않는다. 실계정 전용 `MarketplaceOrderLiveIT`는 환경 변수로 명시 활성화할 때만 실행하며 기본 회귀 실행에서 제외한다.


## 쿠팡 판매 주문 개선 검증 (2026-10-07)

- Java 단위·모듈 경계 전체 385개 통과, 실패·오류·비활성화 0.
- JS 전체 117개 통과, 실패·오류·비활성화 0.
- 격리 MySQL·Redis 전체 통합 실행 245개 통과. 총 250개 중 기존 인증 테스트 4개와 명시 활성화 전용 실계정 읽기 1개는 건너뜀.
- 최종 주문 13개 + 업그레이드 2개 별도 재실행 15개 통과. V34 자료의 V35 관측 건수·접수 연결 복원, 미확인 시각 플래그와 유효 작업 lease 보존을 검증했다. 별도 재실행 수를 전체 통과 건수에 중복 합산하지 않는다.
- 브라우저 localhost·일반 LAN HTTP 각각 1440px·390px 통과. 구간 진행·실패 펼침·완료 구간 보존 재개·이력 지연·검색 페이지 유지·관측 시각 구분·복원 수치·최신 미확인·개인정보 닫기 제거를 검사했다.
- 실계정 읽기: 반품 단건 1건, 반품철회 이력 접수번호 POST 1건, 기존 취소 접수 날짜 재조회 1건 모두 HTTP 200·업무 코드 200. 취소와 철회는 예상 접수번호 일치 확인. 다음 토큰 조회도 정상. 교환은 빈 목록만 확인하여 실제 품목·기존 교환 갱신은 미검증으로 남긴다.

실계정 POST는 공식 `returnWithdrawList` 이력 조회 경로에 한정한다. 실제 반품철회 처리·승인·입고·송장·상품 쓰기는 실행하지 않았다. 비밀 파일 직접 접근·운영 DB 테스트·커밋·푸시·배포는 수행하지 않았다.

### 주문 발주서 제한 회귀 (2026-10-07)

`CoupangOrderClientTest`, `MarketplaceOrderCollectionTest`, `CoupangClientTest`와 격리 DB의 `MarketplaceOrderFlowIT`, `MigrationUpgradeIT`, `ModuleArchitectureTest`로 거절 분류·클레임 정보·집계·재시도·권한·모듈 경계를 검증했다. JS 주문 테스트 17건과 `MOLEBUTTER_UI_HTTP_LAN=true`의 주문 UI 스크립트로 1440px/390px HTTP 환경을 확인했다. 실제 과거 거절 사유는 합성 테스트로 확정하지 않는다.

### 쿠팡 편집 입력 폭·카테고리 규격 (2026-10-07)

등록·수정 입력 폭을 상품명 420px, 브랜드 220px, 카테고리 코드 160px, 숫자 130~150px로 제한했다. 2560px/1440px/390px 브라우저에서 입력 폭·카테고리 버튼·옵션 전환·입력 보존을 검증했다. 기존 설정을 Spring 런타임으로 읽어 화면의 카테고리 메타정보를 1회 요청했으며 HTTP 200·SUCCESS, 속성 21·고시 유형 3·인증 규격 27·서류 규격 5를 확인했다. 실제 원문·자격 증명은 저장하지 않았다.

### 쿠팡 편집 입력 순서와 옵션명 표시 (2026-10-07)

- 쿠팡 단독 편집과 공통 상품의 쿠팡 영역에서 브랜드 → 노출상품명 → 등록상품명 → 카테고리 코드를 세로 배치하고 규격 조회 버튼을 코드 옆에 배치했다.
- API itemName 입력을 등록 옵션명으로 표기하고 구매 속성의 값 조합을 별도 미리보기로 표시한다. 조합 미리보기는 등록 옵션명을 변경하지 않는다.
- LayoutViewTest 성공. 단독 편집 브라우저 2560·1440·390px 성공. 공통 쿠팡 레이아웃 1440·390px 성공 (`MARKETPLACE_UI_LAYOUT_ONLY=1 node scripts/test-common-marketplace-editor.cjs`).
- 공통 편집 전체 브라우저 검증은 부분 조회 후 가격 미확인 표시 대기에서 간헐적 시간 초과가 발생했다. 이번 변경 이전 JavaScript를 제공한 비교 실행에서도 같은 단계의 실패가 재현되어 전체 검증 성공으로 기록하지 않는다.

### Coupang brand search verification (2026-10-07)

- Java: CoupangBrandsTest, CoupangClientTest, CoupangWriteGatewayTest, DraftValidationTest: 72 passed. LayoutViewTest and ModuleArchitectureTest: 5 passed. Includes POST body/paging, empty and invalid responses, actor-bound searched identity, expiry, denied access and existing-brand protection.
- JS editor model, common model and request controls: 33 passed. Missing brand ID is required for new registration and preserved for legacy edits.
- Chrome synthetic standalone editor: 2560/1440/390px passed search paging, selection, paired name/ID display, edit locks, hidden category button and preserved inputs.
- Chrome synthetic common editor: 1440/390px passed existing locks, unselected new-brand save rejection and saving after selection (`MARKETPLACE_UI_LAYOUT_ONLY=1`). The previously documented intermittent partial-current failure in the full common-editor scenario remains separate.
- Live account first brand search page: HTTP 200, SUCCESS, 10 items, hasNext=true. Minimal Spring runtime used existing configuration. No DB, product writes or running-app restart. Live second page was not requested.

### 쿠팡 단독 편집 상품주요정보 라디오 선택 (2026-10-07)

- JS 편집 테스트 17건 통과: 상품주요정보 순서·라디오 렌더링, 구비서류 분리, 조건부 구매수량·기간 입력, 원본 응답 비변경을 포함한다.
- LayoutViewTest 통과 후 생성된 Thymeleaf 페이지로 Chrome 2560px·1440px·390px 검증 통과. 카테고리 모의 응답을 이용해 인증 방식 전환과 인증번호 복원, 구매 제한·판매기간 입력 표시, 기존 상품 구성 읽기 전용을 확인했다. 기존 옵션·이미지 드래그·설명 격리 흐름도 통과했다.
- 모든 마켓 API 호출을 합성 GET 응답으로 격리했으며 외부 쓰기 0건이다. 실계정 등록·수정 성공과 구분한다.

### 쿠팡 이미지 드래그 삽입 위치·최대 개수 안내 (2026-10-07)

드래그 중 카드 앞·뒤 삽입 위치를 파란 표시선으로 보여주고 빈 영역·여러 행 사이도 가장 가까운 카드 기준으로 계산한다. 표시와 실제 드롭은 같은 목적지를 사용한다. 최대 개수에서는 주소 입력 검사와 파일 선택창을 열기 전에 삭제 후 추가 안내를 표시한다. 중고 이미지가 없는 옵션에는 중고 제한을 안내하지 않는다.

LayoutViewTest와 JS 편집 테스트 17건 통과. Chrome 2560px·1440px·390px에서 표시선 위치와 드롭 후 실제 순서 일치, 드래그 종료 시 표시 제거, 최대 개수에서 빈 URL보다 개수 안내 우선, 파일 선택창 차단을 확인했다. 기존 모바일 터치 흐름을 포함하며 마켓 호출은 합성 GET으로 격리했다.

### 쿠팡 인증 상세 개별 추가 (2026-10-07)

JS 편집 테스트 17건·LayoutViewTest 통과. Chrome 2560px·1440px·390px에서 인증 유형과 번호로 행 추가, 서로 다른 유형 두 행 표시, 빈 유형·빈 인증번호·중복 유형 안내, 행 삭제, 다른 인증 방식 전환 후 번호 복원을 검증했다. 합성 GET 응답만 사용하며 인증번호 진위 확인이나 실제 마켓 저장은 수행하지 않았다.

### 상세설명 이미지 세로 미리보기 (2026-10-07)

JS 편집 테스트 17건·LayoutViewTest 통과. Chrome 2560px·1440px·390px의 실제 sandbox iframe에 연속 이미지 두 장을 넣고 같은 가로 위치·서로 겹치지 않는 세로 좌표·최대 폭 준수를 확인했다. 스크립트 격리 및 기존 편집 흐름도 통과했다. 설명 원문을 재작성하거나 외부 마켓 저장을 실행하지 않았다.

### 쿠팡 정보 구역 압축·구비서류·검색어·고시 일괄 참조 (2026-10-07)

JS 편집 테스트 18건·LayoutViewTest 통과. Chrome 2560px·1440px·390px에서 제조사 상단 배치, 서류 추가·주소에 따른 보기 활성화·삭제, 검색어 21개 및 21자 초과 안내·항목 삭제 후 정상 복귀, 고시 전체 참조·개별 수정 후 체크 해제·이전 값 복원·기존 참조 값 조회 시 자동 체크를 검증했다. 합성 GET 응답만 사용하며 실제 마켓 쓰기는 수행하지 않았다.

### 고시 유형 선택 시 누적 표시 수정 (2026-10-07)

JS 편집 테스트 18건·LayoutViewTest 통과. Chrome 2560px·1440px·390px에서 고시 유형 3종 전환 시 현재 유형의 입력만 표시, 기존 유형으로 돌아오면 값 복원, 참조 체크와 일괄 입력이 현재 유형에만 적용됨을 확인했다. 비활성 유형이 체크 판정에 영향을 주지 않는 경우도 JS에서 검증했다. 실제 마켓 쓰기는 수행하지 않았다.

2026-10-07 쿠팡 배송·반품/교환: CoupangShippingPlacesTest 4건, LayoutViewTest 3건, ModuleArchitectureTest 2건 및 JS 모델·화면 테스트 18건 통과. 가짜 주소록 응답을 사용하는 2560·1440·390px 브라우저에서 주소록 페이지 이동, 사용 불가 주소 차단, 선택·취소 시 입력 보존, 출고지 코드 자동 조회, 무료배송·왕복 반품비 계산, 옵션별 출고일 보존과 일괄 적용을 확인했다. Java에서는 실제 transport 호출 경로/GET 쿼리를 mock으로 검증하고, 반품지 계정 불일치·실패 응답·빈 목록·권한 거절을 검증했다. 실계정 조회·외부 상품 쓰기·DB 변경은 실행하지 않았다.

### 쿠팡 단독 수정 저장 연결 검증 (2026-10-07)

최종 선택 Java 단위·경계·화면 75건 통과: CoupangWriteGatewayTest 39, SubmissionExecutionTest 20, CoupangProductSavingTest 5, MarketplaceEditingCompatibilityTest 2, MarketplaceAssetTest 4, ModuleArchitectureTest 2, LayoutViewTest 3. 정상가 0·전용 경로와 재조회, 단계 순서, requested 적용 범위, 승인 대기, 식별자 연결, 공통값 적용, 활성 고시만 전송, 인증·서류·이미지·설명 및 미선택 원문 필드 보존을 확인했다. 관찰 토큰의 작성자·상품·만료 검증과 기존 공통 입력 보존도 포함한다.

격리 MySQL·Redis에서 MarketplaceSubmissionFlowIT 16, MarketplaceEditingFlowIT 15, MarketplaceDraftFlowIT 7, 총 38건 통과. 저장 접수와 서버 이력 복원, 부분 실패 후 후속 요청 중단, 성공 단계 미반복, 충돌 이후 새 준비 가능, UNKNOWN 확인 전 재쓰기 금지, 관리자/CSRF 경계, 자신의 미저장 업로드 전송 허용과 다른 작성자 업로드 공개 차단을 확인했다. 실행 명령은 `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home bash scripts/test-integration.sh -Dtest=MarketplaceSubmissionFlowIT,MarketplaceEditingFlowIT,MarketplaceDraftFlowIT -Dsurefire.failIfNoSpecifiedTests=false`이다.

JS 편집·전송·공통 편집 42건 통과. 기존 편집 브라우저 2560·1440·390px, 신규 저장 브라우저 1440·390px 통과. `node scripts/test-coupang-save-ui.cjs`는 변경 없음 무전송, 만료·충돌 시 입력 보존, 파일 업로드 실패·재시도, blob 제외, 중복 확인 방지와 UNKNOWN 결과 확인을 모의 API로 검증한다. 편집 중 옵션 전환과 실행 후 조회에서도 입력·파일 참조를 보존한다.

최종 마켓 모듈 전체 단위 테스트도 145건 통과했다(`mvn -q -pl molebutter-marketplace -am test`). 모든 외부 마켓 응답은 가짜 자료로 검증했다. 실제 판매 상품 변경·실계정 승인 완료·공개 이미지 외부 도달 가능성은 실행하거나 확인하지 않았다. 운영 상품 쓰기는 별도 상품·변경값 실행 요청이 있을 때 수행한다.

2026-10-07 저장 거절 사유 보완: CoupangWriteGatewayTest 41건을 포함한 선택 Java 검증 73건, 격리 DB 통합 38건, JS 편집·전송 28건 및 모의 저장 브라우저 1440·390px 통과. 문서화된 가격 오류 7종의 HTTP 400/HTTP 200 ERROR 분류, 미분류 HTTP 상태 보존, 외부 연락처 미노출, 5xx UNKNOWN 유지, 관찰 옵션명 메타데이터 및 확인 창의 옵션명·판매가 표시를 확인했다. 운영 DB에서는 해당 실패 실행을 읽기 전용으로 확인했으며 재시도·상품 변경을 실행하지 않았다. 당시 응답 본문·HTTP 상태는 기존 기록에 없어서 상세 거절 사유는 확인하지 못했다.

2026-10-07 HTTP 411 전송 보완: CoupangHttpTransportTest 1, CoupangClientTest 20, CoupangWriteGatewayTest 41, SubmissionExecutionTest 20, 총 82건 통과. 로컬 HTTP 서버가 정상가·판매가·재고 PUT에서 실제 Content-Length: 0·빈 본문·Upgrade/Transfer-Encoding 없음·경로/쿼리 보존을 검증한다. 본문이 있는 PUT도 실제 JSON 바이트 길이를 확인했다. 격리 DB의 MarketplaceSubmissionFlowIT 17건도 통과했으며 과거 411 이력의 새 대처 문구와 후속 재고 미전송을 확인했다. 실제 쿠팡 쓰기를 재시도하지 않았다.


2026-10-07 신규 쿠팡 등록 점검: CoupangWriteGatewayTest 45건·DraftValidationTest 17건(합계 62건), MarketplaceDraftFlowIT 7건·MarketplaceSubmissionFlowIT 17건(임시 DB 합계 24건), LayoutViewTest 3건 통과. 실제 gateway의 CREATE 승인대기·승인완료 전환·응답유실 복구, 구매 조합 중복 차단 및 자동 가격 최소가 조건을 모의 응답으로 검증했다. `test-common-marketplace-editor.cjs`는 1440·390px에서 신규 입력의 0 값 보존, 고시 유형 변경, 선택한 고시만 준비 요청에 포함, 카테고리 자동 속성의 전송 포함, 업로드 중 전환 차단을 확인했다. 운영 DB·실제 쿠팡 쓰기 없음.

### 쿠팡 전용 신규 상품 등록 (2026-10-07)

쿠팡 전용 `/marketplaces/coupang/products/new`의 임시 저장·등록 확인·CREATE 실행 연결을 추가했다. 신규 등록 서비스 입력 변환, revision 충돌·작성자/계정 경계·외부 식별자 거부, UUID 옵션 연결, 공통값 복사와 개별 MPN·GTIN·미디어 유지, 선택 고시 제외, 요청 전문의 CREATE/POST·requested·ID 제거를 검증했다. 기존 실행 엔진에서 UNKNOWN 재등록 차단, ACCEPTED ID 연결, 재조회 후 완료와 기존 공통 초안·실행 회귀를 확인했다.

- 격리 MySQL·Redis 실행: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home PATH=/opt/homebrew/bin:$PATH bash scripts/test-integration.sh -pl molebutter-app -am -Dtest=MarketplaceSubmissionFlowIT,MarketplaceDraftFlowIT,MarketplaceEditingFlowIT,CoupangRegistrationInputTest,CoupangWriteGatewayTest,DraftValidationTest -Dsurefire.failIfNoSpecifiedTests=false`: 서버 108개 통과.
- `MigrationUpgradeIT` 별도 격리 실행 2개 통과: 기존 문서·revision·실행 기록 보존 및 V37의 기존 초안 `COMMON` 기본값 확인.
- `ModuleArchitectureTest`, `LayoutViewTest` 5개 통과. 렌더링한 템플릿을 브라우저 fixture에 사용했다.
- `node --test src/test/js/marketplace-editor.test.cjs src/test/js/common-marketplace-editor.test.cjs`: 40개 통과. 자동 옵션명·UUID·카테고리 규격 재구성·기존 편집 모델 회귀 포함.
- `scripts/test-coupang-registration-ui.cjs`: Chrome 1440px/390px 통과. 불완전 임시 저장 후 재진입, 옵션 추가·삭제·자동명, 버전 충돌 시 입력 유지, 업로드 실패·재시도, 기본 승인 요청 및 저장만 선택, execute 응답 유실 후 동일 key 확인, 새로고침 후 등록 ID/승인 대기/결과 확인을 검증했다.
- `scripts/test-coupang-save-ui.cjs`: Chrome 1440px/390px 통과. 기존 상품 수정 준비 충돌·파일 재시도·UNKNOWN 확인·만료 입력 보존 회귀.
- `scripts/test-marketplace-editor.cjs`: Chrome 2560px/1440px/390px 통과. 실제 상품 등록 버튼으로 전용 화면 진입, 브랜드 검색, 출고/반품 주소록, 공통 출고 소요일, 인증·서류·검색·선택 고시, 데스크톱/터치 이미지 이동과 삽입 표시, 미디어 한도, 800px 세로 미리보기 및 수정 화면 회귀.

브라우저의 쿠팡 관련 응답과 서버 쓰기 게이트웨이는 모두 모의 응답이다. 실계정 신규 상품 생성·승인 요청은 수행하지 않았다. 서버 재시작·배포도 이번 검증에 포함하지 않았다.

### 공통 등록의 브랜드 입력과 11번가 준비 표시 (2026-10-08)

`LayoutViewTest` 3개와 공통·쿠팡 편집 JS 테스트 40개를 통과했다. Chrome 1440px/390px에서 `scripts/test-common-marketplace-editor.cjs`의 기본 모드와 `MARKETPLACE_UI_LAYOUT_ONLY=1` 모드를 실행했다. 11번가 선택 불가, 브랜드명·쿠팡 코드·검색 버튼의 한 행 배치, 공통 브랜드명 직접 입력, 쿠팡 선택 해제/재선택 시 값 보존, 브랜드명 변경 시 코드 해제 및 검색 재선택 저장, 기존 수정 잠금·임시 저장·변경 확인을 검증했다.

공유 브랜드 검색의 쿠팡 전용 등록 회귀는 `scripts/test-coupang-registration-ui.cjs`의 1440px/390px에서 통과했다. 외부 마켓 응답은 로컬 모의 API이며 실제 상품 등록·수정이나 서버 재시작은 수행하지 않았다. 마켓별 브랜드 필드 비교는 [공통 브랜드 안내](market-api/common-product-editor.md#공통-브랜드명과-마켓별-브랜드-코드-2026-10-08)에 기록한다.

### 상품 이미지 도구의 공개 R2 업로드 (2026-10-08)

안내 문구를 제거하고 ‘다운로드’ 옆에 ‘업로드’를 추가했다. 저장용 상품코드를 조회 코드와 분리하며, 공개 `products/{저장코드}/{요청 UUID}-{순번}.{실제 확장자}` 경로를 확인한 뒤 현재 목록의 원본·사이즈·상품정보 이미지를 순서대로 저장한다.

- `mvn -o -pl molebutter-storage,molebutter-imaging -am test`: 저장 모듈 50개·이미지 모듈 165개 통과. 영역별 nested 설정·flat 호환·분리 자격 증명 서명, 조건부 생성과 실제 SDK 요청 형식, ZIP 회귀·원본 URL 검증·본인 PNG 접수 스냅샷을 포함한다.
- `mvn -o -pl molebutter-app -am -Dtest=ProductImageUploadServiceTest,ProductImageControllerTest,ModuleArchitectureTest,LayoutViewTest -Dsurefire.failIfNoSpecifiedTests=false test`: 24개 통과. 저장 코드 정규화·원본 코드 유지·혼합 순서·전체 파일 검사 후 순차 쓰기·부분 실패 중단·동일 ID 중복 방지·작성자 경계·제한·응답 유실 후 SHA-256/크기/형식 확인을 검증한다. HEAD에서 객체가 없거나 다른 값이면 UNKNOWN을 유지하고 추가 쓰기를 하지 않는다.
- `MOLEBUTTER_COUPANG_LIVE=false CLOUDFLARE_R2_ENABLED=false bash scripts/test-integration.sh -pl molebutter-app -am '-Dtest=AuthenticationFlowIT#r2InfrastructureStartsDisabledWithoutACloudClient+productImageWorkspaceRequiresProductAccessAndCsrf+marketplaceRequiresCurrentAdministrator' -Dsurefire.failIfNoSpecifiedTests=false`: 격리 MySQL·Redis에서 3개 통과. 익명·역할 변경·CSRF·입력 오류·없는 작업·비활성 R2의 HTTP 응답을 확인했다.
- `node --test src/test/js/product-images-angle.test.cjs src/test/js/product-images-order.test.cjs`: 16개 통과.
- `scripts/test-product-images-ui.cjs`: Chrome 2560px/1440px/390px 통과. 저장 코드 편집과 모바일 모달, 휴지통 제외·혼합 이미지 순서, 업로드 중 잠금·중복 클릭, POST 응답 유실과 동일 ID 확인·재시도, 조회 오류 후 작업 ID 유지, 부분 실패·UNKNOWN 공개 링크, 세션 갱신·CSRF, 새로고침 복원을 모의 API로 검증했다. 접수된 RUNNING 작업의 GET 403 이후 권한이 복원돼도 추가 POST 없이 결과를 조회한다.

기존 비밀 설정은 Spring ConfigData로만 읽어 `cloudflare` 프로필에서 공개·비공개 SDK 클라이언트를 생성할 수 있는지 확인했다. 비밀 파일은 직접 읽거나 수정하지 않았고 키·서명·Authorization을 출력하지 않았다. 이는 설정 바인딩 검증이며 이번 구현에서 실제 R2 객체 쓰기·실제 상품 등록·실행 중인 앱 재시작은 수행하지 않았다. 업로드 실행 기록은 메모리 4시간 보관으로 영구 DB 이력과 구분한다.

### 생성 이미지 다운로드·업로드 파일명 (2026-10-08)

상품정보 PNG의 순번 파일명을 `상품정보.png`로 변경하고, 사이즈는 `사이즈.png`를 유지한다. 여러 장이면 종류별 `_2`, `_3`를 붙여 ZIP 이름 충돌을 방지한다. R2의 실제 객체명도 같은 파일명을 사용하고 경로는 `products/{저장코드}/{요청 UUID}/{파일명}`으로 구성해 기존 객체를 보존한다.

`mvn -o -pl molebutter-app -am -Dtest=DownloadServiceTest,NoticeImageServiceTest,ProductImageUploadServiceTest,ProductImageControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`에서 39개 통과. ZIP의 실제 항목·바이트·혼합 순서·중복 상품정보·기존 자동 고시 생성과 R2 `create`의 실제 키·파일명·중복명 거절을 검증했다. 기존 순번을 기대하던 고시 회귀를 새 계약으로 갱신한 뒤 재실행했다. `scripts/test-product-images-ui.cjs`의 Chrome 2560px/1440px/390px에서도 두 생성 이미지의 링크 이름과 한글 URL 경로를 확인했다. 외부 응답은 모의 자료이며 실제 R2 쓰기·기존 객체 이동·앱 재시작은 수행하지 않았다.

### 상품정보 코드 제외·폰트와 여백 압축 (2026-10-08)

상품코드를 우측 상품정보 입력과 생성 PNG에서 제외하고 조회 원본의 상품코드는 유지한다. 최종 780px PNG의 제목·항목명·값 글씨를 각각 2px 줄였으며, 카드 안팎·줄 사이의 여백도 줄였다. 화면의 입력 글씨·카드 여백을 함께 압축했다. 이미 추가한 생성 PNG는 그대로 유지하며 새로 추가한 이미지부터 적용한다.

`mvn -o -pl molebutter-imaging -am test`: 165개 통과. 상품코드의 공백·구두점·분해형 한글 라벨 제외, 원본 보존, 필터 적용 전후 PNG 바이트 일치, 긴 텍스트·카드 간격·하단 여백과 기존 생성·다운로드 회귀를 검증했다. `scripts/test-product-images-ui.cjs`의 Chrome 2560px/1440px/390px도 통과했다. 화면과 생성 요청의 상품코드 제외, 조회 코드 보존, 작은 폰트·여백, 편집 내용 잘림 없음 및 기존 다운로드·업로드 모의 흐름을 확인했다.

실제 렌더러의 로컬 PNG 샘플은 780×1026px에서 780×711px로 줄었으며 모든 문구가 보이는지 이미지를 확인했다. 높이는 입력 내용과 폰트에 따라 달라진다. 외부 상품 조회·R2 쓰기·앱 재시작은 수행하지 않았다.

### 로컬 앱의 R2 활성화 누락 (2026-10-08)

상품 이미지 업로드의 ‘R2 업로드가 설정되지 않았습니다’ 안내를 확인했다. 실행 앱은 기본 프로필이며 R2 활성화 인자·환경 변수가 없었다. 비밀 설정 import는 정상이어도 기본 `cloudflare.r2.enabled=false`에 따라 업로드 접수 전에 비활성 저장소가 거절한다. 현재 Mac의 VS Code 실행 구성에 `CLOUDFLARE_R2_ENABLED=true`를 추가했다. 일반 HTTP·쿠키 설정과 자격 증명 없는 환경의 기본 비활성을 유지한다.

`mvn -o -pl molebutter-app -am -Dtest=R2ActivationConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`: 4개 통과. 실제 일반 설정의 비밀 import를 제거한 임시 사본을 ConfigData로 읽어 기본 비활성, cloudflare 활성, 환경 변수 false 우선, 기본 프로필의 환경 변수 true 활성 및 기존 HTTP 설정 유지를 검증했다. 테스트는 가짜 자격 증명을 사용하며 객체 요청을 하지 않는다.

실행 앱과 같은 classpath·작업 디렉터리의 최소 Spring 컨텍스트로 기존 설정을 바인딩해 공개·비공개 SDK 클라이언트 2개와 버킷 연결을 확인했다. 자격 증명 값은 출력하지 않았다. 기존 앱을 정상 종료하고 같은 실행 명령·환경에 R2 활성화 값만 추가해 재실행했으며 로컬 `/login`의 HTTP 200을 확인했다. 실제 이미지 업로드는 수행하지 않았다. 재시작으로 임시 조회·생성 이미지가 초기화되므로 상품을 다시 조회하고 생성 이미지를 다시 추가해야 한다.

### 상품 이미지의 직접 경로와 중복 업로드 차단 (2026-10-08)

공개 저장 경로를 `products/{저장 상품코드}/{파일명}`으로 변경해 UUID 중간 폴더를 제거했다. `사이즈.png`·`상품정보.png`와 같은 종류의 추가 번호는 유지한다. 상품 prefix에 기존 파일이 하나라도 있으면 업로드 전에 거절하며, 이전 UUID 하위 폴더도 포함한다. 기존 객체는 이동·삭제하지 않는다. 중복·접수 전 조회 실패의 HTTP 오류는 확인창과 이미지·상품코드를 유지하며 자동 쓰기 없이 사용자 재시도를 받는다.

상품 단위 동시 보호는 비공개 고정 예약의 조건부 생성·ETag 교체로 처리한다. 서버 발급 세대를 작업에 보관하고 요청 ID·세대가 일치할 때만 예약 확인·해제를 허용해 지연된 쓰기와 조회가 다음 예약을 해제하지 못한다. 응답 유실은 미확인 상태로 차단하고, 공개 파일의 크기·MIME·SHA-256·작업 ID가 일치할 때만 저장을 확정한다. 서버 결과 기록 만료·재시작 이후에도 기존 공개 prefix 또는 진행 예약을 검사한다.

- `mvn -o -pl molebutter-app -am -Dtest=ProductImageUploadServiceTest,ProductImageUploadReservationsTest,ProductImageControllerTest,ProductImageUploadHttpTest -Dsurefire.failIfNoSpecifiedTests=false test`: 46개 통과. 직접 경로·생성 파일명, 기존/구형 prefix 거절, 권한·조회 실패, 동시 획득·지연 해제·세대 보호, 부분 성공 차단, 응답 유실 확인 및 HTTP 409/503 계약을 모의 저장소로 검증했다.
- `mvn -o -pl molebutter-storage -am test`: 50개 통과. 조건부 생성·ETag 교체·SDK 요청과 설정 경계를 모의 S3 응답으로 검증했다.
- `scripts/test-product-images-ui.cjs`: Chrome 2560px·1440px·390px 통과. 직접 공개 URL·조회/저장 코드 분리, 중복 안내 후 코드 수정, 접수 전 조회 실패와 수동 재시도, 입력·선택 유지, 응답 유실·새로고침 복원 및 기존 생성·정렬·휴지통·다운로드 회귀를 확인했다.

실제 R2 객체 쓰기·이동·삭제는 수행하지 않았다. 기존 UUID 경로의 객체는 그대로 유지되며 같은 상품의 중복 판정에 포함된다.

`mvn -o -DskipTests package`와 `git diff --check`를 통과했다. 실행 중인 개발 앱을 기존 명령·환경·R2 활성화 설정으로 정상 재시작하고 새 프로세스의 8081 리스너와 `/login` HTTP 200을 확인했다. 업로드 동작은 모의 응답으로 검증했으며 실제 R2 업로드 버튼은 누르지 않았다. 다른 작업의 기존 변경 파일 260개를 기준으로 이번 범위 밖 파일의 해시가 유지됨을 확인했다.


### 스마트스토어 전용 등록·수정 (2026-10-09)

프로젝트 최신 네이버 SOURCE(2.90.0, 수집 2026-10-03)와 공식 current 2.90.1을 대조했다. 일반 무옵션·1~3축 조합형 등록, 기존 상품 선택 변경 병합, 원상품·채널상품 순차 저장, 옵션 UUID 연결, 이미지 네이버 CDN 변환, 접수와 승인·반영 구분을 구현했다. 공통 문서의 기존 네이버 입력은 전용 입력으로 임의 변환하지 않는다. 그룹상품·특수 옵션 구조/재고·미지원 고시 유형·비편집 상태는 안내 후 제한한다.

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -o -pl molebutter-marketplace -am test`: 마켓 모듈 204개 통과. 토큰 form/bcrypt/Base64, 401 갱신·429 제한·쓰기 응답 유실, 로컬 파일 multipart·호스트/DNS 제한, SOURCE 매핑, 원문 보존, 숫자 0·옵션 UUID·이미지 순서, 비활성 고시 제외, 계정 라우팅·이전 실행 JSON·초안 목록 호환을 확인했다. HTTP 테스트는 localhost 가짜 서버를 사용했다.
- `mvn -o -pl molebutter-app -am -Dtest=NaverProductControllerTest,LayoutViewTest,ModuleArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false test`: 10개 통과. ADMIN 경계 위임·입력 allowlist·원본 응답/외부 ID 입력 거절·고정 오류 안내·페이지 렌더링·모듈 경계를 확인했다.
- `MOLEBUTTER_COUPANG_LIVE=false CLOUDFLARE_R2_ENABLED=false bash scripts/test-integration.sh -pl molebutter-app -am '-Dtest=NaverProductFlowIT,MarketplaceSubmissionFlowIT,MarketplaceEditingFlowIT,MarketplaceDraftFlowIT,AuthenticationFlowIT#marketplaceRequiresCurrentAdministrator' -Dsurefire.failIfNoSpecifiedTests=false`: 격리 MySQL·Redis 49개 통과. NaverProductFlowIT 5개와 기존 쿠팡·공통 흐름 44개이며 실 API 대신 네트워크 클라이언트가 없는 가짜 gateway를 사용했다. 불완전 임시 저장·revision 충돌·변경 없음·중복 실행·응답 유실 뒤 읽기 확인·ID 연결·새 CREATE 차단·외부 변경 충돌·저장 후 재조회와 기존 기능을 확인했다.
- `node --test src/test/js/naver-product-editor.test.cjs src/test/js/marketplaces.test.cjs src/test/js/common-marketplace-editor.test.cjs`: 35개 통과. `NODE_PATH=<Playwright 설치 경로> node scripts/test-naver-product-ui.cjs`: PC 1440px·모바일 390px의 가짜 API 브라우저 검증 통과. 옵션·이미지 전환, DOM 드래그 삽입 표시·대표 교체, 주소록·고시·인증 입력, 파일 업로드 실패·재시도, 미변경 저장 무전송, 충돌 입력 유지, 저장 확인·실행·결과 확인·초안 재진입을 확인했다. 모바일 실제 터치 제스처는 자동화하지 않았다.

검증 중 발견한 누락 옵션 숫자의 null 언박싱, 목록 기본 페이지 크기 불일치, JSON 재조회 숫자 타입 차이로 인한 반영 대기, 원상품 저장 후 자신의 변경을 외부 충돌로 오인하는 문제를 수정하고 회귀 검증했다. `request_json`을 실제로 직렬화·역직렬화한 값과 HTTP JSON 응답을 비교한다.

실제 스마트스토어 토큰 발급·상품 조회·등록·수정·이미지 업로드는 수행하지 않았다. 커머스 앱 권한·허용 IP·실계정 승인 완료는 모의 검증 결과에 포함되지 않는다. 실행 설정은 [스마트스토어 안내](market-api/naver-smartstore.md#연결-설정)를 따르며, 별도 지정 상품·변경값 실행 요청 이후 실계정 검증을 진행한다.


최종 `mvn -o -DskipTests package`와 `git diff --check`를 통과했다. 기존 개발 앱의 실행 명령·작업 디렉터리·R2 활성화 환경을 보존해 정상 재시작하고 `/login`, 스마트스토어 JS·CSS 의 로컬 HTTP 200과 등록 페이지의 로그인 이동을 확인했다. HTTP 페이지 확인은 외부 API 성공을 의미하지 않는다. 시작 전 다른 작업의 기존 변경 파일 263개를 해시로 기록했고 범위 밖 파일은 유지했다.

## 판매 마켓 모듈 분리 검증 (2026-10-09)

새 media·쿠팡·스마트스토어 모듈을 포함한 전체 reactor `clean test`로 이동 전 클래스 파일을 제거했다. legacy 실행 JSON의 누락 schemaVersion을 nullable 기본값으로 수정한 뒤 전체 Java 단위/계약 테스트 750개가 통과했다. 모듈 위치·공개 계약 의존·순환 금지와 자산 소유권·참조 핀 9개, 기존 마켓 필드 매핑·숫자·JSON 호환·미래 버전 차단·이동한 workflow 테스트를 포함한다.

임시 MySQL·Redis의 쿠팡 실행 21개·편집 15개·초안 7개·스마트스토어 5개·주문 수집 16개·관리자 권한 1개, 총 65개가 통과했다. 운영 DB를 변경하지 않고 외부 마켓 응답은 가짜로 제공했다. JavaScript 52개와 실제 Thymeleaf 화면의 1440px·390px 브라우저에서 공통 편집·스마트스토어 등록/수정·주문 화면을 확인했다. 공통 UI 테스트의 네이버 표시명을 중앙 채널 목록의 스마트스토어로 맞췄다.

외부 자격 증명·실제 판매 상품·R2 객체·공식 SOURCE를 변경하지 않았다. 실제 판매 마켓 호출·승인 완료나 다른 마켓 어댑터 구현을 검증한 기록은 아니다. 마지막 실행 JAR 패키징 성공, 기존 환경을 보존해 새 모듈 클래스패스를 포함한 개발 서버를 재기동하고 로그인 보호/정적 파일 응답을 확인했다.

## 판매 마켓 통합 인증 설정 (2026-10-09)

`application-secret/marketplace.properties`를 classpath·실행 디렉터리에서 선택적으로 읽도록 연결했다. 새 쿠팡·스마트스토어 키를 기존 서버 내부 설정에 매핑하고 네이버 환경 변수 우선순위·SELF 기본값을 유지한다. 11번가·ESM·롯데ON은 API 키 설정만 연결하며 API 기능을 활성화하지 않는다. 명시적 주문 조회 테스트의 import와 실행 안내도 통합 파일명으로 갱신했다.

`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home MOLEBUTTER_COUPANG_LIVE=false MOLEBUTTER_ORDER_LIVE=false CLOUDFLARE_R2_ENABLED=false mvn -o -Dmolebutter.build-directory=target/credential-verification -pl molebutter-app -am -Dtest=MarketplaceCredentialConfigurationTest,R2ActivationConfigurationTest,CoupangClientTest,NaverGatewayTest -Dsurefire.failIfNoSpecifiedTests=false test`: 40개 통과(설정 5·R2 설정 4·쿠팡 인증/조회 계약 20·네이버 인증 계약 11).

통합 설정 테스트는 실제 일반 application.properties의 사본에서 비밀 import를 제거하고, application-secret 밖의 임시 가짜 파일을 Spring ConfigData로 읽는다. 호스트 환경·시스템 설정을 격리해 누락 파일 기본값, 아홉 키 매핑, 이전 일반 쿠팡 키 제외, 네이버 환경 변수 우선순위와 canonical 설정 우선순위를 확인했다. 비밀 파일을 직접 열거나 수정하지 않았으며 실제 마켓 요청·운영 DB 변경·개발 서버 재시작은 수행하지 않았다. 검증 빌드는 별도 디렉터리를 사용했다.

### 통합 파일명 수정 (2026-10-09)

두 마켓의 조회에서 연결 설정 안내가 발생했다. 디렉터리 메타데이터로 실제 파일명은 `marketplace.properties`, 기존 import는 `maketplace.properties`임을 확인했다. optional import가 누락을 허용해 앱은 기동했지만 인증값이 비어 있었다. 일반 설정·명시적 live 테스트의 import·가짜 설정 테스트·구현 안내를 실제 파일명으로 수정했다.

별도 `target/credential-filename-verification` 빌드에서 설정 5·R2 설정 4·쿠팡 인증/조회 계약 20·네이버 인증 계약 11, 총 40개 테스트가 통과했다. 처음 실행은 샌드박스의 로컬 socket 제한으로 중단되어 로컬 가짜 HTTP 서버를 허용한 실행에서 다시 검증했다. 실제 마켓 호출은 비활성화했다.

추가로 컴포넌트·DB·스케줄러를 실행하지 않는 임시 Spring ConfigData 진단으로 수정된 일반 설정의 import를 확인했다. 통합 파일 import 및 쿠팡·네이버 필수 설정의 존재 여부만 모두 true로 확인했고 값은 출력하지 않았다. 비밀 파일을 직접 열거나 수정하지 않았으며 마켓 인증 성공·실제 상품 조회는 이 확인에 포함하지 않는다. 실행 중인 개발 서버는 재빌드·재시작 후 새 import를 적용한다.
