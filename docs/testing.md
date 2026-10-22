# 테스트 실행과 유지 기준

다른 상품의 일괄 전송 복구 기록이 새 선택을 막지 않는지와 복구 자료 보존은 [2026-10-22 선택·복구 분리 검증](verification/2026-10-22-coupang-bulk-recovery-scope.md)을 따른다. 단위 검사·격리 DB에서 동일 상품 UNKNOWN 차단/다른 상품 완료, 모의 PC·모바일에서 A 기록 보존/B만 전송/명시적 A 복구를 확인한다.

모든 쿠팡 호출의 시작·응답·실패·대기 시간과 로그 레벨·민감정보 제외는 [전체 호출 로그 검증](verification/2026-10-21-coupang-api-logging.md)을 따른다. `CoupangApiLoggingTest`가 첫 성공·재시도·응답 없는 실패·서로 다른 응답 봉투와 2초 간격을 검사한다. 2026-10-21 전체 Java·격리 DB 회귀는 1,185개 통과·5개 조건부 생략, 실패·오류 0개다.

자동 재개 대기 알림의 단계·대기 시간은 `ProductFlowIT`에서 HTTP 418·429·보안 확인의 1~4단계, 최종 수동 중단·중복 방지·로그인 대기·검증 재확인을 검사한다. 2026-10-21 격리 MySQL·Redis에서 131개 통과(실패·오류·생략 0), `scripts/test-notifications-ui.cjs`의 PC 1440px·모바일 390px 단계·원인·예정 시각 표시와 알림 회귀도 통과했다. 운영 DB·실행 중 앱은 변경하지 않았다.

모든 연속 쿠팡 호출의 응답 수신 후 최소 2초 대기는 [전체 호출 간격 검증](verification/2026-10-21-coupang-all-response-gap.md)을 따른다. 가격·재고 PUT → 조회, GET·읽기 POST·DELETE, 오류 응답·전송 실패·재시도 보호 조회와 서로 다른 작업 세션에서의 간격을 검사한다.

등록·승인 성공 응답 뒤 후속 요청의 이전 2초 검증은 [2026-10-21 응답 간격 검증](verification/2026-10-21-coupang-approval-response-gap.md)에 보관한다. 신규 등록의 현재 5초 간격과 처리 중·승인 대기의 자동 GET 지속은 [자동 결과 확인 검증](verification/2026-10-21-coupang-approval-auto-lookup.md#후속-수정-처리-중에도-자동-get-계속)을 따른다. `scripts/test-coupang-approval-auto-ui.cjs`는 PC·모바일에서 RUNNING 표시·수동 결과 확인 버튼 없음·앱 상태 폴링으로 자동 완료를 검사한다.

신규 등록 호출 축소·규격 재사용·상품코드 조회 제거·완료 관측 재사용은 [2026-10-21 검증](verification/2026-10-21-coupang-creation-queries.md)을 따른다. `node scripts/test-coupang-creation-queries-ui.cjs`는 PC/모바일 모의 화면에서 규격 만료·명시적 갱신·미리보기·입력 보존·추가 조회 없는 완료를 검사한다.

판매기간 원문 보존·고시유형 조회 실패/복원은 [2026-10-20 표시 일치 검증](verification/2026-10-20-coupang-period-notices.md)을 따른다. `node scripts/test-coupang-period-notices-ui.cjs`는 PC/모바일 모의 API로 날짜·오류·혼합 고시유형·지연 응답을 검증하며 실제 쿠팡에 요청하지 않는다.

신규 쿠팡 등록 실패 후 재진입과 고시 유형 저장은 [2026-10-19 신규 등록 재진입 검증](verification/2026-10-19-coupang-create-reentry.md)을 따른다. 재기동 후 브랜드 재검증의 중첩 잠금·타 작업 배제, 확정 거절의 CREATE 유지와 고시 유형 변경·임시 저장·새로고침을 모의·격리 환경에서 검사한다.

네이버 보안 확인·HTTP 418·429의 단계별 자동 대기와 최종 수동 중단은 [2026-10-19 접속 제한 대기 검증](verification/2026-10-19-naver-restriction-backoff.md)을 따른다. 실제 네이버 호출 없이 고정 시계·격리 DB·모의 Chrome에서 검사한다.

쿠팡 거절 사유의 민감정보 제거·기록 복구·단계 로그는 [2026-10-19 상세 거절 사유 검증](verification/2026-10-19-coupang-rejection-details.md)을 따른다. 응답 원문이나 실계정 전송 없이 모의 응답·격리 DB·PC/모바일 화면을 검사한다.

선택한 상품의 작업 범위 보호는 [2026-10-19 선택 상태 안전성 검증](verification/2026-10-19-selection-safety.md)을 따른다. 일괄 완료 후 5개가 사라지고 1개를 새로 선택하는 사례, 페이지 밖 삭제·자동관리·브랜드 대상 제외, 빈 선택 차단, 이전 전송 복구 격리와 R2 재조회 선택 제거를 모의 환경에서 검사한다.

쿠팡 전체 가져오기의 단계·건수·새로고침 복원·상태 조회 실패와 지연 응답 검증은 [2026-10-19 수집 현황 표시](verification/2026-10-19-coupang-collection-progress.md)에 기록한다. 기존 DB 상태 응답을 이용하며 추가 쿠팡 요청 없이 로컬 모의 화면으로 검사한다.

사진 사이즈 편집의 실물 비교는 `SizeGuideComparisonRendererTest`, `SizeGuideServiceTest`, `ProductImageControllerTest`, `src/test/js/size-guide-comparison.test.cjs`, `scripts/test-size-comparison-ui.cjs`에서 검사한다. 화면 검사는 Java가 만든 실제 PNG fixture와 모의 API를 사용하며 운영 DB·R2·판매 API를 사용하지 않는다. 실행 기록은 [2026-10-18 사이즈 비교 검증](verification/2026-10-18-size-comparison.md)을 따른다.

배율 자동 맞춤의 확대 상쇄와 삼다수 사진 교체 회귀는 [2026-10-19 배율·누끼 검증](verification/2026-10-19-size-comparison-zoom.md)에 기록한다. 배치 요청만 비교하지 않고 자동 맞춤 구간의 실제 PNG 상품 픽셀 증가를 검사한다.

비교 선택 전후의 최종 배율 일관성과 현재 본체 영역 맞춤은 [2026-10-19 출력 배율 검증](verification/2026-10-19-size-output-scale.md)에 기록한다. 큰 흰 여백·치우친 상품의 실제 PNG 픽셀 보존, 필수 축소 후 배율·재편집 일치, 고정된 슬라이더 범위와 표 영역 보호를 검사한다.

상품 이미지의 경로별 중복 제외·누락 파일 추가와 예약 해제 복구는 [2026-10-17 업로드 검증](verification/2026-10-17-product-image-upload-duplicates.md)에 기록한다. `ProductImageUploadServiceTest`, `ProductImageUploadReservationsTest`, `ProductImageUploadHttpTest`와 `scripts/test-product-images-ui.cjs`에서 모의 R2·PC/모바일 회귀를 수행하며 실제 객체를 변경하지 않는다.

일괄 수정 모달의 DB 표시·묶음 문맥·전송 직전 최신 확인은 [쿠팡 중복 조회 정리 검증](verification/2026-10-17-coupang-bulk-api-queries.md)에 기록한다. `CoupangWorkingCopyFlowIT.bulkDbContextsGroupOptionsAndReuseUnsavedMappingWithoutExternalReadsOrIntents`, `CoupangWorkingCopyWriteTest`, `src/test/js/coupang-bulk-edit.test.cjs`, `scripts/test-coupang-bulk-edit-ui.cjs`에서 초기 원격 조회 0회·단일 항목 준비·최신값/코드 충돌 차단·중복 전송 보호를 검사한다.

쿠팡 목록 조회 비용 개선의 최신 실행은 [2026-10-17 조회 최적화 검증](verification/2026-10-17-coupang-query-performance.md)에 기록한다. 목록 SQL 계측은 `CoupangRegisteredProductsIT.listHydratesTwentyOrHundredOptionsWithConstantQueryCountAndNoDetailPayloads`, 기존/개선 이력 판독 대조는 `ProductFlowIT.retainedStockKeysetMatchesHistoricalReaderAcrossPagesAndTransfersOnlySelectedEvidence`, 100상품 배치 경계와 즉시 재판정은 `ProductFlowIT.recommendationEvidenceBatchOverHundredProductsKeepsExactCountsAndPreviousStock`에서 검사한다. 운영 응답시간과 합성 데이터의 조회 비용 수치는 구분한다.

Java 21과 프로젝트 Maven wrapper를 사용한다. 판매 연동은 쿠팡 전용이며 실제 외부 쓰기는 일반 검증에 포함하지 않는다. 최신 실행 결과는 [매입 가능 상태·보유 재고 추천과 반응형 목록 검증](verification/2026-10-17-coupang-availability-responsive.md), 이전 결과는 [선정 매입처 펼침·검색 동기화 검증](verification/2026-10-16-coupang-supplier-rows.md), 이전 결과는 [상품 목록·임시저장 UI 검증](verification/2026-10-16-coupang-products-ui.md), 이전 결과는 [등록상품 저장소 검증](verification/2026-10-16-coupang-registered-products.md), 이전 결과는 [구매·검색 속성 구분 재검토](verification/2026-10-16-coupang-attribute-identity.md), 이전 결과는 [V47 커밋·중단 재검토](verification/2026-10-16-coupang-v47-rereview.md)다. 현재 데이터 보존 전환은 [V49 절차](coupang-products-migration.md)이며 이전 전환은 [V48 절차](coupang-intent-migration.md)를 따른다. 이전 검증 기록은 각 기록의 이전 결과 링크로 확인한다.

사진 사이즈 편집의 2880×1880 출력·다중 비교·과거 단일 선택 호환은 [2026-10-21 고해상도·다중 비교 검증](verification/2026-10-21-size-multi-comparison.md)에 기록한다.

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

검색어 최초 자동 입력·재생성·직접 입력 보존과 문자열 배열 전송은 [2026-10-20 검색어 검증](verification/2026-10-20-coupang-search-tags.md)에 기록한다. `CoupangSearchTagsTest`, `CoupangPrefillServiceTest`, `CoupangSearchTagsControllerTest`, `CoupangWorkingCopyWriteTest`, 격리 `CoupangWorkingCopyFlowIT`와 `scripts/test-coupang-prefill-ui.cjs`에서 검사한다.

```sh
node --test src/test/js/*.test.cjs
node scripts/test-modal-scroll-ui.cjs
node scripts/test-coupang-only.cjs
node scripts/test-coupang-new-working-copy.cjs
node scripts/test-coupang-orders-ui.cjs
node scripts/test-coupang-submissions.cjs
node scripts/test-coupang-intent-ui.cjs
node scripts/test-coupang-save-settlement.cjs
node scripts/test-coupang-navigation.cjs
node scripts/test-coupang-products.cjs
node scripts/test-coupang-suppliers-ui.cjs
node scripts/test-coupang-draft-loader.cjs
node scripts/test-notifications-ui.cjs
node scripts/test-coupang-attribute-identity.cjs
```

Playwright와 Chrome이 필요하다. Java 검사에서 생성하는 `molebutter-app/target/ui-fixtures`의 실제 Thymeleaf 화면과 프로젝트 JS·CSS를 사용한다. 화면만 검사할 때는 먼저 다음 명령으로 fixture를 만든다.

```sh
./mvnw -o -pl molebutter-app -am -Dtest=LayoutViewTest,CoupangUnifiedViewTest -Dsurefire.failIfNoSpecifiedTests=false test
```

1440px PC와 390px 모바일에서 쿠팡 CREATE·UPDATE 편집, 단일 쿠팡 입력 폼과 수정 의도, 임시 저장·재진입·실패 후 입력 보존과 `workingCopyId`와 수정 항목 요청을 확인한다. 입력창 Enter와 native submit은 쓰기를 실행하지 않으며 버튼에 초점을 둔 Enter·Space는 실행한다. 상세설명의 줄바꿈·옵션 선택·한글 조합 규칙은 [입력 동작 기준](ui-interaction-rules.md)을 따른다.

주문 검사는 큰 문자열 ID·0수량·개인정보 상세 닫기·수집 진행·취소·실패 재개·클레임 전용 주문과 가로 넘침을 다룬다. 전송 검사는 미리보기에서 실행 없음, 중복 방지 키 재사용, 성공 단계 재실행 금지, 실패 후 입력 수정, UNKNOWN·승인 대기 결과 확인과 페이지 이동을 다룬다. 브라우저 스크립트는 로컬 모의 API를 사용하고 외부 트래픽을 차단한다.

내부 상품·비교 편집 HTML·JS와 그 계층의 테스트를 제거하고 작업본 계약 검증으로 교체했다. 공유 `coupang-editor-model.js`의 계약 검사는 유지하며 현재 통합 화면의 동작은 위 JavaScript·PC/모바일 검사로 확인한다.

`coupang-attribute-identity.test.cjs`는 이름·노출 구분별 규격 채우기·택1 선택·수정 권한·입력 검증·수정 의도 복구를 검사한다. `test-coupang-attribute-identity.cjs`는 같은 이름의 구매·검색속성을 두 순서로 배치하고 구매 권한 true/false/null을 PC·모바일에서 확인한다. 구매값만 저장·재진입·미리보기에 포함되고 최신 검색값은 유지되는지, 필수 표시가 섞이지 않는지, 신규·기존 상품의 택1 전환이 검색행을 지우지 않는지 검사한다. 렌더링만으로는 의도를 만들지 않아야 한다. `CoupangWorkingCopyWriteTest`는 서버 조회 권한의 노출 구분과 실제 모의 PUT의 미수정 검색행 보존·권한 재확인을, `CoupangWorkingCopyFlowIT`는 DB 저장·새 세션 복구의 같은 이름 검색값·외부 사이즈 보존을 검사한다.

보존 기능의 화면 회귀는 `scripts/test-product-ui.cjs`, `test-inventory-ui.cjs`, `test-notifications-ui.cjs`, `test-product-images-ui.cjs`, `test-image-crop-editor.cjs`, `test-r2-image-picker.cjs`를 사용한다. 네이버 공개 검색·매입처 조회는 판매자 연동과 별개이며 관련 fixture를 보존한다.

## 유지보수 명령과 실행 JAR

```sh
./mvnw -o package
bash scripts/test-sales-reset.sh
bash scripts/test-coupang-domain-upgrade.sh
bash scripts/test-coupang-working-copy-upgrade.sh
bash scripts/test-coupang-intent-upgrade.sh
bash scripts/test-coupang-products-upgrade.sh
MOLEBUTTER_TEST_PACKAGE_SMOKE=true bash scripts/test-integration.sh \
  -Dtest=CoupangOnlyMigrationIT -Dsurefire.failIfNoSpecifiedTests=false
```

유지보수 검사는 합성 DB와 가짜 이미지로 전체 백업·격리 복원·행 지문 대조·FK 순서·이미지 백업·재실행·보존 감사를 수행한다. 실제 초기화 명령과 구분하며 운영 데이터에 사용하지 않는다.

`CoupangV47PreparationIT`는 원래 V47의 중복 URL·본문 실패를 재현하고 오프라인 관측 입력 준비 → 불변 V47 → 준비 입력 제거 → V48을 검사한다. 활성 임대·계정 불일치·중복 상품·조회 근거 누락의 무변경 거절, 준비 후 중단 표식과 완료 대조 실패도 검사한다. 원본 초안·실제 수정 의도·이미지 참조·공개 토큰의 지문을 대조한다. `CoupangMediaIdentityTest`는 일대일 매칭·역할/세부 타입·신규 행 ID 충돌·정확한 공개 URL의 로컬 참조를 검사한다. `test-coupang-working-copy-upgrade.sh`에는 동일 URL 이미지와 동일 본문의 연결 상품을 포함해 실제 명령의 전체 백업·복원·감사 동일성·재실행을 검사한다. 일반 Flyway 실행으로 과거 V47을 고쳤다고 해석하지 않는다.

`CoupangV47CommitIT`는 실제 격리 MySQL에 JDBC 커밋 응답 실패를 주입한다. 준비 커밋의 실제 성공/미성공과 롤백 성공/실패, 정리 대조 실패·커밋 실패, 성공한 정리 커밋의 응답 유실을 구분한다. 롤백 실패 뒤 autocommit을 다시 켜지 않는지와 새 DB 연결에서 잔여 표식·감사 지문을 확인한다. 별도 JVM을 정리 삭제 후 커밋 전에 종료해 롤백·재실행 거절을 확인한다. 호스트 전원 차단 검사와 구분한다. 감사·파일 대조를 정리 커밋 뒤로 옮기면 응답 유실 사례가 실패해야 한다.

최종 JAR은 `molebutter-app/target/molebutter-0.0.1-SNAPSHOT.jar` 하나다. packaged smoke는 격리 MySQL·Redis에서 HTTP 기동·정적 자원·마이그레이션과 상태 복구 명령의 미리보기를 검사한다. 비밀 파일·테스트 fixture·테스트 라이브러리와 제거된 판매 모듈이 포함되지 않아야 한다. 인증 설정은 기존 런타임에서 읽으며 `application-secret/` 파일을 직접 열거나 수정하지 않는다.

## 기록 해석

사이즈 비교 대상의 사진·내부 모델명 검증은 [2026-10-19 제품 사진 교체](verification/2026-10-19-comparison-product-photos.md)에 기록한다. 기존 기준 치수·출력 배율·v1 편집본 호환과 본체 내부 캡션 경계를 함께 검사한다.

테스트 개수·성공·실계정 조건 생략·실행 날짜를 검증 기록에 함께 남긴다. 코드 검사, 모의 전송, 격리 DB 검증, 실제 로컬 DB 전환과 실제 외부 호출을 구분한다. 판매 가능 수량은 쿠팡 값이며 내부 보유 재고를 자동으로 변경하지 않는다. R2 객체와 매입 브라우저 프로필은 초기화 대상이 아니다.

재검토 회귀는 완료 직후 목록 갱신 전 저장의 이전 가격 의도 재저장, 중복 URL·설명과 외부 목록 변경의 ID 충돌, 미수정 서류의 두 경로·인증 누락 필드·고시 외부 추가 행 보존을 포함한다. 이전 편집 세션 형식은 입력 보존 후 갱신을 검사한다. 추가된 서버 세션 정보 때문에 DB 마이그레이션을 만들지 않으며 V1~V48을 수정하지 않는다.

후속 회귀는 지연된 저장 응답과 전송 완료 조회를 동시에 진행해 입력 잠금·저장 후 재조회·완료 가격 의도 제거를 PC·모바일에서 검사한다. 최신 조회의 지원하지 않는 미디어 주소는 저장 문서 복구와 전송 차단을 검사한다. 같은 이름의 구매·검색 속성은 행 구분·미수정 최신 값·해당 행의 수정 권한을 검사한다.

이미지 정리는 `ImageAssetCleanupTest`와 격리 MySQL의 `ImageAssetCleanupIT`로 현재·감사·편집 원본 참조, 후보 조회 뒤 추가된 핀, 커밋 롤백과 결과 불명확, 파일 삭제 실패·100개를 넘는 표식의 재시도를 검사한다. 별도 JVM을 DB 삭제 커밋 직전·직후에 강제 종료하고 새 정리 객체로 DB·파일을 복구한다. 호스트 전원 차단을 검사했다는 의미는 아니다. 잘못된 쿠팡 JSON·세션 누락은 400, 존재하지 않는 세션은 기존 404 코드, 지원하지 않는 Content-Type은 415로 응답한다. `AuthenticationFlowIT`는 실제 HTTP에서 공백·null·여러 JSON 값·잘못된 자료형·Content-Type의 응답과 감사 기록, 요청 사용자만 받는 실패 알림을 대조한다. DB/파일 오류의 경로·원문을 응답이나 알림에 포함하지 않는다.

구조화된 JSON(`application/*+json`)도 일반 JSON과 같은 입력 검사·크기 제한·원본 바이트 보존을 검사한다. 불투명한 수정값의 문자열을 과거 계약 필드로 오인하지 않아야 한다. 확정 업로드 롤백 뒤 파일 삭제가 실패하면 표식으로 남기고 새 정리 객체에서 복구하는 경로도 검사한다.

`test-sales-reset.sh`는 V45 당시 초기화 동작과 보존 검사를 위한 합성 스키마 검사다. 현재 V47+ DB를 초기화할 수 있다는 의미가 아니다. 현재 DB는 감사 FK를 보존하고 [V48 전환](coupang-intent-migration.md)을 따른다. 신규 DB 전체 생성과 각 데이터 보존 전환은 기존 IT로 검사하며 적용된 마이그레이션을 수정하지 않는다.

쿠팡 상품 카드·옵션 조건의 동시 적용·계정 전체 집계는 `CoupangRegisteredProductsIT`에서 검사한다. 신규 작업본 필터·건수·페이징과 수집 결과의 요청자 알림 저장·시도별 중복 방지·조회 시 미발행·롤백·임대 만료 복구는 `CoupangWorkingCopyFlowIT`에서 검사한다. `test-coupang-products.cjs`는 1920/1440px PC·390px 모바일에서 6px/18px 공통 표 밀도, 카드, 이미지 확대, 수정 링크, 명시적 수집·취소·재개를 검사한다. `test-coupang-draft-loader.cjs`는 조회 실패·취소·이탈 취소·미저장 입력 복구와 UPDATE에서 불러오기 숨김을 검사한다. `test-notifications-ui.cjs`는 수집 결과를 알림함에 표시하면서 자동 토스트에서만 제외하는지 검사한다.

선정 매입처 펼침은 `test-coupang-suppliers-ui.cjs`에서 1920/1440/390px의 다중 옵션 펼침·닫힘·체크 보존·더 보기·실패 재시도·지연 응답·카드 입력 동기화·자동 재고와 사용자 재고 구분·삭제 범위·상단 버튼 계산 스타일을 검사한다. `ProductFlowIT#exactCodeLookupIsReadOnlyPagedAndPreservesSelectedSupplier`는 임시 DB에서 코드 정규화·부분/다른 코드 제외·기존 중복·삭제·병합·20개 페이징·선정 매입처·HTTP 권한을 검사한다. `test-product-ui.cjs`는 공통 렌더러를 사용하는 기존 상품·설정 및 쿠팡 DB 상세의 이미지/설명 격리를 검증한다.

옵션 빠른 수정은 `CoupangPricingTest`, `CoupangOptionEditsTest`, `CoupangWorkingCopyFlowIT#quickEditPreparesOnlySelectedIntentAndPreservesUnrelatedChangesAfterSuccess`, `ProductFlowIT#coupangPricingSettingsAreSharedRevisionCheckedAndAdminOnly`, `CoupangPricingMigrationIT`에서 검증한다. `scripts/test-coupang-quick-edit-ui.cjs`는 기존 상품 모달의 같은 페이지 표시·미저장 입력·단일 가격/재고 미리보기·Enter/Space·UNKNOWN 보호·지연 닫기·PC/모바일을 다룬다. `scripts/test-coupang-pricing-upgrade.sh`는 합성 V49 DB를 전체 백업·격리 복원한 실제 오프라인 명령, 이미지·감사 보존과 재실행을 검사한다. 운영 DB 전환·실제 쿠팡 쓰기와 구분한다.

최신 빠른 수정 실행 결과는 [2026-10-16 상품 모달·옵션 빠른 수정 검증](verification/2026-10-16-coupang-quick-edit.md)에 기록한다. 실행 접수 후 매입처가 바뀐 경우도 `CoupangWorkingCopyFlowIT#quickEditRejectsChangedSupplierAtExecutionAndBeforeDispatch`에서 외부 요청 0·실패·수정 의도 보존을 검사한다.

수정 모달 후속 정리는 [가격·재고 수정 모달 검증](verification/2026-10-16-coupang-quick-modal.md)을 따른다. `OptionHistoryTest`와 `CoupangRegisteredProductsIT#quickHistoryFiltersBeforePagingAndDoesNotCallExternalApisOrWriteRows`는 해당 옵션·항목의 스칼라 이력, 상태 구분과 필터 후 페이징을 검사한다. `ProductFlowIT#quickStockUsesPreviousConfirmedListingAndPreservesWholeOptionCount`는 이전 정상 재고 복구·실패·수량 0·다중 옵션과 부분 미확인을 실제 격리 DB에서 검사한다. 모의 화면은 입력 출처·의도적으로 비운 입력 보존, 모달 내부 이력과 공통 이미지 오류 이벤트 격리를 포함한다.

추천 카드 회귀는 `CoupangRecommendationsTest`, `CoupangRecommendationSourcesTest`, `CoupangRegisteredProductsIT`, `ProductFlowIT`와 `scripts/test-coupang-recommendations-ui.cjs`에서 검사한다. 전체 계정 판정 후 검색·페이징, 정확한 코드와 선정 근거, 최신 재고만 사용, 이익 0 경계, 추천 검색 유지와 단일 수정 후 마지막 페이지 보정을 포함한다. 브라우저 검증은 렌더링된 템플릿·로컬 모의 API만 사용한다.

추천 카드의 실행 결과는 [2026-10-17 검증 기록](verification/2026-10-17-coupang-recommendations.md)을 확인한다.

추천 유형·간결한 비교 행 회귀는 `CoupangRegisteredProductsIT.recommendationTypesHaveGlobalOverlappingCountsAndFilterBeforePaging`, `src/test/js/coupang.test.cjs`, `scripts/test-coupang-recommendations-ui.cjs`에서 검사한다. 유형별 전체 건수와 복수 사유, 조건 적용 후 페이징, 조회 실패 시 유형 복구, 입력·목록 복귀, 사유별 수정 버튼·툴팁·한 줄 정렬을 포함한다.

쿠팡 공통 API 재시도는 `CoupangRetryTest`, `CoupangWorkingCopyWriteTest`, `SubmissionWorkerDiagnosticsTest`와 격리 `CoupangWorkingCopyFlowIT`·`CoupangRegisteredProductsIT`에서 검사한다. 응답 종료 후 1초 대기·최대 1회·새 서명·명확한 거절과 모호한 쓰기 구분·근거 재검사·실행별 로그 연결 및 최종 DB 결과 보존을 확인한다. 실행 결과는 [2026-10-19 검증 기록](verification/2026-10-19-coupang-api-retry.md)을 따른다.

정상 검색 미발견의 품절 전환은 [2026-10-20 검증 기록](verification/2026-10-20-coupang-missing-listing.md)을 따른다. `CoupangRecommendationSourcesTest`·`CoupangRecommendationsTest`·격리 `ProductFlowIT`·`CoupangRegisteredProductsIT`와 모의 추천/개별/일괄 수정 화면에서 이전 재고 1·쿠팡 49·보유 0, 실패 시 근거 유지, 발견만으로 과거 재고 복원 금지, 더 최신 정상 재고 확인, 서버 기본 수량과 사용자 입력 우선순위를 확인한다.

상품 정보 도구의 신규 등록 자동 입력·계정 기본설정·직사각형 경고와 V51은 [등록 준비 안내](./coupang-registration-prefill.md)를 따른다.

등록 자동 입력의 최근 실행·검증 범위는 [2026-10-20 자동 입력 검증](verification/2026-10-20-coupang-registration-prefill.md)에 기록한다.

### 기간 조건 + 최근 N개 등록상품 수집

`CoupangRegisteredProductsIT`, `RecentProductCollectionTest`, `CoupangClientTest`, `CoupangRecentProductsMigrationIT`와 `src/test/js/coupang.test.cjs`로 후보 비반영·날짜별 전체 페이지·최신 N개·체크포인트·레거시 수집 보호를 검사한다. `scripts/test-coupang-recent-ui.cjs`는 최신 LayoutViewTest HTML과 모의 서버에서 PC/모바일을 검사한다. 합성 데이터의 V52 전체 백업·격리 복원·오프라인 적용·재실행은 `scripts/test-coupang-recent-products-upgrade.sh`로 검증한다. [2026-10-20 결과](verification/2026-10-20-coupang-recent-products.md)와 [운영 전환 절차](coupang-recent-products-migration.md)를 구분한다.

### 브랜드별 상세설명 로고

`CoupangBrandLogosTest`는 단어/구절 판별·NFKC·별칭·복수 일치·계정 분리를 검사한다. `CoupangPrefillServiceTest`는 공통 안내/로고/상품 순서와 대표 이미지 분리를, `CoupangWorkingCopyFlowIT`는 설정 생략/해제·revision·R2 실패·작업본 purpose/UUID 보존을 검사한다. `CoupangWorkingCopyWriteTest`는 로컬 purpose가 쿠팡 전문에 섞이지 않음을 확인한다. `src/test/js/coupang-brand-logo.test.cjs` 및 `scripts/test-coupang-brand-logo-ui.cjs`로 일반 설명 보존·중복 방지·수동 교체/취소·지연 응답·새로고침·PC/모바일 회귀를 검사한다. [2026-10-20 검증 기록](verification/2026-10-20-coupang-brand-logos.md)을 참고한다.
