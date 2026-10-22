# 쿠팡 상품 표·선정 매입처 펼침·검색 동기화 검증

실행일: 2026-10-16 (Asia/Seoul). 이전 결과: [상품 목록·임시저장 UI](2026-10-16-coupang-products-ui.md).

## 변경

- 선택 체크를 첫 번째 독립 열로 옮기고 판매자상품코드를 이미지 바로 오른쪽의 볼드 버튼으로 배치했다. 표는 선택부터 수정까지 12열이다.
- 상단 상품 등록·전체 가져오기·선택 상품 갱신의 높이 40px, 패딩 10px/14px, 글꼴 14px·굵기 600·행간 1.25와 간격 8px를 통일했다.
- 옵션 행 아래에 같은 전체 상품코드의 활성 상품과 선정 매입처를 펼친다. 공통 `product-supplier-summary.js`를 기존 상품 화면과 쿠팡 화면에서 재사용한다. 다중 옵션은 독립적으로 열고 닫으며 체크 변경에는 펼침을 보존한다.
- 공개 상품 조회 포트와 `GET /api/products/by-code`를 추가했다. 기존 권한·상품 DTO·선정 조회 경로를 사용하며 정규화한 전체 코드의 정확 일치, 삭제·병합 제외, ID 역순·20개 페이징을 제공한다. 악센트 무시 정렬로 다른 코드가 섞이지 않도록 바이너리 비교한다.
- 카드 클릭은 판매상태·재고 기본값을 입력하고 다른 검색 조건은 유지한다. 수동 검색은 기본 상태·재고 조합이면 상태 카드를, 다른 재고 조합이면 상품 카드를 선택한다. 삭제 범위는 별도로 유지한다.
- 자동 재고 조건과 사용자 입력을 구분해 판매중→판매중지 전환 시 자동 재고만 제거한다. 초기화는 카드 기본값을 유지한다. 목록 복귀에는 실제 조회에 적용한 자동 조건의 출처를 함께 복원한다.
- 새 테이블·마이그레이션·영구 연결을 만들지 않았다. 펼침은 DB 읽기만 수행하며 선정 변경·외부 최신화·자동관리·삭제 기능을 추가하지 않았다.

## 실행 결과

| 검사 | 결과 |
|---|---|
| 전체 Java 단위·구조·화면 렌더링 | 726개 통과, 실패·오류·생략 0 |
| JavaScript | 203개 통과, 실패·생략 0 |
| 정확 코드 조회 `ProductFlowIT` 사례 | 1개 통과 |
| `CoupangRegisteredProductsIT` | 7개 통과 |
| 별도 출력의 패키징·verify | 통과 |
| `test-coupang-suppliers-ui.cjs` | 1920/1440px PC·390px 모바일 통과 |
| `test-coupang-products.cjs` | 기존 목록·상세·명시적 수집/취소/재개 통과 |
| `test-product-ui.cjs` | 1440px PC·390px 모바일의 기존 상품·설정·쿠팡 상세 통과 |
| `git diff --check` | 통과 |

정확 코드 검사는 공백·대소문자 정규화, 부분 코드와 악센트가 다른 코드 제외, 기존 중복 22개, 삭제·병합 제외, 미선정·선정 가격/재고 보존, 20개 페이징과 HTTP 미인증/권한을 확인했다. 신규 등록은 중복 코드를 거절하므로 중복 사례는 격리 DB에서 기존 중복 데이터 상황을 재현했다.

화면 검사는 두 옵션의 독립 펼침, 상품 단위 체크 선택, 더 보기의 중복 제거, 조회 실패·재시도, 닫은 뒤 늦은 응답 무시, 미선정·선정 판매글 미발견, 상품 상세 링크와 쓰기 기능 부재를 확인했다. 카드 입력·브랜드 유지·판매중→판매중지·사용자 재고 보존·복합 검색·삭제 범위·초기화, 세 버튼의 계산된 스타일과 전체 페이지 가로 넘침도 검사했다.

공통 렌더러 분리 후 기존 상품 화면의 매입처 선정·재고 조회·최신화·미저장 입력 보존·등록/수정/삭제·설정 권한을 검사했다. 이전 쿠팡 탭·외부 페이징 토큰을 사용하던 모의 화면 검사는 현재 DB 목록·페이지·저장 상세 계약으로 맞췄으며 상세 이미지 실패 대체·옵션 선택·HTML 격리·CDN HTTPS 표시 검사를 유지했다.

## 재현과 범위

```sh
JAVA_HOME=/path/to/jdk21 ./mvnw -o -Dmolebutter.build-directory=target/ui-cleanup test
node --test src/test/js/*.test.cjs
bash scripts/test-integration.sh '-Dtest=ProductFlowIT#exactCodeLookupIsReadOnlyPagedAndPreservesSelectedSupplier,CoupangRegisteredProductsIT' -Dsurefire.failIfNoSpecifiedTests=false
node scripts/test-coupang-suppliers-ui.cjs
node scripts/test-coupang-products.cjs
node scripts/test-product-ui.cjs
JAVA_HOME=/path/to/jdk21 ./mvnw -o -Dmolebutter.build-directory=target/ui-cleanup -DskipTests verify
```

별도 임시 MySQL·Redis, 모의 API, 실제 Thymeleaf 렌더링과 프로젝트 CSS/JS를 사용했다. 운영 DB·실행 중 IDE 앱·기본 `target/classes`는 변경하지 않았다. 실제 쿠팡이나 매입처 외부 조회·쓰기를 실행하지 않았다. 적용은 IDE 재실행으로 확인한다.

캡처: `molebutter-app/target/ui-check/coupang-suppliers-{1920,1440,390}.png`.
