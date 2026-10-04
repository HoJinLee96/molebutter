# 네이버 채널 분리 적용

## 저장과 판정

- `NAVER_SMART_STORE`는 기존 매장·선호·판매글 키의 호환용으로 유지한다. 현재 지원 표시명은 **네이버 쇼핑윈도**다.
- `Offer.naverChannel`과 `SourceDetails.naverChannel`에 `type` (`WINDOW`, `SMARTSTORE`, `UNKNOWN`, `CONFLICT`)과 `vertical`을 추가했다. 백화점은 `DEPARTMENT`, 브랜드패션은 `BRAND_FASHION`이다.
- 검색 응답의 상품 URL들을 함께 검사한다. 유형·상품번호가 충돌하면 요청 전에 제외한다. 일반 스마트스토어는 상세 요청하지 않는다.
- 새 상세 응답은 요청 상품번호 일치와 `channelServiceType=WINDOW`를 확인해야 한다. 브랜드 주소와 중계 주소, 판매자 이름만으로 판정하지 않는다. HTTP 204는 채널 미확인이며 품절이 아니다.
- 과거 JSON의 필드 누락/null은 명확한 쇼핑윈도 URL만으로 호환 판정한다. 새 응답의 명시적인 UNKNOWN에는 URL 대체 판정을 적용하지 않는다.
- 매장 식별자 `NAVER_CHANNEL`과 채널 상품번호는 별개다. 다른 판매글의 가격·재고를 합치거나 대체하지 않는다.

## 화면과 선정

- 확인된 쇼핑윈도만 기존 선호·추천 규칙을 적용한다. 실제 매장 미확인/충돌과 추천 가격 조건은 그대로 유지한다.
- 일반 스마트스토어와 미확인 채널은 신규 선정·매장 수동 지정으로 우회할 수 없다. 공식몰 등록도 WINDOW 응답을 요구한다.
- 기존 선정은 해제하지 않는다. 지원 밖의 선정은 `UNSUPPORTED_CHANNEL`, `current=false`로 반환하고 기존 가격·확인 시각·관측 JSON을 보존한다. 이번 선정가가 없으므로 추천을 보류한다.
- 제외한 일반 스마트스토어를 조회 실패나 재고 미확인으로 집계하지 않는다. 실제 쇼핑윈도 통신 실패·제한 정책은 유지한다.

## 전환

DB 마이그레이션이나 이력 일괄 수정은 없다. 서버를 재시작해야 Java 변경이 적용된다. 새 작업의 `selection_snapshot`에는 `naverPolicy: "WINDOW_ONLY"`를 기록한다.

전환 전 실행 중 작업은 기존 서버에서 완료하거나 취소한다. 이전 스냅샷의 진행·중단 작업을 새 서버에서 이어 실행하려 하면 차단하고 취소 후 새 실행을 안내한다. 완료 이력은 그대로 열람할 수 있다.

일반 스마트스토어의 별도 재고 API 확보와 활성화는 이번 범위에 포함하지 않았다. 실제 외부 상품에 대한 추가 요청 없이 연구의 보관 응답과 모의 응답으로 검증했다.

## 검증

- `NaverChannelPolicyTest`, `NaverSearchChannelTest`: URL·응답·상품 ID 충돌, 과거 JSON, 선호/수동 지정 우회, 상세 요청 제외.
- `SupplierTransportTest`, `NaverInventoryTest`: HTTP 204/429, 요청 차단, 기존 옵션/전체 재고 파서.
- `ProductFlowIT`: 임시 MySQL에서 기존 선정·시각·이력 보존, 권한/CSRF/버전 검사, 등록과 작업 전환.
- `products.test.cjs`: 이전 선정의 참고 표시와 선택 가능 상태를 검증한다. 브라우저는 대표 상품·설정 흐름만 유지한다([테스트 안내](testing.md)).
