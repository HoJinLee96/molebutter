# 기간 조건 + 최근 N개 수집 검증

검증일: 2026-10-20 (한국 시각). 운영 DB·실행 중인 IDE 앱·실제 쿠팡 판매값·실제 R2 객체는 변경하지 않았다. 실제 쿠팡 계정의 목록 호출을 수행한 검증은 아니다.

## 구현 범위

- 최근 등록 가져오기 설정 창: 기본 7일·100개, 오늘/7일/30일/직접 지정, 한국 날짜 양끝 포함 최대 90일, 등록상품 1~500개.
- 읽기 전용 내부 후보 포트: 날짜별 100개 페이지, 마지막 페이지 확인, 등록 시각 및 숫자 ID 내림차순 선정, 고정 시작 기준 시각과 명시적 삭제 제외.
- 후보/날짜/토큰의 원자적 체크포인트, 취소·임대 만료·권한/호출 제한 중단 및 재개, 대상 확정 후 상세 실패의 대체 선정 금지.
- 확정 대상만 기존 SUMMARY/상세/가격/재고 관측에 전달. 원래 관측 순서로 최신값 보호. RECENT는 `listComplete=false`, 전체 삭제 목록과 누락 상품 판정 미실행.
- V52 추가 테이블, 채워진 운영 DB의 일반 기동 전환 차단 및 검증된 오프라인 전환 경로. V1~V51은 이번 기능에서 수정하지 않음.

## 실행 결과

- `JAVA_HOME=... PATH=/opt/homebrew/bin:$PATH bash scripts/test-integration.sh`: Java 전체 1,161개 중 1,156개 통과, 실패/오류 0, 기존 조건부 생략 5개(AuthenticationFlowIT 4, 실제 계정 CoupangOrderLiveIT 1). 임시 MySQL/Redis, 모의 HTTP 사용. `target/verification` 빌드 경로.
- `node --test src/test/js/*.test.cjs`: 280개 통과.
- `scripts/test-coupang-recent-ui.cjs`: headless Chrome 1920px/390px 통과. 7일 기본값, Enter로 시작하지 않음, 실패 시 입력 보존, 명시적 시작, 후보/확정 대상 구분과 미정→확정 진행률, 가로 넘침 없음 검사.
- `scripts/test-coupang-products.cjs`: 기존 상품 목록·전체/선택 수집·명시적 작업 확인·PC/모바일 회귀 통과.
- 별도 `target/verification` 패키지 + `scripts/test-coupang-recent-products-upgrade.sh`: 합성 데이터/임시 이미지의 전체 DB 백업과 별도 격리 복원, V52 적용 전후 행 지문·이미지 바이트 동일성, 반복 실행 통과. 운영 적용 명령을 운영 환경에 실행하지 않음.
- `git diff --check`: 통과.

Java 검증에는 정렬되지 않은 하루 다중 페이지, N번째 경계와 숫자 ID 동률, 중복 ID, 빈 날짜/부족/0개, 삭제·기준 시각 이후 제외, 잘못된 날짜/날짜 충돌/반복 토큰, 취소 중 응답의 미커밋, 호출 제한/권한 거절/임대 만료 후 동일 체크포인트 재개, 최신 관측 보호 및 실패 상세 재시도 대상 고정을 포함한다. 기존 전체/선택 수집과 작업본 revision·충돌·UNKNOWN·ACCEPTED 관련 회귀도 전체 Java 검증에 포함된다.

## 운영 적용

현재 운영 스키마는 이번 검증에서 변경하지 않았다. 운영 사용 전 [V52 전환 절차](../coupang-recent-products-migration.md)에 따라 앱·워커 중지와 새 전체 백업·격리 복원 검증을 거쳐 별도로 적용해야 한다.

후속 작업: 구현 검증 이후 사용자의 별도 요청으로 V52 운영 적용을 완료했다. [운영 적용 기록](2026-10-20-coupang-v52-operating-migration.md)을 참고한다.
