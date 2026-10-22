# 2026-10-20 선정 판매글 미발견의 품절 전환 추천

## 변경

정상 검색에서 선정 판매글을 찾지 못하면 추천 근거 전체를 버리던 처리를 제거했다. 보유 0·쿠팡 수량 양수이면 기존 `MARK_SOLD_OUT`에 포함하고 수량 0을 제안한다. 보유가 있으면 기존 보유 재고 맞춤/다중 옵션 직접 확인을 적용한다. 실제 품절 확인과 검색 미발견을 `SELECTED_MISSING`·`availabilityReason`·확인 시각으로 구분한다. 미발견 동안 가격/역마진/재고 보충은 추천하지 않는다.

기존 검색 캐시와 작업 이력을 현재 상품코드·검색어·lookup revision·선정 판매글 식별자로 일괄 판독한다. 실패·차단·취소·불완전 검색은 새로운 미발견을 만들지 않고 이전 정상 근거를 유지한다. 발견만으로 미발견 이전 재고를 복원하지 않는다. 동일 판매글의 더 최신 정상 구매 가능 재고가 있으면 품절 전환을 해제하며, 개별 정상 재고 확인의 실제 시각도 사용한다. SQL은 100상품 배치·run keyset 20행·필요 필드 투영을 사용한다.

목록과 개별/일괄 모달은 같은 서버 재고 제안을 사용한다. 이전 매입처 1개가 0개 제안을 덮지 않으며, 직접 입력·저장 의도는 계속 우선한다. 검색 근거는 기존 fingerprint에 포함돼 준비·실행·재시도 직전 재검사에 연결된다. 기존 UNKNOWN·ACCEPTED·revision·관측 버전 보호를 유지한다.

## 최종 실행 결과

- 전체 Java `verify`: **828건**, 실패/오류/생략 0.
- 격리 MySQL·Redis: **167건**, 실패/오류/생략 0. `ProductFlowIT` 131, `CoupangRegisteredProductsIT` 15, `CoupangWorkingCopyFlowIT` 21.
- JavaScript: **266건**, 실패/생략 0.
- 추천 모의 화면: **1920·1440·1024·390px 통과**. 개별·일괄 수정 기존 회귀는 각각 **1920·1440·390px 통과**.
- `git diff --check` 통과.

공통 실행 환경은 Java 21, `MOLEBUTTER_COUPANG_LIVE=false`, `MOLEBUTTER_ORDER_LIVE=false`, `CLOUDFLARE_R2_ENABLED=false`다. Java 출력은 `target/missing-listing-check`, 격리 DB는 기존 스크립트가 생성·제거하는 임시 MySQL·Redis와 `target/verification`을 사용했다.

```sh
./mvnw -o -Dmolebutter.build-directory=target/missing-listing-check verify
bash scripts/test-integration.sh '-Dtest=ProductFlowIT,CoupangWorkingCopyFlowIT,CoupangRegisteredProductsIT' -Dsurefire.failIfNoSpecifiedTests=false
node --test src/test/js/*.test.cjs
node scripts/test-coupang-recommendations-ui.cjs
node scripts/test-coupang-bulk-edit-ui.cjs
node scripts/test-coupang-quick-edit-ui.cjs
```

## 확인한 사례

- 이전 매입처 재고 1·보유 0·쿠팡 49 → 품절 전환, 제안 0. 가격·역마진·재고 보충은 함께 나오지 않는다. 이미 쿠팡 0이면 제외된다.
- 정상 검색 미발견 후 PENDING/실패에서도 근거 유지. 불완전 검색의 빈 결과는 미발견으로 추정하지 않는다.
- 검색에서 다시 발견됐어도 과거 양수 재고는 재사용하지 않는다. 더 최신 정상 확인 후 구매 가능으로 복귀하고 확인 시각·fingerprint가 변경된다.
- 보유 양수/동일 수량/다중 연결·보유 옵션, 미확인 수량 0, 근거 시각 누락과 식별 충돌·중복 코드·선정 없음·보유 조회 실패를 구분한다.
- 15상품의 계정 전체 품절 전환 집계, 검색과 페이지 크기/정렬, 모든 카드의 사유 제공, 수량 0 반영 후 건수 감소를 확인한다.
- 기존 이력 판독 결과와 정상 재고 본문을 대조한다. 새 검색 메타데이터를 제외한 재고 복구 결과는 동일하며, 100상품 배치 경계와 전체 payload 미전송 검사를 유지한다.
- 개별/일괄 화면이 서버 0개 제안을 사용하고, 제안 없음은 빈 입력, 직접 입력은 재조회 후에도 유지한다. 이전 수량·미발견 근거·시각을 구분한다. 키보드·저장 한 번·UNKNOWN·복구·선택 범위 회귀를 유지한다.
- 1440px의 긴 이전 재고/미발견 문구가 인접 셀과 겹치는 문제를 발견해 줄바꿈을 보완하고 셀 경계 검사를 추가했다. 1440·390px 결과 이미지를 직접 확인했다.

## 자료와 제한

검증 로그는 `/tmp/molebutter-missing-{compile,verify,it,js,ui,bulk-ui,quick-ui}.log`, 미발견 화면은 `/tmp/coupang-missing-listing-{1920,1440,1024,390}.png`다. 이 경로는 해당 로컬 실행 자료다.

공식 수량 변경 SOURCE·가격/재고 FAQ·KNOWN-ISSUES의 수집일은 2026-10-03이고 위 실행일은 2026-10-20이다. 기존 옵션별 수량 PUT과 명시적 저장/전송을 사용한다. 실제 판매중지 API나 자동 전송을 추가하지 않았다.

운영 DB·실행 중인 IDE 앱·실제 쿠팡 판매값·R2 객체를 변경하지 않았다. 실제 계정 조회·전송도 수행하지 않았으며, 모의/격리 검증을 실제 운영 상품의 갱신 완료로 해석하지 않는다. 새 코드 적용은 앱 재시작 후 이루어진다. 새 엔드포인트·테이블·마이그레이션·주기 작업은 없다.
