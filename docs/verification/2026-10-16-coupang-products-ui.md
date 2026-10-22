# 쿠팡 상품 목록·임시저장 UI 검증

실행일: 2026-10-16 (Asia/Seoul). 이전 결과: [등록상품 저장소](2026-10-16-coupang-registered-products.md).

## 변경 범위

- 좌측 메뉴를 쿠팡 상품으로 표시하고 `/coupang/products`를 기본 목록으로 유지했다. 등록상품/임시저장 탭과 패널 제목, 행별 등록상품·확인 시각, 고정 수집 안내 문단을 제거했다.
- 상품·판매중·품절·판매중지·삭제 카드를 옵션 행 기준으로 집계한다. 검색 조건과 무관한 계정 전체 `counts`를 반환하며 카드 조건과 검색 조건은 같은 옵션에 적용한다. 미확인·옵션 없는 상품과 삭제 확정 상품을 구분한다.
- 상품 등록·전체 가져오기·선택 상품 갱신을 상단 오른쪽에 배치했다. 수집 중에는 버튼 처리 상태와 취소, 중단·실패 결과에는 재개·재시도를 제공한다.
- 공통 상품 표의 6px/18px 본문 패딩과 글꼴·수직 정렬·이미지 스타일을 사용한다. 쿠팡 값에 맞는 열 너비와 표 내부 가로 스크롤을 적용했다. 옵션 자신의 HTTPS 대표이미지·첫 유효 이미지·사진 없음 순서로 44px 썸네일을 표시하고 확대·로드 실패 대체를 제공한다.
- 목록 복귀 시 카드·검색·페이지 크기·페이지·세로 스크롤·표 가로 스크롤을 복원한다. 검색 초기화는 카드를 유지한다.
- 신규 등록 화면의 임시 저장 옆에 불러오기 버튼과 모달을 추가했다. `mode=CREATE` 서버 검색·건수·20개 페이징을 사용하며 수정 작업본은 상품 수정 버튼으로 연다. 미저장 입력 보관과 이탈 확인을 유지했다.
- 수집 공개 알림 포트와 앱 어댑터를 연결했다. 요청자 알림함에 결과 건수·사유를 저장하며 해당 유형만 자동 토스트에서 제외한다. 결과 상태와 알림을 같은 트랜잭션에서 저장하고 이벤트 키로 중복을 방지한다. 저장 실패는 결과도 롤백하며 임대 만료 뒤 중단으로 복구한다.
- DB 마이그레이션을 추가하지 않았다. V49를 포함한 기존 마이그레이션과 원문 자료는 변경하지 않았다.

## 실행 결과

| 검사 | 결과 |
|---|---|
| 전체 Java 단위·구조·화면 렌더링 | 726개 통과, 실패·오류·생략 0 |
| JavaScript | 200개 통과, 실패·생략 0 |
| `CoupangRegisteredProductsIT` | 7개 통과 |
| `CoupangWorkingCopyFlowIT` | 16개 통과 |
| 최종 패키징·verify | 통과 (이미 통과한 테스트의 반복 실행은 생략) |
| `test-coupang-products.cjs` | 1920/1440px PC, 390px 모바일 통과 |
| `test-coupang-draft-loader.cjs` | 1440px PC, 390px 모바일 통과 |
| `test-notifications-ui.cjs` | 알림함 유지·쿠팡 결과 토스트 제외·기존 토스트 유지 통과 |
| `test-coupang-new-working-copy.cjs` | 최초 저장·만료/부재 복구·미저장 입력 복구 통과 |
| `test-coupang-submissions.cjs` | 명시적 확인·응답 유실·멱등키·UNKNOWN 보호 통과 |
| `test-coupang-intent-ui.cjs` | 완료 의도 정리·새 입력 보존·CREATE→UPDATE 전환 통과 |
| `git diff --check` | 통과 |

통합 검사는 별도 임시 MySQL·Redis를 생성하고 종료 후 제거했다. 카드 검사는 다중 옵션, 재고 0/1, 판매중지, 미확인, 비활성 옵션, 옵션 없는 상품, 삭제, 타 계정을 포함한다. 수집 결과는 완료·일부 실패·실패·취소·일시중지·중단, 재개 후 새 결과, 조회 시 미발행, 알림 저장 후 예외에 따른 전체 롤백을 확인했다.

화면 검사는 실제 Thymeleaf 렌더링 결과와 프로젝트 CSS/JS, 로컬 모의 API를 사용했다. 공통 표 패딩과 글꼴, 이미지 확대·로드 실패, 카드 숫자 유지, 카드 유지 검색 초기화, 우측 수정 링크, 입력 Enter 검색, 버튼 Enter/Space 수집·취소·재개를 확인했다. 임시저장 모달의 조회 실패·닫기·이탈 취소·불러오기 후 기존 신규 입력 복구와 수정 화면에서의 버튼 숨김을 확인했다.

## 재현 명령과 범위

```sh
JAVA_HOME=/path/to/jdk21 ./mvnw -o -Dmolebutter.build-directory=target/ui-cleanup test
node --test src/test/js/*.test.cjs
bash scripts/test-integration.sh '-Dtest=CoupangRegisteredProductsIT,CoupangWorkingCopyFlowIT' -Dsurefire.failIfNoSpecifiedTests=false
JAVA_HOME=/path/to/jdk21 ./mvnw -o -Dmolebutter.build-directory=target/ui-cleanup -DskipTests verify
node scripts/test-coupang-products.cjs
node scripts/test-coupang-draft-loader.cjs
node scripts/test-notifications-ui.cjs
node scripts/test-coupang-new-working-copy.cjs
node scripts/test-coupang-submissions.cjs
node scripts/test-coupang-intent-ui.cjs
```

실행 중인 IDE 앱의 기본 `target/classes`와 운영 DB를 사용하지 않았다. 실제 쿠팡 API 쓰기·운영 데이터 보정·앱 재기동은 실행하지 않았다. 적용은 IDE 재실행으로 확인한다. 모의 검증은 실제 계정 권한이나 외부 API 성공을 증명하지 않는다.

화면 캡처는 `molebutter-app/target/ui-check/coupang-products-ui-{1920,1440,390}.png`에 보관했다.
