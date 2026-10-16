# 테스트 실행과 유지 기준

Java 21과 프로젝트 Maven wrapper를 사용한다. 판매 연동은 쿠팡 전용이며 실제 외부 쓰기는 일반 검증에 포함하지 않는다. 최신 실행 결과는 [V47 커밋·중단 재검토](verification/2026-10-16-coupang-v47-rereview.md), 이전 결과는 [V47 중복 미디어 보존 수정](verification/2026-10-16-coupang-v47-media-fix.md)이다. 현재 데이터 보존 전환은 [V48 절차](coupang-intent-migration.md)를 따른다. 이전 검증 기록은 각 기록의 이전 결과 링크로 확인한다.

## 전체 Java 검증

```sh
export JAVA_HOME=/path/to/jdk21
export MOLEBUTTER_COUPANG_LIVE=false MOLEBUTTER_ORDER_LIVE=false CLOUDFLARE_R2_ENABLED=false
./mvnw -o verify
bash scripts/test-integration.sh '-Dtest=*IT' -Dsurefire.failIfNoSpecifiedTests=false
```

기본 Surefire 검사는 `*IT`를 포함하지 않는다. 통합 스크립트는 별도 임시 MySQL·Redis를 생성하고 종료 후 삭제한다. 기존 DB·Redis는 사용하지 않는다. `rg`, `mysqld`, `mysqldump`, `mysql`, `mysqladmin`, `redis-server`, `redis-cli`, `python3`가 필요하다. 의존성을 처음 받을 때는 Maven의 `-o`를 생략하고 통합 스크립트에 `MOLEBUTTER_TEST_MAVEN_OFFLINE=false`를 지정한다.

신규 DB 생성과 기존 V44 전환은 `MigrationUpgradeIT`, `CoupangOnlyMigrationIT`, `CoupangDomainMigrationIT`에서 검사한다. V45의 비어 있지 않은 판매 데이터 거절, FK 자식 우선 초기화·반복 실행, 단일 쿠팡 스키마와 보존 데이터 동일성을 확인한다. V45 → V46에서는 데이터가 있는 초안·편집 바인딩·이미지·공개 토큰·전송 증거·UNKNOWN 상태를 보존하고 실행 중 작업과 타 마켓 값은 거절한다. V46→V47은 `CoupangWorkingCopyMigrationIT`에서 수정 의도·ID·이미지·전문 보존, 중단 조건과 재실행을 검사한다. `CoupangWorkingCopyFlowIT`는 저장 전 행 미생성, 계정·revision·만료 복구와 UNKNOWN 보호를 검사한다. `CoupangIntentMigrationIT`는 V47→V48 이력 이동·증거 동일성·활성 작업 거절·재실행을 검사한다. `CoupangWorkingCopyFlowIT`는 행별 의도 복구와 부분 성공 후 완료 항목 제거도 검사한다. 실계정 조건 테스트의 생략은 실행 성공과 구분한다.

## JavaScript와 화면

```sh
node --test src/test/js/*.test.cjs
node scripts/test-coupang-only.cjs
node scripts/test-coupang-new-working-copy.cjs
node scripts/test-coupang-orders-ui.cjs
node scripts/test-coupang-submissions.cjs
node scripts/test-coupang-intent-ui.cjs
node scripts/test-coupang-save-settlement.cjs
node scripts/test-coupang-navigation.cjs
```

Playwright와 Chrome이 필요하다. Java 검사에서 생성하는 `molebutter-app/target/ui-fixtures`의 실제 Thymeleaf 화면과 프로젝트 JS·CSS를 사용한다. 화면만 검사할 때는 먼저 다음 명령으로 fixture를 만든다.

```sh
./mvnw -o -pl molebutter-app -am -Dtest=LayoutViewTest,CoupangUnifiedViewTest -Dsurefire.failIfNoSpecifiedTests=false test
```

1440px PC와 390px 모바일에서 쿠팡 CREATE·UPDATE 편집, 단일 쿠팡 입력 폼과 수정 의도, 임시 저장·재진입·실패 후 입력 보존과 `workingCopyId`와 수정 항목 요청을 확인한다. 입력창 Enter와 native submit은 쓰기를 실행하지 않으며 버튼에 초점을 둔 Enter·Space는 실행한다. 상세설명의 줄바꿈·옵션 선택·한글 조합 규칙은 [입력 동작 기준](ui-interaction-rules.md)을 따른다.

주문 검사는 큰 문자열 ID·0수량·개인정보 상세 닫기·수집 진행·취소·실패 재개·클레임 전용 주문과 가로 넘침을 다룬다. 전송 검사는 미리보기에서 실행 없음, 중복 방지 키 재사용, 성공 단계 재실행 금지, 실패 후 입력 수정, UNKNOWN·승인 대기 결과 확인과 페이지 이동을 다룬다. 브라우저 스크립트는 로컬 모의 API를 사용하고 외부 트래픽을 차단한다.

내부 상품·비교 편집 HTML·JS와 그 계층의 테스트를 제거하고 작업본 계약 검증으로 교체했다. 공유 `coupang-editor-model.js`의 계약 검사는 유지하며 현재 통합 화면의 동작은 위 JavaScript·PC/모바일 검사로 확인한다.

보존 기능의 화면 회귀는 `scripts/test-product-ui.cjs`, `test-inventory-ui.cjs`, `test-notifications-ui.cjs`, `test-product-images-ui.cjs`, `test-image-crop-editor.cjs`, `test-r2-image-picker.cjs`를 사용한다. 네이버 공개 검색·매입처 조회는 판매자 연동과 별개이며 관련 fixture를 보존한다.

## 유지보수 명령과 실행 JAR

```sh
./mvnw -o package
bash scripts/test-sales-reset.sh
bash scripts/test-coupang-domain-upgrade.sh
bash scripts/test-coupang-working-copy-upgrade.sh
bash scripts/test-coupang-intent-upgrade.sh
MOLEBUTTER_TEST_PACKAGE_SMOKE=true bash scripts/test-integration.sh \
  -Dtest=CoupangOnlyMigrationIT -Dsurefire.failIfNoSpecifiedTests=false
```

유지보수 검사는 합성 DB와 가짜 이미지로 전체 백업·격리 복원·행 지문 대조·FK 순서·이미지 백업·재실행·보존 감사를 수행한다. 실제 초기화 명령과 구분하며 운영 데이터에 사용하지 않는다.

`CoupangV47PreparationIT`는 원래 V47의 중복 URL·본문 실패를 재현하고 오프라인 관측 입력 준비 → 불변 V47 → 준비 입력 제거 → V48을 검사한다. 활성 임대·계정 불일치·중복 상품·조회 근거 누락의 무변경 거절, 준비 후 중단 표식과 완료 대조 실패도 검사한다. 원본 초안·실제 수정 의도·이미지 참조·공개 토큰의 지문을 대조한다. `CoupangMediaIdentityTest`는 일대일 매칭·역할/세부 타입·신규 행 ID 충돌·정확한 공개 URL의 로컬 참조를 검사한다. `test-coupang-working-copy-upgrade.sh`에는 동일 URL 이미지와 동일 본문의 연결 상품을 포함해 실제 명령의 전체 백업·복원·감사 동일성·재실행을 검사한다. 일반 Flyway 실행으로 과거 V47을 고쳤다고 해석하지 않는다.

`CoupangV47CommitIT`는 실제 격리 MySQL에 JDBC 커밋 응답 실패를 주입한다. 준비 커밋의 실제 성공/미성공과 롤백 성공/실패, 정리 대조 실패·커밋 실패, 성공한 정리 커밋의 응답 유실을 구분한다. 롤백 실패 뒤 autocommit을 다시 켜지 않는지와 새 DB 연결에서 잔여 표식·감사 지문을 확인한다. 별도 JVM을 정리 삭제 후 커밋 전에 종료해 롤백·재실행 거절을 확인한다. 호스트 전원 차단 검사와 구분한다. 감사·파일 대조를 정리 커밋 뒤로 옮기면 응답 유실 사례가 실패해야 한다.

최종 JAR은 `molebutter-app/target/molebutter-0.0.1-SNAPSHOT.jar` 하나다. packaged smoke는 격리 MySQL·Redis에서 HTTP 기동·정적 자원·마이그레이션과 상태 복구 명령의 미리보기를 검사한다. 비밀 파일·테스트 fixture·테스트 라이브러리와 제거된 판매 모듈이 포함되지 않아야 한다. 인증 설정은 기존 런타임에서 읽으며 `application-secret/` 파일을 직접 열거나 수정하지 않는다.

## 기록 해석

테스트 개수·성공·실계정 조건 생략·실행 날짜를 검증 기록에 함께 남긴다. 코드 검사, 모의 전송, 격리 DB 검증, 실제 로컬 DB 전환과 실제 외부 호출을 구분한다. 판매 가능 수량은 쿠팡 값이며 내부 보유 재고를 자동으로 변경하지 않는다. R2 객체와 매입 브라우저 프로필은 초기화 대상이 아니다.

재검토 회귀는 완료 직후 목록 갱신 전 저장의 이전 가격 의도 재저장, 중복 URL·설명과 외부 목록 변경의 ID 충돌, 미수정 서류의 두 경로·인증 누락 필드·고시 외부 추가 행 보존을 포함한다. 이전 편집 세션 형식은 입력 보존 후 갱신을 검사한다. 추가된 서버 세션 정보 때문에 DB 마이그레이션을 만들지 않으며 V1~V48을 수정하지 않는다.

후속 회귀는 지연된 저장 응답과 전송 완료 조회를 동시에 진행해 입력 잠금·저장 후 재조회·완료 가격 의도 제거를 PC·모바일에서 검사한다. 최신 조회의 지원하지 않는 미디어 주소는 저장 문서 복구와 전송 차단을 검사한다. 같은 이름의 구매·검색 속성은 행 구분·미수정 최신 값·해당 행의 수정 권한을 검사한다.

이미지 정리는 `ImageAssetCleanupTest`와 격리 MySQL의 `ImageAssetCleanupIT`로 현재·감사·편집 원본 참조, 후보 조회 뒤 추가된 핀, 커밋 롤백과 결과 불명확, 파일 삭제 실패·100개를 넘는 표식의 재시도를 검사한다. 별도 JVM을 DB 삭제 커밋 직전·직후에 강제 종료하고 새 정리 객체로 DB·파일을 복구한다. 호스트 전원 차단을 검사했다는 의미는 아니다. 잘못된 쿠팡 JSON·세션 누락은 400, 존재하지 않는 세션은 기존 404 코드, 지원하지 않는 Content-Type은 415로 응답한다. `AuthenticationFlowIT`는 실제 HTTP에서 공백·null·여러 JSON 값·잘못된 자료형·Content-Type의 응답과 감사 기록, 요청 사용자만 받는 실패 알림을 대조한다. DB/파일 오류의 경로·원문을 응답이나 알림에 포함하지 않는다.

구조화된 JSON(`application/*+json`)도 일반 JSON과 같은 입력 검사·크기 제한·원본 바이트 보존을 검사한다. 불투명한 수정값의 문자열을 과거 계약 필드로 오인하지 않아야 한다. 확정 업로드 롤백 뒤 파일 삭제가 실패하면 표식으로 남기고 새 정리 객체에서 복구하는 경로도 검사한다.

`test-sales-reset.sh`는 V45 당시 초기화 동작과 보존 검사를 위한 합성 스키마 검사다. 현재 V47+ DB를 초기화할 수 있다는 의미가 아니다. 현재 DB는 감사 FK를 보존하고 [V48 전환](coupang-intent-migration.md)을 따른다. 신규 DB 전체 생성과 각 데이터 보존 전환은 기존 IT로 검사하며 적용된 마이그레이션을 수정하지 않는다.
