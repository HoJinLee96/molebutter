# 쿠팡 옵션 ID 새 탭 링크 검증 (2026-10-17)

- 상품 목록·상세·편집기·가격/재고 수정 모달·주문/클레임 화면의 vendorItemId를 공통 링크로 표시한다. 상품코드 펼침, 상세 버튼, 선택 상자 등 기존 동작은 유지한다.
- productId와 vendorItemId로 사용자가 제공한 `/vp/products/{productId}?vendorItemId={vendorItemId}` 주소를 만들고 네이티브 새 탭 링크에 noopener/noreferrer를 적용한다. 긴 문자열 ID를 숫자로 변환하지 않고, 누락·비정상 ID는 안전하게 표시한다.
- 노출상품 ID가 없는 주문 화면 등은 ADMIN 권한과 현재 계정으로 저장 DB를 조회한 뒤 302 이동한다. 삭제/비활성 옵션도 보존 기록에서 찾되 노출상품 ID가 없거나 서로 다른 ID가 여러 개이면 갱신 안내 오류를 반환한다. sellerProductId를 대입하거나 쿠팡 API를 호출하지 않는다.
- JavaScript 223개, 관련 Java 8개, 격리 DB CoupangRegisteredProductsIT 11개 통과. 사용자 예시의 정확한 URL, 계정 격리, 누락·중복·잘못된 ID, 외부 호출 없음 등을 검사했다.
- PC/모바일 모의 상품 화면 1920/1440/390px에서 링크의 정확한 주소·새 탭·window.opener 없음·원래 화면 유지·상세 조회/쓰기 미실행을 검사했다. 같은 크기의 빠른 수정 모달과 1440/390px 신규 작업본 회귀도 통과했다.
- git diff --check 통과. 운영 DB·IDE 앱·실제 쿠팡 판매값은 변경하지 않았고 실제 쿠팡 페이지 접근 대신 로컬 모의 응답을 사용했다. 마이그레이션은 추가하지 않았다.
