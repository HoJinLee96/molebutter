# 검색 종료와 상품 조회 상태

검색은 최대 3페이지를 기본 범위로 사용한다. 결과가 먼저 끝나거나 3페이지까지
조회하더라도 모두 같은 정상 완료(`complete=true`, `completionReason=COMPLETED`)다.
페이지 도달 자체에 대한 별도 상태·안내·로그·예외를 생성하지 않는다.

상품 상태는 선정·선호 매입처의 실제 가격·배송비·재고 확인 결과로 집계한다.
같은 매장의 구매 가능 판매글을 확인한 뒤 의도적으로 생략한 재고는 문제로
집계하지 않는다. 실제 실패·수량 미확인·부분 옵션은 종전 판정을 유지하며,
`NO_MATCH`와 `SOLD_OUT`도 유지한다. 추천 조회 한도는 별도 안내만 남긴다.
검색 시간 초과·검증 오류·로그인·접속 제한은 정상 종료로 바꾸지 않는다.

검색 캐시·후보 분류·최신 결과와 개별 재고 조회도 같은 정상 완료 기준을 사용한다.
과거 JSON의 누락·null은 허용한다. 과거 `END_OF_RESULTS`·`PAGE_LIMIT` 및 정확한
최대 3·5페이지 안내는 읽기 호환 처리에서만 정상 완료로 해석하고, 안내를 제거한다.
새 결과에는 페이지 제한 여부를 기록하지 않는다. 원본 과거 이력은 수정하지 않는다.
근거 없는 과거 검색이나 실제 오류를 정상 완료로 추정하지 않는다.

## 기존 현재 상태 보정

`SearchStatusRepairCommand`는 스케줄러나 검색을 실행하지 않는 JDBC 유지보수
명령이다. app 실행 JAR의 모듈·의존성을 이용해 다음 순서로 실행한다. 비밀 설정 파일은 JAR 외부에 둔다.

```sh
java -Dloader.main=cc.ataglace.molebutter.procurement.internal.SearchStatusRepairCommand \
  -cp molebutter-app/target/molebutter-0.0.1-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher preview \
  molebutter-app/src/main/resources/application.properties application-secret/db.properties
java -Dloader.main=cc.ataglace.molebutter.procurement.internal.SearchStatusRepairCommand \
  -cp molebutter-app/target/molebutter-0.0.1-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher apply \
  molebutter-app/src/main/resources/application.properties application-secret/db.properties
```

미리보기는 쓰기를 하지 않는다. 적용은 각 상품의 작업·검색어·코드·조회 버전·확인
시각과 원래 선호 스냅샷을 검증하고, 공유 DB 잠금 안에서 버전과 원문 결과를 다시
확인한다. 진행 중인 상품, 수동 매장 지정이 있는 상품, 근거가 모호한 상품은 제외한다.
현재 상태, 최신 결과의 상태·안내·종료 사유와 수정 버전만 변경하며 선정·가격·재고·
확인 시각·이전 정상 결과·작업 결과·조회 이력은 보존한다. 반복 실행은 추가 변경을
만들지 않는다. 적용 로그는 상품·작업 ID와 변경 전후 상태를 기록한다.

2026-09-28 적용: 102개 중 90개를 `SUCCESS`로 보정했고, 실제 재고 문제가 남은
12개는 `PARTIAL`을 유지하며 안내를 보정했다. DCWA279BK는 확인 33건, 의도적 생략
22건으로 `SUCCESS`가 됐다. 보존 대상 값과 이력의 적용 전후 해시가 같았으며,
재실행 미리보기 대상은 0개였다. 검증 기록은
`target/search-status-repair-verification.json`에 저장했다.

코드 변경은 애플리케이션 재시작 후 이후 최신화에 적용된다. 현재 상태 보정은
DB에 이미 반영됐다. 페이지 수·검색 간격·로그인 재개 정책·실제 재고 요청 수는
변경하지 않는다.
