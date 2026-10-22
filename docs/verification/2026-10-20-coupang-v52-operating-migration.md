# 운영 V52 적용 기록

2026-10-20 한국 시각, 사용자의 명시적인 V52 운영 적용 요청에 따라 실행했다. IDE 앱과 8081 리스너가 중지된 상태에서 유지보수 명령만 실행했다. 실제 쿠팡 API·R2 호출과 앱 기동은 하지 않았다.

- 적용 전: V51 성공, V52 미적용.
- 검증 패키지: `molebutter-app/target/verification/molebutter-0.0.1-SNAPSHOT.jar`. 검증 패키지와 이전 패키지는 아래 백업의 `recovery-runtime`에 보관하고 SHA-256 목록을 남겼다.
- 전체 백업: `backups/coupang-recent-products-v52-20261020-181859/db-files/database.sql` (529,700,859 bytes).
- 전용 파일: 실제 경로 `molebutter-app/data/coupang-assets`를 명시하여 38개 파일 백업·바이트 동일성 확인.
- `scripts/upgrade-coupang-recent-products.sh`에서 임시 MySQL로 전체 DB 격리 복원 후 모든 행 지문을 대조하고, 백업 중 원본 변화가 없음을 확인한 뒤 V52 적용.
- 적용 후 기존 업무 데이터·판매 감사 자료·실행 미리보기·파일 동일성을 재검사. `UPGRADE_COMPLETE`, `verified-before.txt`, `verified-after.txt`, `immutable-previews.sha256` 보관.
- 읽기 전용 재확인: V51/V52 `success=true`, 두 신규 테이블 `coupang_collection_recent`, `coupang_collection_candidate`의 생성 증거 확인.
- 적용 후에도 8081 리스너 없음. IDE에서 앱을 다시 실행한 뒤 최근 등록 가져오기 기능을 사용할 수 있다. 수집 요청 자체는 실행하지 않았다.

백업 루트: `/Users/lee/workspace/molebutter/backups/coupang-recent-products-v52-20261020-181859`.

비밀 설정 디렉터리의 별도 복사는 자동 승인 검토에서 자격 증명 영구 복제 위험으로 거절되어 제외했다. 기존 비밀 설정은 변경하지 않았고 유지보수 코드가 실행 시 기존 경로에서 읽었다. DB/전용 파일 백업과 V52 적용은 승인된 대체 경로로 완료했다.
