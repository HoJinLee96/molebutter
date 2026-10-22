# 쿠팡 전체 가져오기 현황 표시

검증일: 2026-10-19. 화면·로컬 모의 API 검증이며 운영 DB·실행 중인 IDE 앱·실제 쿠팡 판매값은 변경하지 않았다.

## 변경

기존 화면은 수집 중 버튼만 `가져오는 중…`으로 바꾸고 서버의 단계·건수는 표시하지 않았다. 상품 카드와 검색 조건 사이에 전체 가져오기·선택 갱신 공통 현황 패널을 추가했다.

- 기존 DB 상태 API의 `phase`, `status`, `listComplete`, `discovered`, `completed`, `failed`, `updatedAt`만 사용한다. 서버·공개 계약·테이블·마이그레이션은 변경하지 않는다.
- 목록 확인 → 삭제 목록 → 상세·가격·재고 → 누락 상품 확인 단계를 표시한다. 발견·처리·성공·실패는 옵션이나 API 횟수가 아닌 등록상품 수다. 처리 수는 성공(`completed`)과 실패의 합계다.
- 전체 목록 확인 중에는 미정 진행 표시를 사용한다. 이후 현재 발견 수에 대한 처리 비율을 표시하고 누락 확인 시 대상이 추가될 수 있음을 안내한다.
- 활성 작업은 1.5초 간격으로 기존 상태 API만 조회한다. 새로고침·재진입은 최근 작업을 복원하고 완료·일부 실패·중단·취소 결과는 패널에 유지한다.
- 조회 실패는 기존 건수를 보존하고 패널 내부 오류·다시 조회를 제공한다. 활성 작업의 오류 후 조회 간격은 5초다. 수집 POST는 자동 재시도하지 않는다.
- 취소·재개·새 요청은 이전 상태 조회 세대를 무효화한다. 늦은 응답과 페이지 이탈 후 응답은 상태를 덮어쓰거나 폴링을 다시 시작하지 않는다. 중복 클릭 잠금과 명시적 FormActions 실행을 유지한다.

쿠팡 수집 알고리즘과 외부 요청은 바꾸지 않았으며 현황 표시를 위한 추가 쿠팡 API 호출은 없다. 문서 SOURCE·원문은 수정하지 않았다.

## 실행 결과

| 검사 | 결과 |
|---|---|
| Java `LayoutViewTest`, `CoupangUnifiedViewTest` | 6개 통과 |
| JavaScript 전체 | 255개 통과, 실패·생략 0 |
| 실제 Thymeleaf + 모의 API | 1920·1440·390px 통과 |
| 수집 중 새로고침 | 작업·단계·건수 복원, 새 수집 POST 없음 |
| 상태 조회 실패·수동 재조회 | 마지막 건수 유지, GET만 재시도 |
| 지연 응답 | 취소 이후 RUNNING 응답으로 되돌아가지 않음 |
| 종료 상태 | 완료·일부 실패·실패·중단·일시 중단·취소 결과 유지 |
| 키보드·반응형 | Enter/Space 버튼 실행·모바일 줄바꿈·가로 넘침 없음 |
| diff 공백 검사 | 통과 |

Java는 IDE 출력과 분리한 `target/collection-status-check`를 사용했다. `LayoutViewTest`가 생성한 `target/ui-fixtures` HTML과 프로젝트 JS·CSS를 모의 서버에서 읽는다. 브라우저는 외부 트래픽을 차단하며 쿠팡 상품 화면의 기존 검색·필터·이미지 확대·상세·충돌 해결·취소/재개 회귀도 함께 실행한다. 실제 운영 수집이나 처리 속도 검증을 뜻하지 않는다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  CLOUDFLARE_R2_ENABLED=false MOLEBUTTER_COUPANG_LIVE=false MOLEBUTTER_ORDER_LIVE=false \
  ./mvnw -o -Dmolebutter.build-directory=target/collection-status-check -pl molebutter-app -am \
  -Dtest=LayoutViewTest,CoupangUnifiedViewTest -Dsurefire.failIfNoSpecifiedTests=false test
node --test src/test/js/*.test.cjs
node scripts/test-coupang-products.cjs
git diff --check
```

현재 환경에서는 번들 Node·Playwright를 사용했다. 모의 화면 캡처는 `/tmp/coupang-collection-progress-{1920,1440,390}.png`다.

## 종료 현황 닫기 후속 변경

2026-10-19 후속 요청으로 종료된 현황 오른쪽에 닫기 버튼을 추가했다. 같은 탭에서 닫은 작업의 ID·상태·갱신 시각을 보존해 새로고침 후에도 숨기며 서버 작업·이력은 수정하지 않는다. 새 수집·재개·진행 중 상태와 새로운 작업/갱신 결과는 다시 표시한다. 진행 중 작업에는 닫기 버튼을 제공하지 않는다.

후속 회귀는 Java 화면 6개, JavaScript 전체 257개, 1920·1440·390px 모의 화면으로 닫기 Enter·새로고침 후 숨김·새 작업 표시·진행 중 닫기 차단과 가로 넘침을 확인한다. 완료 화면은 `/tmp/coupang-collection-finished-{1920,1440,390}.png`에 생성한다. 운영 DB·실행 중인 IDE 앱·실제 쿠팡 판매값은 변경하지 않는다.
