# 프로젝트 문서

현재 구현과 다음 연동 준비는 아래 문서를 기준으로 확인한다. 날짜가 붙은 조사 기록은 당시 응답과 가설의 근거이며 최신 업무 정책을 대신하지 않는다.

| 영역 | 현재 기준 |
|---|---|
| 판매 마켓 연동 준비와 코드 경계 | [연동 전 기반 정리](market-integration-readiness.md) |
| 판매 마켓 공식 API 조사 | [마켓 API 목차](market-api/README.md) |
| 실제 보유 재고·매입 | [재고](inventory.md) |
| 인증·운영 준비 | [인증 준비](authentication-readiness.md) |
| 근태 | [근태](attendance.md) |
| 상품·매입처 조회 | [상품 조회](product-lookup.md), [공통 설정](shared-settings.md) |
| 매입처 상태·변동 | [상태 정책](product-status-policy.md), [검색 완료 판정](search-completion-status.md), [변동](product-value-changes.md) |
| 검색·재고 조회 복구 | [검색 재시도](naver-search-retry.md), [재고 조회 큐](supplier-stock-queue.md) |
| 알림 | [알림](notifications.md) |
| 실행·검증 | [테스트](testing.md) |
| 모듈·DB 책임·전환 | [모듈 구조와 적용 절차](modular-architecture.md), [전환 검증](modular-transition-verification.md), [경계 보강 검증](module-boundary-reinforcement.md) |

## 과거 조사 기록

파일명에 날짜가 붙은 네이버·롯데·매장 검증 문서는 당시 외부 응답을 조사한 기록이다. 삭제하거나 최신 구현에 맞춰 조사 결론을 바꾸지 않는다. 이후의 확정 정책은 위 현재 기준 문서와 코드에서 확인한다. 판매 마켓 전체 API를 이미 구현하거나 실제 계정 호출을 완료했다는 의미는 아니다.
