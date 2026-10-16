# V45 초기화 이력

이 문서는 이전 V45 전환 기록이다. 현재 전환은 데이터를 초기화하지 않는 [V48 보존 절차](coupang-intent-migration.md)를 따른다. 아래 초기화 절차를 현재 전환에 실행하지 않는다.

## 대상과 보존 경계

쿠팡을 포함한 판매 초안·상품 연결·편집 및 전송 이력·주문·클레임·작업·전용 로컬 이미지와 공개 토큰을 초기화한다. `SalesSchema.TABLES`의 역사적 32개 `marketplace_*` 테이블만 삭제한다.
회원·권한·근태·catalog·procurement·inventory 데이터, 실제 보유 재고와 재고 이동, 매입처 조회·네이버 공개 검색, 공용 R2 상품 이미지와 매입용 브라우저 프로필은 보존한다.

## V45 당시 적용 순서

1. 앱과 판매·매입 워커를 중지한다. 실행 중인 구버전 앱을 재기동하지 않는다.
2. 쿠팡 전용 JAR을 빌드한다. V1~V44와 그 원본 SQL 자원은 수정하지 않는다.
3. `bash scripts/reset-sales-data.sh --apply backups/sales-reset-<새로운시각>`을 실행한다. 기존 설정을 Spring이 읽으므로 비밀 파일을 직접 열 필요가 없다.
4. 명령이 전체 DB를 백업하고 격리 MySQL에 복원한 뒤 모든 테이블의 행 지문을 대조한다. 원본 변경·복원 불일치·미등록 판매 테이블·판매 데이터에 대한 비판매 FK가 있으면 삭제 전에 중단한다.
5. 판매 전용 이미지 디렉터리는 백업으로 이동한다. FK 자식부터 트랜잭션으로 판매 데이터만 지우고 보존 테이블 지문을 재대조한다. 실패하면 DB와 이미지 이동을 되돌린다.
6. 아래 유지보수 진입점의 `--migrate`로 앱·워커를 켜지 않고 V45를 적용하고 `--audit`로 전후 보존 데이터를 다시 대조한다. 판매 테이블에 데이터가 남아 있으면 자동 삭제하지 않고 중단한다. V45는 불필요한 `market`·`import_market` 컬럼과 해당 인덱스 구성요소를 제거한다.
7. 빈 판매 목록·주문 이력, 회원·매입·보유 재고 보존, 쿠팡 설정·상품 읽기와 임시 저장을 확인하고 앱을 재개한다. 쿠팡 재수집·가져오기는 직접 실행한다.

유지보수 진입점은 프로젝트 루트에서 실행한다. `JAVA_HOME`은 Java 21이며 아래 변수는 비밀값을 포함하지 않는다.

```sh
sales_jar=molebutter-app/target/molebutter-0.0.1-SNAPSHOT.jar
sales_main=cc.ataglace.molebutter.app.internal.maintenance.SalesDataMaintenance
"$JAVA_HOME/bin/java" -Dloader.path=molebutter-app/src/main/resources \
  -Dloader.main="$sales_main" -cp "$sales_jar" \
  org.springframework.boot.loader.launch.PropertiesLauncher --migrate
"$JAVA_HOME/bin/java" -Dloader.path=molebutter-app/src/main/resources \
  -Dloader.main="$sales_main" -cp "$sales_jar" \
  org.springframework.boot.loader.launch.PropertiesLauncher --audit backups/sales-reset-YYYYMMDD-HHMMSS
```

`--migrate`는 기존 Flyway 실행 계정을 사용한다. `--audit`는 Flyway 적용 이력 외 보존 테이블의 모든 행 값을 대조하고 판매 테이블이 비어 있는지 확인한다. 둘 다 앱 포트가 열려 있으면 중단한다. `--audit`는 판매 계정 잠금 테이블도 비어 있어야 하므로 재기동 전에 실행한다. 재기동 후에는 스케줄러가 계정 잠금 행을 만들 수 있다. 앱을 정지하고 같은 진입점의 `--status`로 각 판매 테이블 행 수만 읽어 업무 데이터와 잠금 메타데이터를 구분한다. `MIGRATION_VERIFIED` 확인 후 새 JAR을 기존 설정으로 재기동한다.

기존 앱을 `molebutter-app` 디렉터리에서 실행했다면 초기화 명령에 `COUPANG_ASSETS_DIRECTORY`로 실제 판매 이미지 절대 경로를 지정한다. 앱과 유지보수 명령의 상대 경로 차이로 이미지를 누락하지 않아야 한다. 자산 DB 행은 있는데 해당 디렉터리가 없으면 명령이 중단한다.

`MOLEBUTTER_RESET_JAR`로 검증용 JAR 경로를 지정할 수 있다. 초기화 재실행도 같은 검증을 수행하며 기존 백업 디렉터리를 덮어쓰지 않는다. Redis 전체 초기화와 원격 R2 삭제는 수행하지 않는다.

## 복구

앱을 중지한 상태에서 백업의 `database.sql`을 원래 DB에 복원하고 `marketplace-assets`를 기존 설정 경로로 돌려놓은 뒤 V44 이전 앱을 사용한다. V45 적용 이후에는 DB 전체를 백업 시점으로 복원한다. 복원 검증 기록은 `verified-before.txt`, 초기화 완료 표시는 `RESET_COMPLETE`에 남는다. 백업은 비공개로 보관한다.

## 검증 구분

격리 DB·모의 쿠팡 API·브라우저 검증과 실제 로컬 DB 적용 결과를 구분하여 `docs/verification/2026-10-15-coupang-only.md`에 기록한다. 실제 쿠팡 상품 쓰기는 정리 검증에 포함하지 않는다.

V47 이후 `legacy_coupang_*`의 현재 판매 데이터 FK는 감사 자료 보호를 위한 것이다. `reset-sales-data.sh --apply`와 `SalesDataMaintenance --apply/--migrate`는 현재 스키마의 초기화 도구가 아니며 이 FK가 있으면 중단한다. 이를 우회하거나 감사 참조를 삭제하지 않는다. `--status`는 현재 등록된 `coupang_*` 표만 보고하고 감사 자료의 행 수를 포함하지 않는다.
