# 상품 이미지 경로별 중복 제외 검증

검증일: 2026-10-17. 코드·모의 저장소·로컬 모의 화면 검증이며 실제 R2 업로드나 운영 반영 기록이 아니다.

## 변경 동작

- 상품 prefix 전체 조회와 완료 작업의 상품코드 차단을 제거했다. 최종 공개 객체 키가 같은 파일만 중복 제외하고 없는 파일을 조건부 생성한다.
- 모든 파일 검증과 대상 키별 HEAD를 첫 공개 쓰기 전에 수행한다. 조회 실패·권한 오류를 파일 없음으로 취급하지 않는다.
- 기존 POST·GET과 작업 상태를 유지하고 `result.skippedFiles`에 파일명·키·URL·`EXISTS` 사유를 추가했다. 전체 중복은 공개 파일을 쓰지 않고 성공 종료한다.
- 상품별 진행·결과 미확인 예약, 요청 UUID·입력 동결·소유자 격리, ETag·세대 검사와 응답 유실 보호를 유지했다. 예약 해제 실패는 결과 확인 후 원래 성공/실패 결과로 복구하며 공개 쓰기를 반복하지 않는다.
- UNKNOWN은 시간 경과만으로 제거하지 않는다. 재시작으로 유실된 실행 자료의 예약은 자동 해제하지 않으며 복구 절차를 `docs/r2-storage.md`에 기록했다.
- 일반 사진의 순번과 파일명 생성 규칙은 유지한다. 현재 선택 순서로 만들어진 키가 기존 파일과 같으면 내용과 관계없이 제외하며 자동 이름 변경은 없다.

## 실행 결과

| 검사 | 결과 |
|---|---|
| Java 전체 verify + 추가 업로드 회귀 | 현재 보고서 합계 755개, 실패·오류·생략 0 |
| 업로드 서비스·예약·HTTP 회귀 | 56개 통과: 서비스 38, 예약 14, HTTP 4 |
| R2 저장소 계약 | 25개 통과, 조건부 생성·ETag 교체·오류 및 직접 HEAD 계약 유지 |
| JavaScript 전체 | 245개 통과 |
| 업로드 모의 화면 | 2560·1440·390px 통과, 외부 트래픽 차단 |
| diff 공백 검사 | 통과 |

Java 전체 verify 이후 서비스 테스트 2개를 추가해 업로드 관련 56개를 재실행했다. 현재 모듈별 Surefire 보고서의 고유 테스트 합계는 755개다. 전체 verify는 별도 `target/r2-upload-check` 디렉터리에서 수행했다.

주요 서버 사례는 삭제 후 4시간 이내 새 요청으로 재업로드, 완료 이력이 남은 상품에 사이즈 이미지만 추가, 일부/전체 중복, 다른 분류·확장자, 요청 내부 중복 경로, 조건부 쓰기 경쟁, 부분 실패 후 새 요청, 미확인 쓰기의 잘못된 소유자·해시·빈 HEAD, UNKNOWN 보관, 재시작 후 held 예약 보호, 예약 해제 실패와 해제 커밋 후 응답 유실 복구다. 중복 제외가 섞인 마지막 불확실 파일의 완료 판단도 검사했다.

모의 화면은 업로드·중복 제외 수와 파일별 표시, 같은 폴더의 연속 업로드, 전체 중복 후 잠금 해제, 부분 실패 안내, 입력 Enter 쓰기 금지·버튼 Enter/Space, 중복 클릭, 응답 유실·새로고침의 동일 요청 조회, 권한·CSRF 복구, 모바일 가로 넘침 없음을 확인했다.

## 재현 명령

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  CLOUDFLARE_R2_ENABLED=false MOLEBUTTER_COUPANG_LIVE=false MOLEBUTTER_ORDER_LIVE=false \
  ./mvnw -o -Dmolebutter.build-directory=target/r2-upload-check verify

JAVA_HOME=/opt/homebrew/opt/openjdk@21 CLOUDFLARE_R2_ENABLED=false \
  ./mvnw -o -Dmolebutter.build-directory=target/r2-upload-check -pl molebutter-app -am \
  -Dtest=ProductImageUploadServiceTest,ProductImageUploadReservationsTest,ProductImageUploadHttpTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

node --test src/test/js/*.test.cjs
node scripts/test-product-images-ui.cjs
```

이 환경에서는 bundled Node와 `NODE_PATH`의 Playwright를 사용했다. 모의 화면 검사는 로컬 포트·Chrome 실행 권한으로 실행했다. 새 DB 구조나 DB 조회·수정 경로를 추가하지 않았으므로 DB 마이그레이션·운영 DB 검증은 실행하지 않았다. IDE 앱의 시작·중지·재기동, 실제 R2 객체·예약의 조회·쓰기·삭제, 실제 쿠팡 호출은 수행하지 않았다. 비밀 설정은 열거나 수정하지 않았다.
