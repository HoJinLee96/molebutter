# 무료배송 기본값 자동 입력 누락 수정

2026-10-20 한국 시각 검증.

## 원인과 수정

상품 편집에서 배송비 종류를 직접 바꾸는 이벤트는 무료배송의 기본배송비·무료배송 기준을 0원으로 만들었다. 반면 신규 등록 준비는 저장된 공통 설정을 그대로 복사해, 무료배송만 설정하고 배송비를 비워 둔 계정에서는 필수 배송비가 누락됐다. 화면은 무료배송이어도 기본배송비가 0이 아니면 입력창을 표시하고 기존 필수 검증을 적용했다.

공통 설정의 무료배송 선택·표시와 명시적 저장, 서버 신규 등록 준비에서 기본배송비 `deliveryCharge` 및 무료배송 기준 `freeShipOverAmount`를 0원으로 맞춘다. 이전 설정의 누락 또는 남아 있는 양수 금액에도 새 등록 준비에서 같은 규칙을 적용한다. 공통 설정에서 두 금액은 0원으로 비활성 표시하며 배송비 종류를 바꾸면 다시 입력할 수 있다. 기존 초도·반품 배송비는 유지하고 유료배송 금액에는 이 정규화를 적용하지 않는다.

공식 계약은 보관된 상품 생성 SOURCE의 필수 배송비 필드와 무료배송 FAQ SOURCE(`a-message-when-delivery-type-is-free-shipping-basic-delivery-fee-and-conditional.md`)의 두 필드 0원 지침을 확인했다. 문서 수집일은 2026-10-03이며 실제 쿠팡 전송 검증은 수행하지 않았다.

## 실행 결과

- Java `CoupangPrefillServiceTest`, `LayoutViewTest`: 16개 통과. 과거 설정의 빈/양수 배송비 정규화, 초도·반품비 보존 및 유료배송 회귀를 포함한다.
- JavaScript 전체 단위 검사: 275개 통과.
- 임시 MySQL·Redis `CoupangWorkingCopyFlowIT`: 29개 통과. 무료배송 저장·재조회 및 기존 계정·revision 보호를 포함한다.
- 모의 HTTP·헤드리스 Chrome 1440px/390px: 모두 통과. 신규 등록의 배송비 0원·입력창 숨김, 무료배송 공통 설정의 누락 금액 0원 표시·입력 잠금, 유료→무료 변경, 실제 모의 PUT의 두 금액 0원 및 반품비 보존을 확인했다.
- `git diff --check`: 통과.

사용 명령: `./mvnw -o -Dmolebutter.build-directory=target/free-shipping -Dtest=CoupangPrefillServiceTest,LayoutViewTest -Dsurefire.failIfNoSpecifiedTests=false test`, `node --test src/test/js/*.test.cjs`, `bash scripts/test-integration.sh -Dtest=CoupangWorkingCopyFlowIT -Dsurefire.failIfNoSpecifiedTests=false`, `node scripts/test-coupang-prefill-ui.cjs`.

운영 DB·실행 중인 IDE 앱·실제 쿠팡 상품값·R2 객체를 변경하지 않았다. 실제 공통 설정 저장이나 운영 마이그레이션도 수행하지 않았다. 기존 작업본·준비 토큰·미저장 입력은 자동 변경하지 않는다. 앱 재시작 후 새 등록 준비부터 서버 수정이 적용되며, 이미 열린 입력은 배송비 종류를 무료로 다시 선택하여 기존 편집 동작으로 0원을 적용할 수 있다.
