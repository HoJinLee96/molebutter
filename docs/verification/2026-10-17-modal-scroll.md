# 공통 모달 스크롤 검증 (2026-10-17)

## 변경

공통 head에서 `modal-scroll.js`를 읽는다. native dialog의 열림과 동적 생성·제거, role=dialog/aria-modal=true 화면을 감지한다. 첫 모달을 열 때 페이지 좌표·기존 보정 변수를 보관하고 body를 해당 위치에 고정한다. 스크롤바 제거에 따른 본문 폭 증가를 보정한다. 중첩 모달은 마지막 모달이 닫힐 때까지 잠금을 유지한다.

닫기·Escape·dialog 폼·DOM 제거에서 원래 페이지 좌표와 스타일을 복원한다. 취소된 Escape는 잠금을 유지한다. 페이지 이탈·복귀도 열린 모달 상태와 연결한다. 공통 dialog CSS는 내부 스크롤을 허용하고 경계에서 배경으로 스크롤이 연결되지 않게 한다. 이미지 확대 등 기존 모달별 overflow 설정은 유지한다.

잠금 중 `window.scrollY` 대신 `ModalScroll.position()`으로 원래 배경 좌표를 읽도록 쿠팡 목록 이동 상태 저장을 연결했다. 입력·포커스·FormActions·미저장 수정값과 기존 업무 API는 변경하지 않는다.

## 실행한 검증

- `scripts/test-modal-scroll-ui.cjs`: Chrome 1440px(일반/고정 gutter), 390px 모바일 에뮬레이션, 설치된 WebKit 1440px(일반/고정 gutter), 390px 반응형 뷰포트에서 각 케이스 통과. 배경의 실제 화면 좌표·본문 폭 고정, 모달 내부 휠, 경계 스크롤, 중첩 재고·이미지 모달, 동적 dialog, dialog 폼 닫기, 제거, Escape 취소·닫기, custom role 모달, 이탈·복귀, 입력과 스타일 복원을 검사했다.
- Chrome 모바일 에뮬레이션에서 CDP 터치 드래그로 내부 스크롤과 배경 고정을 검사했다. 실제 iOS 기기 검증은 아니다.
- WebKit은 설치된 2359 실행 파일을 `MODAL_SCROLL_WEBKIT_EXECUTABLE`로 지정했다. 모바일 WebKit 모드는 Playwright의 mouse.wheel 자동화가 지원되지 않아 390px 반응형 검증과 구분했다.
- Firefox는 설치된 1543 실행 파일을 지정했으나 브라우저가 `Could not find profile folder`로 기동하지 못했다. 임시 경로를 /tmp로 바꾼 재시도도 동일했다. Firefox 화면 통과로 기록하지 않는다. 기본 스크립트는 대응 브라우저 실행 파일이 없으면 SKIP을 표시하며 별도 실행 경로를 환경 변수로 지정할 수 있다.
- JavaScript 전체 218개 통과. 쿠팡 목록 복귀 검증은 모달 잠금으로 window.scrollY가 0이어도 원래 427px를 저장·복원하는 케이스를 포함한다.
- LayoutViewTest/CoupangUnifiedViewTest 6개 통과. 공통 모달 스크립트가 실제 렌더링 head에 포함됨을 확인했다.
- 기존 `test-product-ui.cjs`: 1440/390px 통과. 상품·중첩 재고·선정·최신화·미저장 입력·설정 회귀.
- 기존 `test-coupang-quick-edit-ui.cjs`: 1920/1440/390px 통과. 쿠팡 내부 상품 모달·중첩 재고·빠른 수정·이력·Enter/Space·UNKNOWN·입력 보존 회귀.
- `git diff --check` 통과.

운영 IDE 앱·운영 DB·실제 쿠팡 판매값은 변경하지 않았다. 브라우저 검증은 격리된 합성 화면 및 로컬 모의 API만 사용했다.
