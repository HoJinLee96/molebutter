# at-a-glance 상품코드 네이버 검색 제한 조사

> 문서 유형: 과거 조사·설계·검증 기록. 수치·외부 응답·적용 여부는 기록 당시 기준이며 현재 실행 지침이 아니다. 개인 경로와 target 산출물도 당시 근거로 보존한다. 현재 정책과 실행 방법은 [문서 목차](README.md)에서 확인한다.

조사일: 2026-09-27. `/Users/leehj/workspace/at-a-glance`의 현재 로컬 소스와 설정을 확인했다. 실제 실행 프로세스의 환경변수·명령행 오버라이드 및 운영 DB 상태는 확인하지 않았다. 아래 수치는 네이버 공식 허용량이 아니라 이 프로젝트에서 정한 제한이다. 이번 작업은 문서 조사이며 설정이나 애플리케이션 코드를 변경하지 않았다.

## 검색 제한

| 항목 | 현재 설정 및 동작 |
| --- | --- |
| 검색 방식 | 전용 Chrome/CDP, 비로그인 브라우저 수집. `browser-search.enabled=true`, `entry-mode=organic`, `login-required=false`. 기존 `naver.api.search.shop.enabled=false` |
| 동작 간격 | 기본 3,000ms + 매번 0~1,500ms 무작위 추가. 이전 동작 시작 이후 이미 경과한 시간을 빼고 부족한 만큼만 대기. 첫 동작은 바로 실행 |
| 제한 단위 | 상품코드 한 건이 아니라 홈 진입·검색 제출·가격비교 진입·정렬/페이지 크기 조작·페이지 이동 등 `acquireAction()`을 거치는 동작 |
| 공유 범위 | 동일 `egressId`의 모든 브라우저 슬롯, 같은 JVM 내부. 실제 IP를 자동 탐지하는 제한이 아니라 설정된 회선 ID로 묶음. 다른 JVM과 동작 간격을 공유하는 분산 제한은 아님 |
| 워커 | `worker-01` 1개. 두 번째 슬롯의 worker-id는 공란으로 비활성화. 두 슬롯의 회선 설정은 `home-primary` |
| 페이지 크기 | 80개 |
| 상품등록 단발 검색 | 1페이지 기본. 실사용 매입처 필터를 통과한 표시 가능 오퍼가 10개 미만이고 다음 페이지가 있으면 최대 2페이지 |
| 등록 상품 동기화 검색 | 다음 페이지가 있고 등록된 링크의 상품 ID 중 아직 관측하지 못한 것이 있을 때 추가 페이지 수집, 최대 5페이지 |
| 검색 작업 수 | `max-searches-per-window=7500`. 구현상 추가 페이지 작업을 만들기 전에 해당 run의 job 수를 검사하는 상한 |
| 동일 코드 중복 | 같은 run의 정규화된 검색어를 묶어 1페이지 작업 하나를 만들고 여러 상품에 결과를 연결 |
| 작업 재시도 | `job-max-attempts=3`. 첫 페이지는 실패 횟수 3에 도달하면 FAILED. 추가 페이지 실패는 부모 작업을 재등록해 같은 검색 흐름에서 재시도하는 별도 처리 |
| 예약 검색 | 서울 시간 18:00 하루 1회. 새 pipeline 스케줄러가 `full-sync-times`를 사용하며 legacy scheduler는 꺼져 있음 |

**3~4.5초는 상품 한 건을 완료하는 시간이나 모든 HTTP 요청 사이의 보장 간격이 아니다.** 브라우저 동작 하나가 여러 리소스/XHR 요청을 만들 수 있으며 상품코드 한 건에도 여러 동작이 필요하다. 확인한 검색 경로에는 재고 API처럼 30분/60분 이동 구간별 호출 예산이 없다.

`7500` 역시 전체 네트워크 호출의 하드 상한이 아니다. 초기 1페이지 작업을 일괄 생성하는 `NaverSearchRunFactory`에는 이 상한 검사가 없고, 동일 작업 재시도도 새로운 job 수로 세지 않는다. `shadow-sample-max-queries=1436`은 별도 검증 run의 고유 검색어 상한이며 운영 검색의 일일 상한이 아니다.

## 차단·대기 기준

- 설정상 반복 제한 1회는 600초(10분), 2회는 1,800초(30분), 이후는 3,600초(1시간) 대기한다.
- 반복 횟수가 8 이상이면 회선을 중단하고 수동 재개를 요구한다. 즉 8번째에도 자동 대기하는 것은 아니다.
- 같은 fingerprint의 제한 신호가 15초 안에 반복되면 같은 사건으로 묶어 단계 상승을 중복 계산하지 않는다.
- 한 번의 성공 응답으로 반복 제한 횟수를 초기화하지 않는다.
- CAPTCHA·요청률 제한·로그인 벽을 제한 처리 경로로 보내며, 로그인 벽을 재로그인으로 해결하지 않는다.
- 제한 화면은 최대 15초 동안 0.5초 간격으로 상태를 다시 확인한다. 사람이 CAPTCHA를 해소할 수 있는 관찰 시간이지 자동 해제 보장은 아니다.
- 직접 진입의 제한 HTML/418에 대해서는 5초 후 새로고침 최대 1회 설정이 있다. CAPTCHA·429·로그인 벽은 이 새로고침 재시도 대상이 아니다. 현재 기본 진입은 organic이므로 이를 모든 검색에 적용되는 재시도라고 해석하면 안 된다.

### 대기 단계와 초기화의 충돌

`NaverSearchEgressState.nextRestrictionStreak()`는 마지막 제한 시각으로부터 **30분 이상 지나면 1로 초기화**한다. 대기 종료 후 정상 재시도하는 단일 워커 경로에서는 다음 순서가 가능하다.

1. 첫 차단 → 10분 대기.
2. 10분 후 재차 차단 → 30분 대기.
3. 30분 후 재차 차단 → 마지막 차단으로부터 30분이 지났으므로 다시 1회로 계산 → 10분 대기.

따라서 설정 의도인 10분 → 30분 → 1시간 및 8회 중단을 실제로 순차 적용한다고 보장할 수 없다. 이는 코드와 설정을 대조한 정적 분석이며 운영 로그로 재현한 결과는 아니다. 다른 상품 작업으로 넘어가더라도 회선 반복 횟수는 공유된다. 이 정책을 가져올 때에는 초기화 기준과 단계별 대기 시간을 함께 검토해야 한다.

## 이 수치를 선택한 근거

`application.properties`에 남은 2026-08-07/08 운영 주석에 따르면:

- 2초 간격 실험은 10/10 통과했지만 일시 제한 회복에 수분이 걸려 3초로 여유를 늘렸다.
- 당시 건당 소요시간 기록은 간격 2초일 때 8.1초, 3초일 때 9.3초, 4초일 때 11.9초, 6초일 때 17.3초였다. 현재 동선·추가 지연·페이지 수에서의 처리량 보장으로 사용할 수는 없다.
- 제한 후 3분만 쉬고 다시 접근했을 때 CAPTCHA로 악화되어 첫 대기를 1분에서 10분으로 올렸다.
- 기존 하루 10시/18시 두 번에서 18시 한 번으로 줄였다. 주석에 기록된 당시 대상 규모는 1,436건이다.

이는 저장된 주석의 실험 기록이며 이번 조사에서 재실험하거나 네이버 공식 정책으로 확인한 내용은 아니다.

## 상품 상세·재고 API 제한은 별도

| 항목 | 설정 |
| --- | --- |
| v2 요청 시작 간격 | 같은 JVM 전체에서 6초, AUTO/MANUAL 공유. 동시 대기 시 MANUAL 우선 |
| AUTO 예산 | 최근 30분 300회, 최근 60분 600회 |
| MANUAL 예산 | 최근 30분 50회, 최근 60분 100회 |
| 집계 | 요청 직전 DB permit 및 호출 기록. 성공 건수만 세는 방식이 아님 |
| 비정상 응답 대기 | 최근 30분 `NON_JSON` 판정 5회 이상이면 scope 공통 30분 cooldown |

검색 단발 `MANUAL_SEARCH`는 v2를 호출하지 않아 v2 예산 대기에서 제외한다. 상품 동기화는 검색 이후 v2가 필요하므로 작업을 꺼내기 전 v2 예산 상태도 확인할 수 있다. 즉 검색 자체의 할당량과 후속 재고 조회 때문에 발생하는 대기를 구별해야 한다.

## 소스 위치

- 설정과 실험 기록 — `/Users/leehj/workspace/at-a-glance/src/main/resources/application.properties:129`
- 검색 동작 간격 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/service/NaverSearchEgressActionLimiter.java:25`
- 브라우저 동작별 제한 적용 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/infra/product/NaverShoppingBrowserSearchClient.java:1543`
- 페이지 진행 기준 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/service/NaverSearchJobQueueService.java:288`
- 7500 상한 검사 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/service/NaverSearchJobQueueService.java:583`
- 검색어별 초기 작업 생성 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/service/NaverSearchRunFactory.java:347`
- 제한 단계와 수동 재개 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/service/NaverSearchEgressStateService.java:109`
- 30분 반복 횟수 초기화 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/domain/NaverSearchEgressState.java:110`
- 재고 요청 간격 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/infra/product/NaverShoppingChannelProductRateLimiter.java:30`
- 재고 호출 예산 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/service/NaverV2PermitGate.java:100`

위 절대경로와 줄 번호는 조사 당시의 소스 위치 기록이다. 다른 환경에서 열리는 링크가 아니며, 당시 조사 결론과 근거 위치를 보존한다.
