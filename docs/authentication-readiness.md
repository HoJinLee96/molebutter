# 인증·실행 환경 개선

> 문서 유형: 현재 구현 안내. 2026-10-05 로컬 코드와 대조했다. 날짜가 붙은 적용·검증 문단은 당시 기록이며 현재 정책과 구분한다.

## 적용 범위

- Redis의 GETDEL로 refresh 토큰과 이메일 인증 완료 증명을 한 번만 소비한다.
- 인증번호 검증은 값 비교와 삭제를 Lua로 처리한다. 발송 쿨다운은 SET NX, 로그인 시도 카운터 증가와 만료 설정은 Lua로 처리한다.
- 같은 페이지의 병렬 API 요청은 refresh 요청 하나를 공유한다. 이미 사용한 refresh를 재사용하면 해당 로그인 세션의 access/refresh를 모두 거부한다. 여러 탭에서 동시에 갱신하면 재사용으로 판단되어 재로그인이 필요할 수 있다.
- 계정 정지·잠금·역할 변경·비밀번호 변경 시 `auth_version`을 올린다. 민감 조회와 변경 요청은 DB 상태와 버전을 확인하므로 변경 커밋 이후 시작한 요청부터 기존 토큰을 거부한다. 이미 처리 중인 요청까지 취소하지는 않는다. 일반 조회의 지연 반영은 아래 정책을 따른다.
- 이메일 인증 완료 증명은 DB 변경 전에 소비한다. DB 저장이 실패하면 다시 인증해야 한다. Redis와 MySQL 간 분산 트랜잭션을 사용하지 않는다.
- 계정 변경은 DB 행 잠금으로 직렬화한다. 로그인 검증 도중 비밀번호/상태가 바뀐 경우 로그인 성공 처리를 거부한다.
- CSRF를 활성화했다. GET `/api/auth/csrf`에서 토큰을 받고 변경 요청에 `X-XSRF-TOKEN`을 보낸다. 공통 `api.js`가 발급과 만료 후 1회 재시도를 담당한다. Bearer 헤더 요청도 변경 작업에는 CSRF가 필요하다.

## 요청별 인증 확인 범위

`JwtAuthenticationFilter` 하나가 `RequestAuthPolicyResolver`의 분류에 따라 인증한다. 분류는 서버의 HTTP 메서드·경로로 결정하며, 클라이언트의 헤더·파라미터로 선택할 수 없다.

| 분류 | 대상 | 확인 범위 |
| --- | --- | --- |
| `BASIC_READ` 일반 조회 | GET·HEAD `/version-history`만 | JWT 검증 + Redis 폐기 확인, 인증용 DB 조회 생략 |
| `SENSITIVE_READ` 민감 조회 | 나머지 GET·HEAD: 내 정보, 마이페이지, 회원 관리, 로그인 기록 등 | JWT + Redis + DB 상태·버전·역할 |
| `WRITE` 변경 요청 | POST·PUT·PATCH·DELETE 등 GET·HEAD 외 메서드 | JWT + Redis + DB 상태·버전·역할 |

현재 버전 이력은 메뉴만 있고 화면은 미구현이다. 이 분류가 화면/API를 추가하지는 않는다. 구현된 보호 조회는 모두 DB 확인을 유지한다. 이후 공통 조회를 추가할 때는 개인정보·업무 데이터가 없는지 확인하고 resolver의 정확한 경로 목록에 추가한다. 하위 경로는 자동 포함하지 않는다.

일반 조회에서는 JWT 발급 당시의 이메일·역할을 사용하므로 계정 정지·삭제·권한 변경·비밀번호 변경이 access 만료까지 지연될 수 있다(설정된 access TTL 기준). 로그아웃 등 Redis에 기록한 토큰/세션 폐기는 일반 조회에서도 확인한다. 민감 데이터나 최신 권한이 필요한 조회를 일반 조회로 지정하면 안 된다.

공개 경로와 역할별 접근 허용은 기존 `SecurityConfig`가 담당한다. CSRF도 기존 설정대로 검사하며, `WRITE` 분류 자체가 CSRF 검사 대상을 정하지는 않는다. 여기서 생략하는 DB 조회는 인증용 사용자 조회이며, 컨트롤러·서비스의 업무 데이터 조회와는 별개다.

## DB 초기 구성

- 빈 스키마에 V1로 테이블을 만들고, V2로 `user.auth_version`을 추가한다. V1은 관리자 데이터를 넣지 않는다.
- 기존 공용 초기 관리자가 없는 신규 DB이므로, 해당 계정을 비활성화하던 V3는 사용하지 않는다.
- 이번 V1 변경은 빈 DB로 시작한다는 전제다. `user` 데이터만 비우고 기존 `flyway_schema_history`를 남겼다면 V1 체크섬 불일치가 발생할 수 있다. 이미 적용한 다른 DB에 수정된 V1을 그대로 배포하지 않는다.
- 프로젝트 변경이나 테스트 실행이 실제 `molebutter` DB를 초기화하지는 않는다.

## 최초 관리자 준비

로컬 `molebutter-app/src/main/resources/application-secret/admin.properties` 또는 실행 디렉터리의 `application-secret/admin.properties`에 다음 값을 넣는다. 두 경로 모두 `spring.config.import`로 읽으며 Git과 배포 JAR에서 제외된다. 실제 비밀번호는 코드·SQL·Git에 넣지 않는다.

```properties
BOOTSTRAP_ADMIN_EMAIL=관리자가_사용할_이메일
BOOTSTRAP_ADMIN_PASSWORD=관리자가_정한_비밀번호
```

파일을 읽는 것과 생성 기능을 켜는 것은 별개다. 최초 실행 시 IDE 환경 변수에 `SPRING_PROFILES_ACTIVE=bootstrap-admin`을 지정한다. 운영에서는 `prod,bootstrap-admin`을 사용한다. 이메일·비밀번호도 파일 대신 환경 변수로 전달할 수 있다.

| 환경 변수 | 값 |
| --- | --- |
| `BOOTSTRAP_ADMIN_EMAIL` | 메일을 받을 수 있는 관리자 이메일 |
| `BOOTSTRAP_ADMIN_PASSWORD` | 개인 비밀번호: 8~64자, 영문·숫자 포함, UTF-8 72바이트 이하 |
| `SPRING_PROFILES_ACTIVE` | 로컬 `bootstrap-admin`, 운영 `prod,bootstrap-admin` |

관리자 초기화는 **한 인스턴스에서 한 번만** 실행한다. 완료 후 `bootstrap-admin` 프로필과 파일/환경 변수에 넣은 두 초기화 값을 제거한다.

- 기존 ADMIN이 하나라도 있으면 생성 생략 로그를 남기고 종료한다. 정지·잠금 상태의 관리자도 임의로 복구하지 않는다.
- ADMIN이 없으면 설정된 이메일로 ADMIN·ACTIVE 계정을 만들고, 비밀번호는 BCrypt 해시로 저장한다. 이전 비활성 초기 계정 복구 분기는 제거했다.
- 지정 이메일이 일반 사용자 계정과 겹치면 승격하지 않고 실패한다.
- 기존 공용 초기 비밀번호 `admin1234!`는 초기화에 사용할 수 없다.

## 운영 설정

운영에서는 `prod` 프로필을 사용한다. `application-prod.properties`는 HTTPS 리다이렉트와 Secure/HttpOnly/SameSite 쿠키, Flyway clean 차단 및 JPA validate를 설정한다.

필요한 환경 변수:

```text
DB_URL
DB_APP_USER
DB_APP_PASSWORD
DB_MIGRATION_USER
DB_MIGRATION_PASSWORD
REDIS_HOST
REDIS_PORT (기본 6379)
REDIS_PASSWORD
JWT_SECRET
RESEND_API_KEY
RESEND_FROM
```

`JWT_SECRET`은 최소 32바이트의 무작위 비밀값을 사용한다. DB URL은 운영 DB의 TLS 구성에 맞게 설정한다. Redis는 GETDEL을 지원하는 6.2 이상이 필요하다.

인증서는 리버스 프록시에 설정하고 프록시가 `X-Forwarded-Proto: https`를 전달하도록 한다. 기본 신뢰 프록시는 loopback뿐이다. 별도 프록시는 `TRUSTED_PROXY_REGEX`에 실제 주소만 지정하고 앱 포트의 직접 외부 접근을 막는다. 이 설정 자체가 TLS 인증서를 발급하거나 HTTPS 리스너를 만들지는 않는다.

로컬 HTTP 개발은 기본 프로필을 사용한다. 두 `application-secret/` 위치는 Git에서 제외하고 배포 JAR에도 classpath 비밀 파일을 포함하지 않는다.

## 검증 실행

외부 서비스 없이 실행하는 설정·정책·메일 API 요청 형식 테스트:

```bash
./mvnw -o -Dmolebutter.build-directory=target/verification test
node --test src/test/js/api.test.cjs
```

실제 MySQL/Redis를 사용하는 전체 통합 테스트:

```bash
bash scripts/test-integration.sh
```

필요 도구는 Java 21, Python 3, MySQL 8의 `mysqld/mysql/mysqladmin`, Redis의 `redis-server/redis-cli`다. Maven 의존성은 미리 받아야 한다(`-o`는 오프라인 모드). 스크립트는 `/tmp`에 임시 MySQL 데이터 디렉터리를 만들고 임의의 로컬 포트에 MySQL·Redis를 띄운다. 런타임 계정은 DML만, Flyway 계정은 해당 임시 스키마의 DDL 권한도 부여한다. 종료 시 프로세스와 임시 데이터를 정리한다. IDE 자동 컴파일과 충돌하지 않도록 `target/verification`에 빌드한다.

검증 대상: 가입→승인→로그인, 승인 전 차단, 역할별 접근, 계정 정지·역할 변경, refresh 재사용 및 동시 갱신, 인증번호 동시 검증/소비와 시도 제한, 비밀번호 재설정, CSRF 누락/위조, 운영 쿠키, V1에 초기 계정 없음·V1→V2 업그레이드, properties 파일을 통한 최초 관리자 생성·기존 비밀번호 보존, 런타임 계정 DDL 차단.

### 요청별 정책 검증 (2026-09-24)

- 일반 조회의 인증 DB 접근 없음, Redis 폐기 확인 유지, 민감 조회·변경 요청의 현재 DB 사용자 정보 사용을 검증했다.
- HEAD·미등록 경로·하위 경로·변조한 정책 헤더/파라미터, 만료·서명 오류·필수 정보 누락 토큰을 검증했다.
- 격리된 MySQL·Redis로 전체 Java 테스트 62개 통과(추가 정책 테스트 43개 포함). 기존 데이터베이스는 변경하지 않았다.

### 최초 관리자 생성 방식 변경 검증 (2026-09-24)

- V1 실행 직후 사용자 테이블이 비어 있는지, V2가 기존 계정 정보를 보존하는지 확인했다.
- 별도 테스트 properties 파일의 값으로 ADMIN·ACTIVE 계정이 생성되고 BCrypt 비밀번호로 로그인되는지 확인했다. 초기화를 다시 실행해도 관리자 추가 생성·비밀번호 덮어쓰기가 없는지 검증했다.
- V3 없이 V1/V2만 사용하는 전체 Java 테스트 62개 통과. 실제 DB와 실제 관리자 비밀 설정은 변경하지 않았다.

## 실행 환경 확인 결과 (2026-09-21)

- 기존 앱 `/signin`: HTTP 200.
- 기존 MySQL: 앱 계정으로 접속 및 SELECT 성공, MySQL 8.4.10, `molebutter`의 Flyway V1 성공 상태 확인.
- 기존 Redis: 8.8.0, PONG.
- Resend: HTTPS API 응답 확인. 발송 전용 키라 읽기 전용 `/domains` 조회는 HTTP 401과 발송 전용 권한 제한 응답을 반환했다. 테스트 메일은 외부에 발송하지 않았다.
- 메일 요청 형식·API 실패 처리는 모의 HTTP 서버로, 가입 흐름은 테스트용 수신함으로 검증한다. 실제 수신함 도착과 운영 인증서·프록시 구성은 배포 환경에서 별도 확인해야 한다.
- 최종 결과: Java 테스트 19개, 프런트엔드 테스트 3개 모두 통과. JAR 빌드 성공, V1/V2/V3와 운영 프로필 포함 및 비밀 설정 파일 제외 확인.

참고: [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html), [Redis GETDEL](https://redis.io/docs/latest/commands/getdel/), [Resend 키 권한](https://resend.com/changelog/new-api-key-permissions).
