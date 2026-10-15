# 등록 결과 확인·공통 연결·쿠팡 실행 사용자 설정

2026-10-13. 실제 DB/스마트스토어 상품은 읽기만 수행했다. 운영 서버 재시작·DB 변경·마켓 상품 등록/수정·이미지 업로드는 실행하지 않았다.

## 실제 진단

- 공통 상품 `897779631605467192`, 네이버 실행 `897797804715779378`는 `ACCEPTED / REFLECTION_PENDING`이었다.
- DB 연결은 이미 존재했다. 원상품 `13747403202`, 채널상품 `13808601939`, 공통 옵션 UUID `6bad7d85-2c0c-4027-bb57-1359f2ff2863` → 외부 옵션 `60374079811`. 실행 완료 revision은 미확정이었다.
- 인증된 `GET /v2/products/origin-products/13747403202`에서 `SALE`, 판매가 128000원, 즉시할인 41000원(WON), 옵션 추가금 0원을 확인했다. 전체 응답·자격 증명은 출력하거나 기록하지 않았다.
- 보관된 CREATE 전문과 현재 응답의 판매가·상세설명·검색 모델명이 달랐다. 기존 구현은 모든 등록 필드의 일치까지 요구하여 등록 후 변경/서버 정규화가 있으면 계속 대기했다.
- 쿠팡 런타임에서 `coupang_access_id`는 업체코드 형식이며 별도 Wing 사용자 설정은 없었다. 실제 설정값이나 비밀 파일은 읽거나 출력하지 않았다.

## 수정

네이버 CREATE는 성공 응답에서 확보한 원상품 ID를 인증 조회하고 상품/채널 ID와 옵션 연결을 확인한다. 현재 값이 다르더라도 확인 가능한 판매 상태이면 `CONFIRMED / CREATED_VALUES_DIFFER`로 등록·연결을 완료하고 차이를 안내한다. 모든 입력이 반영됐다고 표시하지 않는다. 응답 유실로 ID가 없거나 조회 실패·식별자 불일치·승인 대기·옵션 연결 미확인은 계속 미확정으로 유지한다. UPDATE는 기존 선택 변경값의 반영 검사와 충돌 처리를 유지한다. 같은 CREATE를 다시 보내지 않는다.

완료 시 기존 실행 저장소가 연결과 완료 revision을 기록한다. 공통 기준을 외부값으로 덮어쓰지 않는다. 연결 상품의 수정 진입/다른 탭 최초 조회는 외부 원상품 ID로 GET한 값을 마켓 폼에 투영하며, 기존 임시 변경은 기존 재적용/충돌 정책을 따른다. 사용자가 입력 중인 화면을 자동으로 덮어쓰지 않으며 실행 기록의 상품 수정 링크 또는 재진입으로 최신값을 조회한다.

쿠팡은 서버 설정 `marketplace.coupang.vendor-user-id`를 `COUPANG_VENDOR_USER_ID` 또는 `coupang_vendor_user_id`에서 읽는다. 업체코드 `coupang_access_id`와 구분한다. 신규 전문에는 서버 값을 넣고 과거 초안/브라우저의 `vendorUserId`는 무시한다. 기존 상품 수정은 최신 서버 조회의 사용자 ID를 유지한다. 입력 검증에서도 신규 등록에 설정이 없으면 안내한다. 편집 폼과 미리보기 변경 목록에서 사용자 ID 입력/표시를 제거했다. 비밀 파일은 수정하지 않았다.

## 근거와 실측 대조

- 쿠팡 [상품 생성 SOURCE](../../marketplace-api-docs/coupang/api/products/product-creation.md): `vendorId` 업체코드, `vendorUserId` 업체 소속 Wing 실사용자 ID.
- 쿠팡 [식별자 FAQ SOURCE](../../marketplace-api-docs/coupang/faq/auth/where-can-i-find-vendorid-vendors-id-and-vendoruseridwing-users-id.md).
- 네이버 [등록 응답 SOURCE](../../marketplace-api-docs/naver/schemas/상품-등록-수정-응답.md), [원상품 조회 SOURCE](../../marketplace-api-docs/naver/api/product/get-v2-products-origin-products-originProductNo.md).
- 실제 GET 결과와 기존 실행 스냅샷을 수정한 코드에 메모리에서만 대조하여 `CONFIRMED / CREATED_VALUES_DIFFER`, 옵션 연결 1개를 확인했다. 운영 실행 상태는 아직 변경하지 않았으며 재시작 후 결과 확인 대상이다.

## 실행한 검증

- 단위/컨트롤러 테스트 178개: NaverWriteGatewayTest 44, CoupangWriteGatewayTest 61, CoupangProductDocumentsTest 10, CoupangClientTest 22, NaverSubmissionRoutingTest 7, MarketplaceTargetEditingTest 21, MarketplaceProductEditorTest 10, LayoutViewTest 3. 모두 실패/오류 0.
- 격리 통합 77개: NaverProductFlowIT 18, MarketplaceEditingFlowIT 27, MarketplaceSubmissionFlowIT 32. 등록 직후 조회 실패 → 외부 가격 변경 → 결과 확인 성공 → 공통/옵션 연결 및 완료 revision → 수정 재진입의 실제 GET 값 사용을 새 시나리오로 검사했다. 같은 CREATE의 추가 전송은 없었다.
- 모의 Chrome 통합 편집기 1440/390px: 연결/가져오기, 탭 최초 조회, 임시 저장·재진입, 기존 Wing ID 필드 숨김 통과. 실제 사용자 Chrome이 Wing 창으로 전환되어 추가 조작하지 않았다.
- 운영 실측은 인증 GET과 DB SELECT만 실행했다. 수정된 결과 판정은 메모리에서 검사했으며, 운영 실행 기록 완료 처리는 사용자의 서버 재시작 후 ‘결과 확인’으로 수행한다.
