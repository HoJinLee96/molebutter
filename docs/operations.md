# 운영·재기동·보존 기준

현재 앱은 단일 인스턴스로 운용한다. Maven reactor는 루트 포함 13개, 업무 모듈은 12개, 실행 JAR 안의 업무 라이브러리는 app을 제외한 11개다. 미저장 신규 편집 세션과 이미지 편집 원본은 프로세스 메모리에 있으므로 재시작 시 세션 복구 또는 이미지 재선택이 필요하다. 저장 작업본·전송 증거·이미지 참조는 DB·전용 파일에서 보존한다.

## 빌드와 로컬 재기동

1. 현재 소스·기존 JAR의 SHA-256과 복구 사본을 보관한다. 실행 중인 JAR를 빌드로 덮지 않는다. `JAVA_HOME=/path/to/jdk21 ./mvnw -o -Dmolebutter.build-directory=target/release verify`처럼 별도 경로에서 검증한다. 신규 환경은 의존성을 받아야 하므로 `-o`를 생략한다.
2. 현재 프로세스의 PID·포트·실행 디렉터리·JAR가 프로젝트 앱인지 확인한다. 판매 단계 QUEUED/RUNNING, 수집 작업 QUEUED/RUNNING과 두 계정 잠금의 활성 임대를 확인하고 앱·워커를 정상 종료한다. 다른 프로젝트 프로세스를 포괄 종료하지 않는다.
3. DB 전환이 있으면 전체 DB·전용 파일·기존 실행 설정을 안전한 별도 보관소에 백업하고 격리 MySQL 복원·행 지문·파일 해시를 대조한다. 현재 전환은 [V49 절차](coupang-products-migration.md)이며, 이전 단계는 [V48 절차](coupang-intent-migration.md)다. 코드만 바꾸는 배포는 마이그레이션·초기화를 실행하지 않는다.
4. 검증된 JAR만 교체하고 기존 실행 디렉터리·프로필·자격 증명 로딩 방식을 유지해 기동한다. 로컬 PropertiesLauncher 실행은 `molebutter-app`에서 다음과 같이 수행한다.

   ```sh
   java -Dloader.path=src/main/resources \
     -cp target/molebutter-0.0.1-SNAPSHOT.jar \
     org.springframework.boot.loader.launch.PropertiesLauncher
   ```

5. 로그인 화면·`/api/auth/csrf`·정적 JS·작업본 API 인증·공개 이미지 토큰·로그와 스키마 버전을 확인한다. DB 전후 행·값 지문과 파일 참조를 대조한다. UNKNOWN·ACCEPTED는 재전송하지 않고 결과 확인을 우선한다. 실제 쿠팡 쓰기는 배포 점검에 포함하지 않는다.
6. 코드만 변경했다면 문제가 있을 때 앱을 중지하고 보관한 기존 JAR로 되돌린다. DB 전환이 포함됐다면 이전 코드만 실행하지 않고 해당 전환의 검증된 DB·파일 복원 절차를 따른다. 적용된 Flyway checksum이나 성공 이력을 임의 수정하지 않는다.

`coupang.submissions.worker-enabled=false`와 `coupang.orders.worker-enabled=false`는 해당 워커의 자동 실행을 중지한다. 실행 중 작업을 강제로 성공 처리하는 설정이 아니다. `product.refresh.worker-enabled=false`는 별도 매입 조회 워커의 설정이다. 서비스 중지·임대 확인을 대신하지 않는다.

## 실행 설정 키

비밀 파일의 내용은 열거나 문서에 복사하지 않는다. 아래는 현재 공개 코드에서 확인한 키 이름이며 값은 포함하지 않는다. 로컬은 `src/main/resources/application-secret/`, 배포는 실행 디렉터리의 `application-secret/`를 기존 import로 읽는다. 기존 `marketplace.properties` 이름은 호환 설정 경로로 유지한다.

| 영역 | 현재 읽는 키·환경 변수 |
|---|---|
| DB | `spring.datasource.*`, `spring.flyway.*`; prod의 `DB_URL`, `DB_APP_USER`, `DB_APP_PASSWORD`, `DB_MIGRATION_USER`, `DB_MIGRATION_PASSWORD` |
| Redis | `spring.data.redis.*`; prod의 `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` |
| 인증 | `auth.jwt.secret`, prod의 `JWT_SECRET`; bootstrap-admin은 첫 관리자 설치용 별도 프로필 |
| 메일 | `resend.api-key`, `resend.from`; prod의 `RESEND_API_KEY`, `RESEND_FROM` |
| 쿠팡 | `coupang.vendor-id`←`coupang_access_id`, `coupang.access-key`←`coupang_access_key`, `coupang.secret-key`←`coupang_secret_key`, `coupang.vendor-user-id`←`COUPANG_VENDOR_USER_ID` 또는 `coupang_vendor_user_id` |
| 로컬 이미지 | `coupang.assets.directory` 기본 `./data/coupang-assets`, `coupang.assets.public-base-url`←`COUPANG_PUBLIC_IMAGE_BASE_URL` |
| R2 | `cloudflare.r2.*`, `CLOUDFLARE_R2_ENABLED` 및 자격 증명 환경 변수. 세부 목록은 [R2 저장소](r2-storage.md) |

`prod`는 별도 DB 앱·마이그레이션 계정과 HTTPS·Secure 쿠키를 요구한다. `cloudflare`는 loopback 바인딩·HTTPS 프록시·R2 활성화 기본값을 추가한다. 프로필을 바꾸는 것은 단순 R2 스위치와 다르다. 운영 자격 증명·키·서명·Authorization과 외부 원문을 로그·테스트·응답에 복사하지 않는다.

## 백업과 비추적 자료

- 변경·전환 실행별 전체 DB와 전용 파일 복구 사본을 보관한다. 스키마만 백업하거나 소스 tar를 DB 백업으로 간주하지 않는다. 파일 경로는 실행 디렉터리를 기준으로 해석한다.
- `backups/`의 과거 복원 사본·전송 감사 자료는 자동 정리하지 않는다. 폐기는 복원 확인과 별도 판단 후 수행한다. 이미지 자동 정리는 현재·감사 참조를 보호하며 공용 R2 객체를 삭제하지 않는다.
- Redis와 매입 브라우저 프로필을 정리 명령으로 초기화하지 않는다. Redis 인증 상태는 DB/파일 복구 사본과 별도이며 복원 시 [인증 정책](authentication-readiness.md)을 확인한다.
- 정기 DB 백업 스케줄·외부 복제·R2 재해복구 자동화는 이 저장소 코드만으로 실행 상태를 확인할 수 없다. `.gitignore`의 로컬 자료 설명을 자동 백업 보장으로 해석하지 않는다. 배포 담당자가 별도 백업·복원 리허설을 준비해야 한다.
- `coupang-api-docs/`는 현재 비추적 필수 자료다. 새 클론은 담당자의 비공개 쿠팡 전용 보관 사본을 복원하고 SOURCE·원문·자산 해시를 대조한 뒤 작업한다. 공개 저장소에 원문을 새로 게시하거나 수집·재생성하지 않는다. 이번 점검의 쿠팡 전용 아카이브·해시 목록은 `backups/coupang-cleanup-review-before-20261016/`에 있다. 다른 마켓까지 포함한 과거 전체 아카이브를 프로젝트에 통째로 복원하지 않는다.
- 소스·DB·파일·원문·실행 설정은 각각 보존 범위가 다르다. 비밀 설정·개인 브라우저 쿠키·로컬 스크래치·백업·로그를 Git에 포함하지 않는다. 로컬 tunnel launch agent와 IDE 실행 설정은 호스트별 관리 대상이다.

## 로컬 이미지 자동 정리

매시간 24시간 이상 삭제 대기 중인 자산을 최대 100개 조회한다. 자산 행을 잠근 뒤 작업본·전송 전문·감사 이력·감사 핀·편집본 원본 참조를 다시 확인하며 FK도 삭제를 보호한다. 공개 토큰만 남은 자산은 영구 보존 대상이 아니다.

DB 행 삭제를 별도 트랜잭션으로 커밋한 뒤 파일을 삭제한다. 커밋 전에 같은 디렉터리에 `<UUID>.png.delete-pending` 또는 `<UUID>.jpg.delete-pending` 표식을 만든다. 파일 삭제 실패나 프로세스 중단 시 다음 실행에서 표식을 최대 100개 재검사한다. 오래된 표식부터 처리하고 실패한 표식의 시각을 갱신해 이후 파일도 재시도한다. 이 순서는 프로세스 재시작 후에도 유지한다. DB 행이 남아 있으면 이미지 파일을 보존하고 표식만 제거한다. 행이 없을 때만 파일·표식을 삭제한다. 임의의 고아 파일 전체를 검색해 삭제하지 않는다.

이미지 업로드는 확정 롤백에서만 새 파일을 즉시 제거한다. DB 커밋 결과가 불명확하면 파일을 보존하고 같은 표식으로 DB 확인을 기다린다. 확정 롤백의 파일 삭제가 실패해도 표식을 남겨 재시도한다. 이 로컬 DB 상태는 쿠팡 전송 실행의 UNKNOWN과 별개다.

전용 이미지 디렉터리 백업에는 이 표식도 포함하고, 앱이 실행 중일 때 수동 삭제하지 않는다. 실패는 자산 ID와 예외 클래스만 WARN으로 남기고 다음 자산을 처리한다. 정리 실패 로그는 사용자별 HTTP 실패 알림과 별개다. 공용 R2·과거 백업·감사 참조 이미지는 이 정리로 삭제하지 않는다.

## 역할과 운영 기록

| 화면 | ADMIN | PRODUCT | VIEWER |
|---|---|---|---|
| 상품·정보 도구·최신화·보유 재고·공통 설정 | 허용 | 허용 | 차단 |
| 쿠팡·판매 주문 | 허용 | 차단 | 차단 |
| 내 근태 | 허용 | 허용 | 허용 |
| 근태 관리·인증 로그·직원 관리 | 허용 | 차단 | 차단 |

WORKFLOW·DASHBOARD·VERSION_HISTORY는 현재 화면이 없는 메뉴 정의다. 그 정의만으로 업무 API 권한이 생기지 않는다. 주문의 구체적인 보안 경로가 `/api/coupang/**`보다 먼저 등록돼야 한다.

쿠팡 작업본 저장·전송 준비와 실행·재시도·결과 확인·입력 수정, 수집 시작·취소·재개, 이미지 자르기 요청은 운영 감사에 메타데이터를 남긴다. HTTP 쓰기 요청 실패 알림은 요청 사용자에게만 제공한다. 비동기 쿠팡 반영의 최종 성공·부분 성공·UNKNOWN은 전송 실행 이력이 기준이다. 요청 접수 성공을 외부 반영 성공으로 표시하지 않는다.

앱의 쿠팡 JSON 수신은 `application/json`과 `application/*+json`에 같은 이전 판매 형식 거절·11MiB 요청 제한을 적용한다. 작업본 문서 자체의 5MiB 제한은 별도로 유지한다. text/plain·XML처럼 지원하지 않는 요청 형식은 415이며 장애 알림을 만들지 않는다. 이 규칙은 앱의 수신 계약으로, 쿠팡 공식 API의 Content-Type을 변경하는 안내가 아니다.

로그 파일의 현재 보관 설정은 일반 30일/1GiB, WARN 90일/512MiB, ERROR 180일/512MiB다. 비밀·배송 개인정보·외부 원문을 남기지 않는다. 쿠팡 예기치 않은 전송 오류는 실행 ID와 예외 클래스만 WARN으로 기록한다. GitHub CI나 호스트 로그 경로 변경은 이번 점검에서 구성하지 않았다.

쿠팡 전송 단계 결과 커밋 후 `[COUPANG_SUBMISSION] execution=… step=… type=… action=… state=… code=…`를 기록한다. FAILED·UNKNOWN은 WARN, 그 외는 INFO다. 로그에는 요청·응답 원문, 상세 거절 문구, 계정 키나 서명을 포함하지 않는다. 결과 저장 실패는 별도 PERSISTENCE 로그로 구분한다. 민감정보를 제거한 거절 설명은 권한이 확인된 앱 전송 기록에서 조회한다. 변경 적용 이후의 결과부터 상세 사유가 보존되며 기존 실행에 소급 생성하지 않는다.

## 등록상품 저장소 V49

상품 화면은 `/coupang/products`, DB 기반 조회·수집 API는 `/api/coupang/products`다. [저장소 규칙](coupang-api/registered-products.md)과 [V49 오프라인 전환](coupang-products-migration.md)을 따른다. `coupang.products.worker-enabled`의 기본값은 true이며 수동으로 접수한 수집만 처리한다. 중단 작업은 임대 만료 후 INTERRUPTED로 표시하고 사용자가 재개한다. 운영 전환 전 앱·워커를 중지한다. V49 판매 테이블은 33개이며 V50에서 계산 설정 1개를 추가한다. 감사 테이블 11개는 그대로다. 등록상품 원문과 변경 이력의 이미지 참조도 자동 정리에서 보호한다.

## 쿠팡 가격 계산 설정 V50

[V50 보존 전환](coupang-pricing-migration.md)은 공유 계산 설정 테이블 하나를 추가한다. 기존 데이터가 있는 V49 설치는 일반 앱 기동에서 자동 적용하지 않는다. 검증한 신규 JAR과 `scripts/upgrade-coupang-pricing.sh`로 전체 백업·격리 복원·전후 지문 대조를 수행한 뒤 재기동한다. `CoupangSchema` 보존·복구·상태 점검 대상에 새 테이블을 포함한다. V1~V49는 변경하지 않는다.

쿠팡 API 재시도 진단은 `[COUPANG_API]`의 실행·단계·시도 번호·고정 코드·RETRY/STOP을 확인한다. 실행 기록의 최종 결과는 기존 DB에 보존한다. 정책과 UNKNOWN 보호는 [공통 재시도](coupang-api/retries.md)를 따른다.

V51 쿠팡 계정별 등록 기본설정은 [등록 준비·오프라인 적용](coupang-registration-prefill.md#v51-적용)을 따른다. 운영 DB 자동 전환 대신 전체 백업·격리 복원을 검증한 `scripts/upgrade-coupang-registration.sh`를 사용한다.
