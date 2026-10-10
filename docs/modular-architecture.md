# 업무 모듈과 DB 책임

> 문서 유형: 현재 구현 안내. 2026-10-09 로컬 코드와 대조했다. 날짜가 붙은 적용·검증 문단은 당시 기록이며 현재 정책과 구분한다.

하나의 Spring Boot 앱·DataSource·트랜잭션 관리자·MySQL·Redis를 유지한다. 루트 Maven 프로젝트는 부모와 집계 역할을 하며, 배포할 실행 JAR은 `molebutter-app/target/molebutter-0.0.1-SNAPSHOT.jar` 하나다. 쿠팡 등록상품 목록 수동 조회를 포함하며 내부 상품·재고 저장과 자동 동기화는 하지 않는다.

## 모듈 경계

| 모듈 | 책임 | 참조 가능한 업무 모듈 |
|---|---|---|
| common | ID, 한국 시간, 업무 오류, 페이징, 공통 영속성 기반 | 없음 |
| storage | 공개·비공개 R2 객체 CRUD, 목록·메타데이터, 저장소 설정·오류 | common |
| identity | 계정, 인증, 직원, 인증 감사, 메일, 인증 Redis | common |
| operations | 업무 감사, 알림 저장·접근 | common, identity |
| catalog | 상품 기준, 브랜드, 수정 버전, 일관성 가드 | common, identity, operations |
| procurement | 검색 기준, 매입처, 선정, 최신화, 추천, 변동·복구 | common, identity, operations, catalog |
| inventory | 매입 주문, 재고, 수량 이력, 환불, 결제 수단 | common, identity, operations, catalog |
| attendance | 근태, 정정·승인, 조회 | common, identity, operations |
| media | 원본 이미지·소유권·공개 발행·참조·실행 핀 | common, identity |
| marketplace | 공통 판매 초안·편집 세션·검증·실행·주문 이력 | common, identity, media |
| marketplace-coupang | 쿠팡 인증·조회·전송·규격·응답 해석 | common, identity, marketplace, media |
| marketplace-naver | 스마트스토어 인증·조회·전송·네이버 이미지 전달 | common, identity, marketplace, media |
| imaging | LF몰 이미지 조회·정렬 산출물·사이즈 및 고시 렌더·임시 다운로드 | common |
| app | HTTP·화면, 설정, 업무 간 연결, Flyway, 실행 | 모든 모듈 |

다른 업무의 Java 코드는 `.api` 계약으로만 사용한다. 구현과 저장소는 `.internal`에 둔다. `common.persistence`는 공통 엔티티 기반을 위한 명시적 예외이며 JPA 의존성을 선택적으로 선언한다. 사용하는 업무가 JPA 라이브러리를 직접 선언한다. HTTP 응답·예외 매핑은 app에 두며 `PageResponse`에는 Spring Data 변환이 없다.

`CatalogCommands`는 기준 상품과 기존 `revision`을 변경한다. `CatalogQueries`는 ID 목록의 기준 정보를 일괄 조회한다. `CatalogConsistencyGuard`는 공유·배타 잠금을 제공한다. `ProcurementLifecycle`은 조회 기준 무효화·생성·통합·삭제에 참여하고, `ProcurementProductQueries`는 기존 상품 HTTP 조회 결과를 제공한다. `InventoryLinkService`는 삭제 제한과 참조 통합을 담당한다.

상품 변경은 app의 `ProductService`가 기존 순서대로 동기 연결한다. 같은 JPA/JDBC 트랜잭션에 참여하므로 중간 실패 시 전체가 롤백된다. 재고의 선택적 판매글 검증은 `SupplierReferencePort`를 app에서 procurement 조회 계약에 연결한다. 알림 대상 확인도 `NotificationTargets` 포트를 app에서 연결한다. 조회 계약과 변경 계약을 나눠 Bean 순환을 피한다. 근태의 사용자 잠금은 `IdentityAccounts.lockUser`로 바깥 트랜잭션에 참여한다.

### 변경 계약과 잠금의 실행 조건

기준 상품의 코드·브랜드 검증, 중복 확인, 등록명 결합, 브랜드 추론과 수정 버전 검사는 catalog가 소유한다. `CatalogCommands.ProductInput`·`BrandSelection`·`VersionedProduct`로 전달하며 상품 등록·수정용 저장 JSON과 기준 상품 변경 SQL은 catalog가 작성한다. app은 감사 기록용 변경 전후 스냅샷 JSON을 조합할 수 있으며, 이를 catalog 저장 책임과 구분한다. 검색어 검증과 신규 상품의 초기 검색어 선정은 procurement가 담당한다. app은 권한 확인, HTTP 입력 변환과 모듈 간 트랜잭션 연결을 담당한다.

브랜드 일괄 지정·해제는 `CatalogCommands.editBrand`로 처리한다. 브랜드·버전·배타 가드를 검증하면서 과거 상품코드 원문과 procurement의 검색어·조회 기준·결과를 보존한다. 상품코드를 직접 수정하는 `edit`의 정규화·중복 검사는 유지한다.

상품 변경의 호출자는 같은 DataSource의 실제 쓰기 트랜잭션에서 배타 가드를 먼저 획득해야 한다. catalog 변경과 procurement·inventory의 통합/삭제 참여 계약은 이를 실행 중 검사한다. 트랜잭션 없음, 읽기 전용 변경, 가드 미획득, 고정 가드 행 누락, 트랜잭션에 참여하지 않는 DataSource를 거절한다. 존재하지 않거나 삭제·통합된 기준 상품과 수정 결과도 확인한다. 변경할 행이 없어도 정상 완료한 것처럼 처리하지 않는다. 다만 등록명 동일 값 저장이나 이동할 이력이 없는 경우 같은 합법적인 무변경은 허용한다.

잠금 획득 상태는 현재 트랜잭션의 synchronization에 보관한다. `REQUIRES_NEW`는 별도 상태를 사용하고, 바깥 트랜잭션 재개 시 원래 상태를 사용한다. 종료·롤백 이후에는 재사용하지 않는다. 배타 획득 뒤 공유 획득은 허용하지만 공유에서 배타로의 암묵적 승격은 거절한다. 잠금 범위와 기존 업무별 잠금 순서는 유지한다. Spring 프록시를 사용하지 않는 복구 명령의 직접 생성 계약도 같은 검사를 받는다.

상품 HTTP 전용 요청·결과는 app의 `ProductHttpDtos`에 둔다. procurement 조회 조합은 `ProcurementProductView`로 이름을 명확히 하되 기존 JSON 필드는 유지한다. 공통 문자열 길이·필수값 검증은 `BusinessText`를 사용하며 각 호출부의 오류 문구와 업무별 의미는 유지한다.

`ModuleArchitectureTest`는 알려지지 않은 업무 패키지, 실제 Maven 모듈과 패키지 소유자의 불일치, 공개 계약의 제네릭·배열·레코드 등에 노출된 내부 타입까지 검사한다. 잘못된 테스트 전용 계약으로 규칙이 실제로 실패하는지도 확인한다. SQL 쓰기 소유권 검토는 여전히 별도로 수행한다.

## 저장 책임과 SQL 쓰기 검토 목록

| 소유자 | 변경 가능한 테이블 |
|---|---|
| catalog | catalog_product, product_brand, sales_channel, catalog_merge_history, catalog_consistency_guard |
| procurement | procurement_product, procurement_settings, procurement_runtime, product_supplier·선정·매장·선호·조회·변동 관련 기존 테이블, product_refresh_*, supplier_stock_lookup, 검색 시도·게이트 이력 |
| inventory | inventory_purchase, inventory_item, inventory_movement, inventory_request_lock, inventory_payment_method 등 기존 재고 저장 구조 |
| identity | user 및 인증 관련 저장소·Redis 키 |
| attendance | attendance, attendance_break, attendance_correction |
| operations | operation_audit_log, notification_event, user_notification |
| marketplace | marketplace_draft, marketplace_asset, marketplace_draft_asset |
| app/Flyway | 신규·기존 마이그레이션 실행만 담당 |

실제 테이블명은 Flyway를 기준으로 한다. 코드 검토 시 `INSERT / UPDATE / DELETE`의 소유자를 대조한다. 다른 업무 테이블과 조회용 JOIN은 허용하지만 다른 업무의 컬럼 변경은 공개 변경 기능으로 요청한다. app 연결 어댑터는 조회만 수행한다. ArchUnit은 허용 의존성·내부 접근·업무 패키지 순환을 검사하며 SQL 쓰기까지 자동 판정하지 않는다.

`catalog_product`에는 ID·코드·브랜드·등록명·시각·통합·삭제·수정 버전만 남는다. `procurement_product.product_id`는 상품 PK를 참조하며 검색 설정, 조회/변동 버전, 최신·이전 정상 결과, 조회 시각과 마지막 이미지를 소유한다. 상품 이미지 우선순위는 선정 판매글 이미지 다음 마지막 확보 이미지다. catalog에는 기준 상품 이미지나 판매 마켓 이미지를 추가하지 않는다.

설정의 수정 버전과 예약 설정은 `procurement_settings`, 워커 임대·간격·쿨다운·재개 게이트는 `procurement_runtime`, 전역 잠금은 `catalog_consistency_guard`의 고정 행으로 분리한다. 최초 전환은 기존 잠금 범위와 순서를 유지한다. `revision`은 catalog에서만 변경하고 `lookup_revision`·`change_version`은 procurement에서 변경한다. 예전 버전 번호를 초기화하지 않는다.

상품코드·검색 기준 변경과 현재 결과 무효화는 함께 커밋한다. 결과 확정 때 현재 상품과 조회 버전·실행 상태를 다시 확인한다. 이전 조회는 이력에 남아도 현재 결과를 덮어쓰지 않는다. 목록 검색·정렬·전체 건수·페이지 처리는 SQL에서 수행하며 페이지 뒤 필터링이나 상품 행마다 HTTP/DB 조회를 추가하지 않는다.

## 빌드와 실행

Java·Spring Boot·기존 라이브러리 버전은 유지한다. Boot 재패키징은 app에서만 수행한다.

```sh
# 루트 reactor에서 모든 모듈을 함께 빌드한다. 사전 SNAPSHOT 설치가 필요 없다.
./mvnw clean verify
# 운영 연결 없는 전체 Java 통합 검증
bash scripts/test-integration.sh
# 실행 JAR만 생성
./mvnw -DskipTests package
java -jar molebutter-app/target/molebutter-0.0.1-SNAPSHOT.jar
# 개발용 단일 app 목표 실행에는 먼저 reactor 설치가 필요하다.
./mvnw -DskipTests install
./mvnw -pl molebutter-app spring-boot:run
```

Flyway·템플릿·정적 파일·일반 설정은 app 자원에 있다. 로컬 비밀 파일은 `molebutter-app/src/main/resources/application-secret/` 또는 실행 디렉터리의 `application-secret/`에서 읽는다. Git과 JAR 제외 규칙을 유지하며 테스트 자료와 테스트 전용 공통 JAR은 배포 의존성에 포함하지 않는다. 공유 응답 자료는 루트 `test-fixtures/`에서 테스트 classpath에만 제공한다.

복구 명령은 `cc.ataglace.molebutter.procurement.internal.ProductStatusRepairCommand`와 `SearchStatusRepairCommand`다. 전체 모듈과 외부 의존성이 포함된 classpath 또는 실행 JAR의 Boot `PropertiesLauncher`를 이용한다. 자동 기동에서 실행하지 않는다. 자세한 명령은 상태 판정 문서를 따른다.

## V28 → V30 적용과 복구

운영에서 구버전·신버전을 동시에 실행하지 않는다. 이중 저장도 지원하지 않는다.

1. 기존 앱의 예약과 워커를 중지한다. 활성 최신화·개별 조회는 기존 앱에서 명시적으로 완료하거나 취소한다. 임대가 해제되거나 만료됐는지 확인하고 앱을 종료한다.
2. 백업을 만들고 별도 임시 환경에서 복원 가능 여부를 확인한다. 상품·매입처·선정·조회·이력·재고의 건수와 버전 기준을 기록한다.
3. 새 코드와 마이그레이션을 함께 적용한다. V29는 활성 작업을 검사한 뒤 테이블을 생성하고 삭제·통합 상품을 포함해 모든 값과 참조를 복사·대조한다. V30은 다시 대조한 후 기존 컬럼과 product_settings를 제거한다. 작업이나 이력을 임의 취소·삭제하지 않는다.
4. 첫 확인 기동은 `--product.refresh.worker-enabled=false --product.changes.initialize-enabled=false`로 하고 기존 예약 설정도 중지 상태인지 확인한다. bootstrap-admin 프로필은 사용하지 않는다. 트래픽은 아직 열지 않는다.
5. 데이터 대조·화면·권한·등록·입고·취소·선정·조회 상태를 확인한 후 예약과 트래픽을 재개한다.

MySQL DDL 전체를 하나의 트랜잭션으로 되돌릴 수 있다고 가정하지 않는다. 이전 실패 후 운영 DB에서 무조건 `repair`하거나 기존 앱을 재기동하지 않는다. 적용 중 실패하면 백업 DB와 이전 앱을 함께 복구한다. 트래픽 재개 후 생긴 매입·입고 등은 백업에 없으므로 단순 복원하지 않고 후속 변경까지 대조해 복구하거나 수정 배포한다. 테스트의 `Flyway.repair()`는 의도적으로 실패를 만든 격리 DB에서 원인을 고친 뒤 검증하기 위한 용도다.


## 판매 마켓 조회 모듈

`molebutter-marketplace`는 common·identity·media 공개 계약에 의존하고 app이 조립한다. 공통 초안·편집 세션·실행·주문 이력은 marketplace, 쿠팡과 스마트스토어 인증·외부 요청·규격·응답 해석은 각각 marketplace-coupang과 marketplace-naver, 관리자 HTTP·화면·비밀 설정 연결은 app 소유다. catalog·procurement·inventory 테이블을 읽거나 수정하지 않는다. ArchUnit이 허용 의존성과 다른 모듈 internal 접근 금지를 검사한다. [쿠팡 구현 범위](market-api/coupang-wing.md)를 참고한다.

판매용 공통 초안은 marketplace, 원본 이미지·공개 발행·참조·실행 핀은 media가 소유한다. 기존 기준 상품·매입처·보유 재고 테이블을 연결하거나 변경하지 않는다. 편집 DTO·외부 쓰기 전문·현재 판매값을 구분하고 초안 수정은 버전 검사와 이미지 참조 잠금을 사용한다. [공통 편집 구현](market-api/common-product-editor.md)을 참고한다.

## 상품 이미지 도구

`molebutter-imaging`은 LF몰 이미지 조회와 수동 등록용 이미지 가공을 담당한다. API·화면·사용자 권한은 app에서 연결하고 다른 업무 테이블은 읽거나 수정하지 않는다. 생성 이미지와 ZIP 작업은 사용자별로 격리된 임시 저장을 사용한다. [사용 흐름과 HTTP 계약](product-image-workspace.md)을 참고한다.

## 객체 저장 인프라

`molebutter-storage`는 Cloudflare R2의 공개·비공개 버킷을 사용하는 독립 인프라 모듈이다. 중립 타입의 `storage.api.ObjectStorage`를 공개하고 S3 SDK·자격 증명·요청 구성은 `storage.internal`에 둔다. 앱이 설정을 로드하며, 업무별 접근 권한·소유자·DB 참조·정리 정책은 호출하는 업무가 관리한다. 객체 키를 직접 받는 범용 HTTP 저장 API는 제공하지 않는다.

기존 media의 로컬 원본·발행 이미지와 imaging의 임시 PNG·ZIP은 자동 이전하지 않는다. 공개 버킷에는 공개용 파일만 저장하고 비공개 객체에는 공개 URL을 제공하지 않는다. 신규 R2 설정은 기본 비활성으로 유지한다. [구성 및 CRUD 계약](r2-storage.md)을 참고한다.

## 판매 마켓 경계 확장 (2026-10-09)

단일 Spring Boot 앱을 유지하며 다음 모듈을 분리한다. 기존 브라우저 API와 전문 JSON의 필드 이름은 유지한다.

| 모듈 | 책임 | 공개 계약 의존 |
|---|---|---|
| marketplace | 공통 초안·revision·관찰/편집 세션·순차 실행·재시도·주문 수집 이력 | common, identity, media |
| marketplace-coupang | HMAC·조회·상품 생성/수정·가격/재고/배송·주문 응답 해석 | common, identity, marketplace, media |
| marketplace-naver | OAuth·상품/카테고리 조회·원상품/채널상품 저장·네이버 이미지 업로드 | common, identity, marketplace, media |
| media | 원본 자산·소유권·공개 토큰·초안 참조·실행 핀·정리 | common, identity |
| imaging | LF몰 이미지 조회·치수/상품정보 렌더·편집·임시 ZIP | common |
| storage | 공개/비공개 R2 객체 CRUD | common |
| app | HTTP·화면·설정 주입·모듈 조립 | 각 모듈의 공개 API |

공통 실행기는 `MarketplaceWriteGateway`, `MarketplaceProductAdapter`, `MarketplaceOrderGateway`를 사용하며 공급자 구현에 의존하지 않는다. 기존 전용 화면 DTO 및 신규/수정 워크플로 계약은 호환을 위해 marketplace.api에 남는다. 변환·검증·외부 전문 구성은 공급자 포트로 위임한다. 주문 수집의 DB 체크포인트·취소·재개는 공통 모듈에 남는다.

저장된 Prepared JSON은 schemaVersion을 포함한다. 없는 기존 버전은 1로 읽고, 지원하지 않는 미래 버전은 쓰기 실행/재시도를 거부한다. 기존 마켓·상품·옵션 매핑 키와 CREATE 결과 확인 필요, 승인 대기, 부분 성공의 의미는 바꾸지 않는다.

`MarketplaceChannels`는 여섯 채널의 식별자·표시명·공급자·기능별 구현 여부를 한 곳에서 관리한다. 계정 자격 증명 구성이나 실계정 성공을 뜻하지 않는다. G마켓·옥션은 ESM 공급자를 공유하되 채널 ID는 분리한다. 롯데ON·11번가는 준비 상태이며 비어 있는 어댑터나 외부 호출을 생성하지 않는다. 스마트스토어 주문 수집은 미구현이다. 공통 편집기의 네이버·ESM 준비 입력과 전용 화면 전송 가능 여부도 구분한다.

`ImageAssets` 추출은 기존 자산 테이블·저장 경로·설정·HTTP URL을 그대로 사용한다. 기존 공개 R2 객체나 로컬 원본을 옮기지 않는다. 상품 이미지 도구의 R2 업로드 작업 registry는 현재 메모리 수명 정책을 유지하며, 영구 작업 기록·중단 복구와 자산 통합은 별도 마이그레이션 과제다. 기존 R2 prefix 중복 검사와 예약 CAS를 유지한다.

ArchUnit은 새 패키지의 실제 모듈 위치, 공개 API만 사용하는 의존 방향과 순환 금지를 검사한다. 모듈 이동은 API 규격 변경이나 실제 판매 마켓 호출 성공을 의미하지 않는다.
