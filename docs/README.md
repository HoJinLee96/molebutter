# 프로젝트 문서

> 문서 유형: 현재 구현 안내. 2026-10-05 로컬 코드와 대조했다. 날짜가 붙은 적용·검증 문단은 당시 기록이며 현재 정책과 구분한다.

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

## 문서와 코드가 다를 때

[문서 정합성 정리](documentation-alignment.md)에 판단 기준·수정 목록·검증 범위를 기록한다. 코드를 무조건 정답으로 취급하지 않고 합의된 업무 의도와 대조한다. 현재 정책, 과거 조사·적용 결과, 미구현 제안을 구분한다. 상태 판정은 상태 정책·검색 완료 판정, 재시도는 검색 재시도, 책임·잠금은 모듈 구조, 실행 명령은 테스트 문서를 기준으로 확인한다.

## 과거 조사 기록

파일명에 날짜가 붙은 네이버·롯데·매장 검증 문서는 당시 외부 응답을 조사한 기록이다. 삭제하거나 최신 구현에 맞춰 조사 결론을 바꾸지 않는다. 이후의 확정 정책은 위 현재 기준 문서와 코드에서 확인한다. 판매 마켓 전체 API를 이미 구현하거나 실제 계정 호출을 완료했다는 의미는 아니다.
