# R2 객체 저장 인프라

`molebutter-storage`는 Spring 앱에서 재사용하는 공통 객체 저장 모듈이다. `storage.api.ObjectStorage`만 다른 모듈에 공개하며 AWS S3 SDK와 요청·자격 증명 처리는 `storage.internal`에 둔다. 파일 소유자, 업무 권한, DB 참조와 삭제 가능 여부는 호출하는 업무가 검사한다. 객체 키를 직접 조작하는 범용 HTTP API는 추가하지 않는다.

2026-10-08 Cloudflare API로 기존 버킷과 도메인 상태를 재조회했다. `molebutter`의 `assets.molebutter.link`는 활성이고, `molebutter-private`은 사용자 도메인이 없으며 두 버킷 모두 r2.dev가 비활성이다. 신규 버킷을 만들거나 기존 객체를 변경하지 않았다.

| 영역 | 기본 버킷 | 공개 주소 |
|---|---|---|
| `StorageArea.PUBLIC` | `molebutter` | `https://assets.molebutter.link` |
| `StorageArea.PRIVATE` | `molebutter-private` | 없음 |

공개 버킷에는 공개할 파일만 저장한다. 비공개 객체의 `publicUrl`은 빈 값을 반환한다. URL 계산은 객체 존재 여부를 조회하지 않는다.

## 상품 이미지 경로

상품 이미지 도구의 ‘업로드’ 버튼은 현재 이미지 순서의 파일을 공개 버킷에 저장한다. 사용자는 확인 창에서 `uploadProductCode`를 수정할 수 있다. 조회 원본 코드가 `HIHO6F861W2`여도 저장 코드를 `HIHO861W2`로 입력하면 `products/HIHO861W2/` 아래에 저장한다. 조회 상품정보·원본 이미지 URL·생성 이미지 소유권 검사는 원본 `productCode`와 브랜드를 기준으로 유지한다.

실제 객체 키는 `products/{저장 상품코드}/{파일명}`이다. 상품코드와 파일명 사이에 UUID 폴더를 만들지 않는다. `molebutter`는 버킷 이름이며 객체 키에는 포함하지 않는다.

| 구분 | 예시 |
|---|---|
| 버킷 | `molebutter` (`StorageArea.PUBLIC`) |
| 상품별 목록 prefix | `products/HIHO861W2/` |
| 객체 키 | `products/HIHO861W2/상품정보.png` |
| 공개 URL | `https://assets.molebutter.link/products/HIHO861W2/상품정보.png` |

원본·저장 상품코드는 영문·숫자·밑줄·하이픈 4~40자로 검사하고 대문자로 정규화한다. 저장 코드에는 `/`, `..`, 임의 폴더 경로를 넣을 수 없다. 서버는 로그인 사용자가 조회한 원본 목록과 요청의 이미지 번호·URL을 대조하고, 생성 PNG도 해당 사용자·원본 상품에 속하는지 검사한다. 목록 prefix 끝의 `/`를 유지해 이름이 비슷한 다른 상품의 파일을 포함하지 않는다.

확인 시 브라우저가 요청 UUID와 현재 선택·순서를 동결하고 서버가 UUID 형식을 검사한다. UUID는 작업 식별과 응답 유실 확인에만 사용한다. 원본 사진명은 목록 순번이며, 사이즈는 `사이즈.png`, 상품정보는 `상품정보.png`다. 여러 장이면 각각 `_2`, `_3`를 붙인다. 상품 대표·추가 이미지의 영구 관리 정보를 새로 만드는 것은 아니다. 파일 형식은 실제 MIME에 맞춰 정하고, 다운로드와 같은 상품사진 여백 처리 및 생성된 사이즈·상품정보 PNG를 업로드한다. 휴지통의 이미지는 제외한다. ZIP과 R2 객체 키의 마지막 파일명은 동일하며 업로드 결과에 공개 URL과 함께 표시한다.

`products/{저장 상품코드}/` 아래에 객체가 하나라도 있으면 상품 전체의 중복 업로드를 차단한다. 기존 UUID 하위 폴더의 파일과 부분 성공으로 저장된 파일도 포함한다. 기존 파일은 이동·삭제·덮어쓰기하지 않는다. 개별 공개 이미지도 `If-None-Match: *` 조건부 생성으로 저장한다. 확인창은 선택한 이미지와 입력 코드를 유지하며 다른 저장 상품코드로 변경할 수 있다.

공개 파일은 `molebutter`에만 저장한다. 동시 업로드를 막는 내부 예약은 비공개 버킷 `molebutter-private`의 고정 키 `product-image-upload-reservations/{저장 상품코드}.json`에 보관한다. 예약에는 작업 ID·`held` 또는 `released` 상태·획득 세대 식별자를 넣으며 공개 이미지 경로에 포함하지 않는다.

## 상품 이미지 업로드 흐름

`POST /api/product-images/products/upload`는 원본 상품·브랜드·저장 상품코드·현재 이미지 목록·요청 UUID를 받는다. 상품 업무 권한과 CSRF를 검사한 뒤 접수하며, `GET /api/product-images/products/upload/jobs/{요청 UUID}`로 같은 사용자의 결과를 확인한다. 설정이 비활성이면 원본 사진을 다운로드하거나 파일을 쓰기 전에 안내한다.

접수 전에 상품 prefix를 조회한다. 객체가 있으면 `409 IMAGING_UPLOAD_DUPLICATE`, 경로를 확인할 수 없으면 `503 IMAGING_UPLOAD_CHECK_FAILED`로 쓰기 전에 거절한다. 브라우저는 이 명시적 거절을 응답 유실로 취급하지 않고 확인창과 입력을 유지한다.

접수 시 조회 정보와 소유한 생성 PNG를 snapshot으로 확보한다. 선택한 모든 파일을 준비하고 파일별 R2 한도와 전체 64MiB 한도를 검사한 뒤 비공개 예약을 조건부 생성하거나 ETag 조건으로 획득한다. 예약 중인 상품은 다른 요청이 획득할 수 없다. 직전에 해제된 같은 작업 ID를 다시 획득하지 않으며 새 작업은 새로운 세대 식별자를 사용한다. 파일을 저장하지 않은 실패는 새 작업 ID로 다시 시작할 수 있다. 예약 후 공개 prefix를 다시 검사하고 이미지를 순차 저장하므로 첫 파일이 다른 동시 요청도 상품 전체에서 차단한다. 예약 확인·해제는 요청 ID와 서버가 발급한 세대가 모두 일치할 때만 수행한다. 해당 ETag 조건으로만 해제하며 삭제하지 않아 지연된 조회·해제가 다음 작업의 예약을 지우지 못한다.

한 번에 1~64개, 전체 실행 2개, 사용자별 실행 1개로 제한한다. 준비 중 실패하면 파일을 쓰지 않으며, 쓰기 중 명확한 실패가 발생하면 앞서 저장한 파일과 실패 사유를 반환하고 남은 파일을 보내지 않는다. 일부라도 저장되면 같은 상품의 재업로드를 차단한다.

브라우저는 요청 UUID와 동결한 입력을 세션 보관한다. 보관 중인 같은 UUID·같은 입력 요청은 기존 작업을 반환하며, 다른 내용으로 같은 UUID를 사용하면 충돌이다. 새로고침은 기존 결과를 조회하고 쓰기를 자동 실행하지 않는다. 접수 응답이 유실된 경우에도 같은 UUID의 결과를 먼저 조회한다. 서버가 정확히 ‘업로드 기록 없음’을 반환했을 때만 같은 요청 재전송을 제공한다.

이미지 쓰기 응답 유실·시간 초과·조건부 생성 충돌은 `UNKNOWN`으로 남겨 새 업로드를 막는다. 결과 확인은 불확실한 파일의 S3 `HEAD`로 크기·MIME·`sha256`·`request-id`를 대조한다. 일치하면 해당 작업의 저장을 확인하지만, 조회 실패·객체 없음·불일치는 실패로 단정하거나 다시 쓰지 않는다. 파일 일부만 확인되면 나머지는 미전송으로 종료하고 같은 상품의 재업로드도 차단한다. 비공개 예약의 쓰기 결과가 불확실하면 이미지를 보내지 않으며, 결과 확인으로 본인 예약의 저장·해제를 확인하기 전에는 재업로드하지 않는다. 결과 확인은 이미지 쓰기를 반복하지 않는다.

업로드 결과 기록은 DB가 아닌 서버 메모리에 최대 32개를 보관하며 종료 후 4시간에 정리한다. 서버 재시작 시 결과 기록은 소실되므로 영구 실행 이력이나 완전한 재개 기능은 제공하지 않는다. 중복 판정은 메모리 기록 외에도 공개 prefix와 비공개 예약을 확인하므로 재시작·기록 만료·파일 형식 변경·새 UUID로 기존 상품 차단을 우회할 수 없다. 재시작으로 결과 확인 경로가 사라진 미확인 예약은 자동 해제하지 않으며, 해당 상품의 업로드는 계속 차단한다.

## CRUD 계약

| 호출 | 동작 |
|---|---|
| `create(area, key, bytes, contentType, metadata)` | `If-None-Match: *`로 새 객체를 저장한다. 같은 키가 있으면 `CONFLICT`다. |
| `get(area, key)` | 크기 제한 내 바이트와 메타데이터를 반환한다. 없는 객체는 `Optional.empty()`다. |
| `head(area, key)` | 내용 없이 크기·MIME·ETag·수정 시각·사용자 메타데이터를 조회한다. 없는 객체는 빈 값이다. |
| `replace(area, key, expectedETag, bytes, contentType, metadata)` | `If-Match` 조건에 맞을 때만 내용과 메타데이터를 교체한다. 최신 ETag가 다르면 `CONFLICT`다. |
| `delete(area, key)` | 정확히 한 키를 삭제한다. 없는 객체의 삭제는 성공으로 처리한다. |
| `list(area, prefix, token, limit)` | 1~1000개 단위로 한 페이지를 조회한다. 다음 토큰을 그대로 다음 호출에 전달하며 버킷 전체를 자동 순회하지 않는다. |
| `publicUrl(area, key)` | 공개 영역만 UTF-8 경로를 인코딩한 URL을 반환한다. |

ETag는 따옴표를 포함한 불투명 문자열이다. 임의로 해시로 변환하거나 따옴표를 제거하지 않고 받은 값을 교체 조건에 사용한다. 쓰기 응답에는 수정 시각이 없고, 목록 응답에는 MIME·사용자 메타데이터가 없으므로 상세값이 필요하면 `head`를 사용한다. 교체 입력의 사용자 메타데이터는 기존값과 합치지 않고 전체를 교체한다.

객체 키에는 빈 경로 구간·`.`·`..`·역슬래시·제어 문자를 허용하지 않는다. 한글과 공백은 사용할 수 있다. 업로드·조회 내용은 설정된 바이트 한도를 검사하며, 읽기는 응답 스트림을 제한해 선언된 크기가 잘못돼도 한도를 넘겨 읽지 않는다.

쓰기를 자동 재시도하지 않는다. 연결 단절·시간 초과 등으로 결과를 확정할 수 없으면 `WRITE_UNCERTAIN`을 반환한다. 같은 쓰기를 반복하기 전에 `head` 또는 `get`으로 최신 상태를 확인해야 한다. 오류에는 SDK 원문·서명·키·자격 증명을 포함하지 않는다. 이 저장 모듈은 업무 DB 트랜잭션에 참여하지 않으므로 파일 저장 후 DB 저장 실패 시의 보상 삭제와 결과 확인은 호출하는 업무가 담당한다.

사용자 도메인의 캐시는 S3 조회와 별개다. 동일 공개 키를 교체·삭제했더라도 캐시에는 이전 내용이나 404가 남을 수 있다. 현재 상품 이미지 업로드는 기존 상품의 재업로드·교체·삭제를 제공하지 않으며, 캐시 삭제 기능도 이번 모듈에 포함하지 않는다. [Cloudflare 일관성·캐시 안내](https://developers.cloudflare.com/r2/reference/consistency/)

## 설정과 활성화

기본 설정은 `cloudflare.r2.enabled=false`이며, 공개 배포용 `cloudflare` 프로필에서는 `true`를 기본으로 사용한다. 두 경우 모두 `CLOUDFLARE_R2_ENABLED`로 덮어쓸 수 있다. 비활성 상태에는 SDK 클라이언트나 기본 AWS 자격 증명 탐색을 만들지 않고, `ObjectStorage` 호출에 명시적인 `NOT_CONFIGURED`를 반환한다. 활성화 시 필수값·엔드포인트·버킷·크기·시간 제한을 검증한다. 설정 변경은 앱 재실행 시 적용된다.

로컬 HTTP 실행에서 업로드를 사용할 때도 `CLOUDFLARE_R2_ENABLED=true`를 실행 환경에 넣는다. R2 자격 증명 import만으로 활성화되지 않는다. R2만 켜기 위해 `cloudflare` 프로필을 추가할 필요는 없다. 그 프로필은 HTTPS 강제·Secure 쿠키·loopback 바인딩도 설정한다. 현재 Mac의 VS Code 앱 실행 구성에는 R2 활성화 환경 변수를 지정했다. 터미널 개발 실행은 다음과 같이 명시한다.

```sh
CLOUDFLARE_R2_ENABLED=true ./mvnw -pl molebutter-app spring-boot:run
```

단일 app 목표 실행 전 reactor 설치가 필요할 수 있으므로 [개발 실행 안내](modular-architecture.md#빌드와-실행)를 따른다. 앱이 이미 실행 중이면 환경 변수 변경 후 다시 시작해야 한다. 활성화는 저장 클라이언트만 준비하며 사용자가 업로드를 확인하기 전에는 파일을 저장하지 않는다.

앱의 기존 Spring 설정 import에 `application-secret/cloudflare.properties`를 추가했다. classpath 개발 설정과 실행 디렉터리의 설정을 모두 지원한다. 비밀 파일은 에이전트가 직접 읽거나 수정하지 않는다. 운영 환경 변수 또는 사용자가 관리하는 해당 파일로 설정을 공급한다.

| 속성 (`cloudflare.r2.` 하위) | 기본값 / 의미 |
|---|---|
| `enabled` | 기본 `false`, `cloudflare` 프로필 `true` |
| `account-id` | Cloudflare 계정 ID. 생략하면 검증된 `endpoint`에서 파생하며, 명시했으면 endpoint의 계정과 일치해야 한다. |
| `endpoint` | 생략하면 계정 ID의 R2 S3 엔드포인트를 사용한다. 지정할 때도 검증된 HTTPS R2 호스트를 사용한다. |
| `region` | `auto` |
| `public.access-key-id`, `public.secret-access-key` | 공개 버킷용 S3 자격 증명 쌍 |
| `private.access-key-id`, `private.secret-access-key` | 비공개 버킷용 S3 자격 증명 쌍 |
| `access-key-id`, `secret-access-key` | 해당 영역의 중첩 자격 증명을 지정하지 않았을 때 사용하는 기존 flat 설정 fallback |
| `public.bucket` | 기본 `molebutter`. 기존 `public-bucket` 설정보다 우선한다. |
| `private.bucket` | 기본 `molebutter-private`. 기존 `private-bucket` 설정보다 우선한다. |
| `public.base-url` | 기본 `https://assets.molebutter.link`. 기존 `public-base-url` 설정보다 우선한다. |
| `max-object-bytes` | `33554432` (32MiB, 최대 64MiB) |
| `connect-timeout` | `3s` |
| `request-timeout` | `30s` |

예를 들어 환경 변수는 `CLOUDFLARE_R2_ENABLED`, `CLOUDFLARE_R2_ACCOUNT_ID`, `CLOUDFLARE_R2_ACCESS_KEY_ID`, `CLOUDFLARE_R2_SECRET_ACCESS_KEY`로 공급한다. 실제 자격 증명은 문서·응답·로그에 기록하지 않는다. Codex Cloudflare 플러그인 인증은 앱의 S3 자격 증명을 대신하지 않는다.

기존 `cloudflare.properties`의 `public.*`, `private.*` 구조를 지원하며 공개·비공개 요청에 서로 다른 S3 클라이언트를 사용한다. 중첩 자격 증명은 한 쌍으로 선택한다. 한쪽 값만 입력한 쌍을 flat 값과 섞지 않고 설정 오류로 처리한다. 활성화에는 두 영역의 유효한 자격 증명이 필요하다.

SDK는 R2용 `auto` 리전·경로 방식·비청크 업로드를 사용한다. [Cloudflare Java SDK 예제](https://developers.cloudflare.com/r2/examples/aws/aws-sdk-java/)와 [조건부 객체 요청 호환표](https://developers.cloudflare.com/r2/api/s3/api/)를 기준으로 구성했다.

## 기존 기능과 적용 범위

상품 이미지 도구의 명시적인 업로드만 공통 R2 모듈에 연결했다. 조회·PNG 생성·ZIP 다운로드만 수행하면 R2에 쓰지 않는다. `marketplace_asset`의 로컬 파일·공개 token 경로·초안과 실행 참조 보호 및 imaging의 임시 PNG·ZIP 보관은 유지한다. 기존 파일을 R2로 일괄 이전하거나 마켓 상품의 이미지 연결을 자동 변경하지 않는다.

신규 저장소 관리 화면·DB 로그 정리·자동 백업·기존 파일 일괄 이전은 이번 범위에 포함하지 않는다. 이번 업로드 연결은 모의 S3·상품 이미지 응답과 브라우저 모의 응답으로 검증하며 실제 객체 쓰기 검증과 구분한다. [Cloudflare 접속 설정 문서](cloudflare-setup.md)의 이전 실계정 업로드·조회·삭제 검증 기록은 그대로 유지하며, 이번 업로드 화면에서 실제 R2 쓰기를 실행한 기록을 뜻하지 않는다.
