# V50 쿠팡 가격 계산 설정 보존 전환

V50은 `coupang_pricing_settings` 하나를 추가한다. 앱 전체에서 사용하는 계산 배송비 4,000원, 판매수수료율 10.5%, 희망 마진율 7%와 revision·수정자·수정 시각을 저장한다. 기존 V1~V49, 등록상품·옵션·작업본·전송 전문·감사 이력·이미지는 변경하지 않는다.

운영 적용 전 앱과 모든 업무 워커를 정상 종료한다. 전체 DB·쿠팡 전용 파일·기존 실행 JAR을 백업하고 격리 MySQL 복원과 기존 데이터/파일 지문 대조를 완료한 뒤 전환한다. 일반 기동은 기존 데이터가 있는 V49 DB의 미검증 V50 전환을 Flyway 실행 전에 거절한다. 전송·주문·상품 수집 작업이나 계정 임대가 활성 상태면 전환을 거절한다.

```sh
JAVA_HOME=/path/to/jdk21 \
MOLEBUTTER_UPGRADE_JAR=/absolute/path/to/verified-new.jar \
COUPANG_ASSETS_DIRECTORY=/absolute/path/to/coupang-assets \
  bash scripts/upgrade-coupang-pricing.sh --apply /absolute/path/to/new-backup-directory
```

이 명령은 기존 런타임 설정을 읽고 별도 임시 MySQL에서 전체 dump를 복원·검증한다. 운영 HTTP·업무 워커를 시작하지 않는다. V49 완료를 요구하며 V47 준비 중단 표식이 남아 있으면 거절한다. 백업에는 전체 dump·판매 이미지·전후 지문·불변 미리보기 증거·`UPGRADE_COMPLETE`를 남긴다. 이미 V50을 적용했다면 DB·파일을 변경하지 않는다.

상태 조회는 같은 런타임 설정으로 `CoupangPricingMaintenance --status`를 PropertiesLauncher의 `loader.main`으로 실행한다. V49/V50 성공 여부와 새 테이블 존재 여부만 읽는다.

DDL·커밋 결과가 불명확하거나 보존 대조가 실패하면 앱을 시작하거나 Flyway repair로 넘기지 않는다. 검증한 전체 DB·전용 파일과 이전 JAR을 함께 복원한다. 성공 이력·checksum은 수정하지 않는다.

격리 검증은 `CoupangPricingMigrationIT`와 `scripts/test-coupang-pricing-upgrade.sh`에서 수행한다. 실제 운영 전환은 일반 테스트에 포함하지 않는다. 실행 중인 IDE 앱은 테스트로 중지·재시작하지 않는다.
