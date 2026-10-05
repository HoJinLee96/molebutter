# 테스트 실행과 유지 기준

핵심 로직은 Java·JavaScript 테스트로 검증하고, 브라우저에서는 실제 템플릿과 화면 이벤트가 연결되는 대표 흐름만 확인한다. 운영 DB·계정과 외부 쇼핑몰은 사용하지 않는다.

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
NODE_PATH=/path/to/node_modules node scripts/test-inventory-ui.cjs
NODE_PATH=/path/to/node_modules node scripts/test-notifications-ui.cjs
```

`test-integration.sh`는 Bash 스크립트다. 별도 임시 MySQL·Redis를 시작하고 테스트 종료 시 종료·삭제한다. Python은 사용 가능한 포트를 찾는 데만 사용한다. Maven은 기본적으로 오프라인 캐시를 사용하며, 의존성을 처음 받아야 하면 `MOLEBUTTER_TEST_MAVEN_OFFLINE=false`를 지정한다.

브라우저는 실제 화면 코드와 로컬 모의 API를 검증한다. 모의 API의 수량 결과는 서버 동시성 검증을 대신하지 않으며, 해당 검증은 Java 통합 테스트가 담당한다. 외부 응답 자료는 `test-fixtures/product` 아래에서 관리하고 필요한 검증에서만 참조한다. 브라우저 스크린샷은 무시되는 `target/ui-check`에 생성한다.

## 마켓 연동 전 기반 정리 검증

`InventoryFlowIT`는 같은 요청의 동시 주문 생성·입고 재시도와 서로 다른 주문의 병렬 처리, 카탈로그 변경 대기, 재고 실패 알림의 역할 변경, 미등록 API의 기본 거절을 확인한다. `InventoryInputTest`는 역할별 응답 계약에서 알려지지 않은 열을 제외하는지 확인한다. `ExceptionPrivacyTest`는 거절 입력·예외 원인·쿼리 문자열이 응답과 로그에 노출되지 않고, 애플리케이션이 작성한 입력·충돌 안내의 400·409 응답은 유지되는지 확인한다.

`AuthenticationFlowIT`는 HTTP 감사의 요청 식별자·실제 계정과 백그라운드 감사의 커밋/롤백을 확인한다. `ProductFlowIT`는 SUCCESS·PARTIAL·SOLD_OUT·과거 NO_MATCH의 완료 감사와 FAILED·STALE의 실패 감사, 중복 완료 방지를 확인한다. 근태 정정 승인·취소의 알림 검증은 하나의 흐름으로 합치고, 실패 롤백·중복 요청·입력 오류 및 상품 API 권한 검증은 유지한다. `MigrationUpgradeIT`는 V26 설치에서 V27 요청 직렬화와 V28 감사 컨텍스트를 적용하고 기존 수량·단가·HTTP 감사 행이 보존되는지 검증한다. 실제 판매 마켓 계정은 이 테스트에서 호출하지 않는다.

## 다중 모듈 검증

기존 Java 검증 442개와 JS 검증 47개를 기준으로 기능 검증을 유지한다. 단위 테스트는 소유 모듈, 전체 통합·화면 검증은 app에서 실행한다. `ModuleArchitectureTest`는 허용 의존성·공개 API 접근·업무 패키지 순환을 확인한다. `MigrationUpgradeIT`는 V28→V29→V30 이전과 활성 작업 거절, 값 불일치 시 삭제 거절, JSON null·버전·삭제/통합 상품·재고 값 보존을 함께 확인한다. 전체 구조와 운영 적용 절차는 [업무 모듈과 DB 책임](modular-architecture.md)을 따른다.

최종 JAR을 먼저 `./mvnw -DskipTests package`로 생성한 후 `MOLEBUTTER_TEST_PACKAGE_SMOKE=true bash scripts/test-integration.sh`를 실행하면 같은 임시 DB·Redis에서 실행 JAR의 HTTP 기동·자원·마이그레이션 로딩과 두 상태 복구 명령의 미리보기까지 검증한다. 비밀 설정·테스트 자료·테스트 JAR·ArchUnit이 배포 JAR에 포함되지 않았는지도 확인한다. 초기화·예약 워커와 외부 메일 발송은 실행하지 않는다.

전환 전후의 실제 실행 결과와 자료 대조는 [다중 모듈 전환 검증](modular-transition-verification.md)에 기록했다.

## 모듈 경계 보강 검증

`ProductFlowIT`는 공개 catalog 계약을 직접 호출해 코드·브랜드·중복·버전·통합 대상 검증을 확인한다. 잠금 없는 호출, 읽기 전용 변경, 공유→배타 승격, 가드 행 누락과 잘못된 DataSource를 거절하고 `REQUIRES_NEW`·롤백 후 상태가 섞이지 않는지도 확인한다. 브랜드만 추론할 때 과거 상품코드와 조회 기준은 유지한다. `InventoryFlowIT`는 상품 통합의 마지막 참여 단계에서 실패해도 상품·매입처·재고·이력이 모두 복원되는지 확인한다. `ModuleArchitectureTest`는 알 수 없는 패키지·모듈 소유 불일치·제네릭 내부 타입 노출을 탐지하는 음성 사례를 포함한다.

보강 전후 실행 결과와 유지 범위는 [모듈 경계 보강 검증](module-boundary-reinforcement.md)에 기록한다.
