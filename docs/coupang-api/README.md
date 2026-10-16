# 쿠팡 판매 연동

판매 마켓은 쿠팡 Wing 하나다. [공식 자료 읽기 기준](../../coupang-api-docs/README.md), [쿠팡 구현 안내](coupang-wing.md), [상품 편집](working-copies.md), [주문](sales-orders.md), [전송 복구](submission-recovery.md)를 따른다.

상품 조회·등록·수정, 옵션·가격·판매 가능 수량, 이미지·상세설명, 배송·반품 설정, 주문·클레임 조회·수집을 지원한다. 내부 저장과 실제 쿠팡 전송은 별도 동작이다. 실제 보유 재고를 쿠팡의 판매 가능 수량으로 덮어쓰지 않는다.

`molebutter-coupang` 하나가 `coupang.api` 공개 계약과 `coupang.internal` 업무·저장, `coupang.internal.client` HTTP 통신을 소유한다. app은 화면·HTTP 컨트롤러·인증을 조립한다. 기존 `application-secret/marketplace.properties`는 런타임에 읽으며 비밀 파일을 직접 열거나 수정하지 않는다.

[V48 수정 의도·감사 이력 전환](../coupang-intent-migration.md)과 [V47 작업본 구조](../coupang-working-copy-migration.md)를 확인한다. 매입용 네이버 공개 검색은 판매 연동과 별개로 유지한다.

앱의 현재 경로는 [HTTP 표](endpoints.md), 재기동·설정 키·권한·백업은 [운영 기준](../operations.md)을 따른다.
