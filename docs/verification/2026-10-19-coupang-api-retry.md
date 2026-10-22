# 2026-10-19 쿠팡 API 공통 재시도 검증

## 변경과 근거

기존에는 읽기·쓰기에 각각 HTTP 429 재시도만 있었다. 상품 등록·승인·가격·수량·카탈로그·주문 등의 실제 HTTP 전송을 하나의 실행기로 모아, 명확한 일시 거절은 응답 이후 최소 1초 뒤 한 번만 재시도한다. 조회의 네트워크·타임아웃·408·5xx도 포함하며, 쓰기의 모호한 실패는 재전송하지 않는다. `Retry-After`, 기존 요청 시작 간격·취소·계정 잠금과 UNKNOWN/ACCEPTED 보호를 유지한다.

상품 생성·조회·승인·옵션별 가격/수량 SOURCE와 승인 상태 FAQ, 속도 제한 공지, KNOWN-ISSUES를 확인했다. 공식 수집일은 2026-10-03이고 아래 실행일은 2026-10-19다. 등록/수정 처리 중에 대한 공식 10분 안내와 사용자 요청에 따른 1회 짧은 재확인을 구분한다. 구현 상세는 [공통 재시도](../coupang-api/retries.md)를 따른다.

## 실행 결과

- 전체 Java `verify`: **825건, 실패/오류/생략 0**.
- 격리 MySQL·Redis: `CoupangWorkingCopyFlowIT` **21건**, `CoupangRegisteredProductsIT` **14건**, 총 **35건**, 실패/오류/생략 0.
- JavaScript `node --test src/test/js/*.test.cjs`: **265건**, 실패/생략 0.
- 초기 집중 검사 86건 후 실제 클라이언트와 게이트웨이를 함께 쓰는 3개 회귀를 추가하고 위 전체 검증을 실행했다.

Java는 `JAVA_HOME=/opt/homebrew/opt/openjdk@21`, `MOLEBUTTER_COUPANG_LIVE=false`, `MOLEBUTTER_ORDER_LIVE=false`, `CLOUDFLARE_R2_ENABLED=false`로 실행했다. 출력은 `target/coupang-retry-check`와 격리 스크립트 전용 `target/verification`을 사용했다.

```sh
./mvnw -o -Dmolebutter.build-directory=target/coupang-retry-check verify
bash scripts/test-integration.sh '-Dtest=CoupangWorkingCopyFlowIT,CoupangRegisteredProductsIT' -Dsurefire.failIfNoSpecifiedTests=false
node --test src/test/js/*.test.cjs
```

로그는 `/tmp/molebutter-coupang-retry-{compile,target,verify,it,js}.log`에 있다. 위 경로는 해당 로컬 검증 실행 자료이며 영구 배포 산출물이 아니다.

## 확인한 동작

`CoupangRetryTest`는 승인 거절 후 성공, 실패 응답 종료부터 최소 1초 대기와 새 서명, 서로 다른 실패가 이어져도 총 2회에서 중단, CREATE·가격·수량의 같은 정책, HTTP 200 ERROR와 성공/형식 오류/식별자 반환의 구분을 검사한다. 쓰기 응답 유실·타임아웃·5xx·인증·권한·입력 오류에서는 추가 전송이 없다. 조회용 POST의 네트워크 재시도, 조회 400 처리 중/503 복구와 최종 실패, 긴 Retry-After, 대기 중 취소·전송 직전 근거 변경 차단, 로그 민감정보 제외를 확인했다.

`CoupangWorkingCopyWriteTest`의 추가 회귀는 실제 공통 HTTP 실행기와 게이트웨이를 연결한다. 가격·재고의 명확한 거절 뒤 전송 직전 콜백을 다시 실행하고 성공하는지, 그 사이 외부 가격이 바뀌면 두 번째 전송이 차단되는지, 승인만 재시도하면서 생성 ID를 유지하고 POST를 보내지 않는지 확인했다.

`SubmissionWorkerDiagnosticsTest`는 HTTP 진단에 사용할 실행·단계 문맥이 워커 안에서만 설정되고 종료 후 제거되는지 확인한다. 격리 DB 회귀는 최종 거절 상세의 저장·재조회, 성공 단계 보존, UNKNOWN·ACCEPTED와 중복 키 보호, 입력·선정 근거 변경 시 차단 및 등록상품 관측을 포함한다. 내부 HTTP 시도별 별도 DB 행을 추가한 검증이 아니라 기존 최종 결과 보존 경로 검증이다.

## 적용 범위

운영 DB·실행 중인 IDE 앱·실제 쿠팡 판매값·실제 R2 객체를 변경하지 않았다. 실제 쿠팡 전송·재시도는 하지 않았으며, 성공은 모의 응답/로컬 HTTP 및 격리 DB 검증 결과다. UI 코드 변경이 없어 별도 브라우저 화면 회귀는 추가 실행하지 않았다. 기존 미완료 실행을 강제로 재개하거나 UNKNOWN을 해제하지 않는다. 실행 중 앱에는 반영하지 않았으므로 새 코드 적용은 사용자가 앱을 재시작할 때 이루어진다.
