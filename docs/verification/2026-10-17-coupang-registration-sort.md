# 쿠팡 등록일 정렬 검증 (2026-10-17)

- 목록 API의 기본 정렬은 쿠팡 `createdAt`을 보관한 `created_at_external` 내림차순이다. `sort=CREATED_ASC`는 오래된순이다. 허용 목록 밖의 값은 거절한다.
- null·빈 문자열·공백 등록일은 두 방향 모두 마지막이며, 동률은 내부 상품 ID 내림차순·옵션 ID 오름차순이다. 정렬 후 DB에서 페이징한다. 새 테이블·마이그레이션·운영 데이터 보정은 없다.
- 페이지당 보기 바로 다음에 등록일 정렬 셀렉을 추가했다. 변경은 기존 검색 조건을 유지하고 첫 페이지로 조회하며 체크·펼침을 해제한다. 검색·카드·초기화·수정 화면 복귀에서 정렬을 유지한다.

실행 검증:

- `node --test src/test/js/*.test.cjs`: 206개 통과. 초기 최신순, 오래된순 요청, 페이지 초기화, 검색 조건·초기화·목록 복귀 보존 포함.
- `scripts/test-integration.sh -Dtest=CoupangRegisteredProductsIT -Dsurefire.failIfNoSpecifiedTests=false`: 임시 MySQL·Redis, 9개 통과. 저장 순서와 다른 등록일, 동률·다중 옵션·미확인 날짜·페이징·잘못된 정렬·외부 호출 없음 포함.
- `LayoutViewTest,CoupangUnifiedViewTest`: 6개 통과, 최신 Thymeleaf 모의 화면 생성.
- `scripts/test-coupang-products.cjs`: 모의 API·Chrome 1920/1440/390px 통과. 셀렉 위치·기본 요청·변경 요청·첫 페이지·기존 검색/명시적 버튼 동작·페이지 넘침 검사.
- `git diff --check`: 통과.

첫 격리 검증에서 미확인 날짜의 null·빈 값이 정렬 방향에 따라 서로 달라지는 것을 확인해 정렬값 자체도 NULLIF/TRIM 처리한 후 재검증했다. 화면 검증은 최신 템플릿을 생성한 뒤 실행했다. 실행 중 IDE 앱·운영 DB·쿠팡 판매값은 변경하지 않았다.
