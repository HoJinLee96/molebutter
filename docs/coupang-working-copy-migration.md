# V47 쿠팡 작업본 보존 전환

> V47의 역사적 전환 절차다. 현재 전환·복구는 [V48 절차](coupang-intent-migration.md), 현재 동작은 [쿠팡 작업본](coupang-api/working-copies.md)을 따른다.

V1~V46과 역사적 SalesSchema는 수정하지 않는다. 초기화하지 않는다. V47은 판매용 내부 상품을 쿠팡 작업본으로 바꾸고 식별자·revision·작성자·옵션 UUID·이미지·공개 토큰·전송 증거를 보존한다. 상품업무·매입·보유 재고·이동 이력·회원·근태는 변경하지 않는다.

적용 순서는 앱·워커 중지 → 전체 DB·판매 전용 파일 백업과 격리 복원 확인 → 사전 검사·V47 → 데이터·참조 대조 → 새 앱 재기동이다. 실행 중 작업·활성 임대, 중복 상품 연결, 계정 불일치, 판독할 수 없는 수정 의도나 원본 근거가 없는 연결은 DDL 전에 거절한다. 계정 근거가 없는 미연결 초안은 현재 실행 설정의 쿠팡 계정으로 귀속하며 사전 보고서에 작업본 ID를 기록한다.

```sh
MOLEBUTTER_UPGRADE_JAR=/absolute/path/to/new.jar \
COUPANG_ASSETS_DIRECTORY=/absolute/path/to/current/coupang-assets \
  bash scripts/upgrade-coupang-working-copies.sh --apply /absolute/path/to/new-backup-directory
```

기존 비밀 설정은 런타임에 읽는다. 비밀 파일을 열거나 수정하지 않는다. 이미지 경로는 기존 앱이 사용하던 실제 경로를 절대 경로로 지정한다. 명령은 앱 포트가 닫힌 상태와 DB가 참조하는 로컬 파일의 존재를 확인하고 별도 임시 MySQL에 전체 덤프를 복원해 행 지문을 대조한다. DB·전용 파일의 백업 및 전후 지문과 사전 보고서는 새 백업 디렉터리에 남긴다. Redis 전체 초기화와 공용 R2 삭제를 하지 않는다.

`coupang_draft`는 `coupang_product_working_copy`, 이미지 참조는 `coupang_working_copy_asset`이 된다. 현재 FK 열은 `working_copy_id`, `working_revision`이다. 계정·등록상품 ID·mapping·수정 항목은 작업본으로 합치고 종류·import·중복 등록 계정 컬럼은 제거한다. 기존 연결은 UPDATE, 미연결 작업은 CREATE다. 저장된 쿠팡 수정 의도를 우선하며 내부 기준값으로 쿠팡 입력을 덮지 않는다.

기존 비교·해결·미디어 증거, 별도 mapping·수정 의도, 전환 전 작업본 원문은 `legacy_coupang_*`에 감사 보관한다. 전환 당시 로컬 이미지에는 감사 참조를 부여해 자동 삭제를 막는다. `coupang_execution*`, 실행된 미리보기 전문·해시·중복 방지 키는 그대로 유지한다. 이전 편집 세션은 취소하고 미실행 미리보기는 만료시킨다. 저장 작업본에서 새 세션을 연다.

MySQL DDL은 원자적인 전체 롤백을 보장하지 않는다. DDL 이후 실패하면 앱을 재기동하거나 무조건 Flyway repair로 밀어붙이지 말고 검증된 전체 백업과 파일을 함께 복원한다. 복원 시 이전 JAR을 함께 사용한다. V47 적용 후 새 입력·실행이 생겼다면 해당 자료를 먼저 별도 보관한다.

현재 reset 명령은 감사 테이블이 현재 판매 데이터를 참조하면 중단한다. V47 전환에 판매 초기화를 사용하지 않는다. V45 초기화·V46 전환 문서는 과거 복원 절차의 근거로 보존한다.
