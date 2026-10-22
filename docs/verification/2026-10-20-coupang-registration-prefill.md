# 2026-10-20 상품 정보 도구 → 쿠팡 등록 자동 입력 검증

## 구현

[사용·구조·V51 안내](../coupang-registration-prefill.md).

- 기존 쿠팡 등록 권한에 따라 관리자에게만 상품 등록 버튼을 표시하며, 상품 담당자의 기존 이미지 도구는 유지한다. 버튼에서 소유한 서버 관측·이미지 선택을 검증하고, 사용자·계정별 10분 준비 토큰으로 저장 전 CREATE 세션을 만든다. 일반 신규 작성 및 다른 상품/준비 토큰의 캐시와 격리한다. 사용자별 준비 중복 요청도 차단한다.
- 서버 관측의 상품명·원래 코드·제조사·고시·카테고리 지원 속성, 고유 정확 일치 브랜드 및 추천/유효성/규격이 확인된 카테고리만 매핑한다. 불확실한 고시·옵션은 확인 대상으로 남긴다.
- 기존 공통 가격 계산 및 정상/이전 정상/미발견/매입 불가/보유 재고 판정을 재사용한다. 정확한 활성 내부 코드가 없거나 중복이면 추정하지 않는다. 다중 수량은 합산하지 않으며 후보를 안내한다.
- 계정별 기본설정은 revision으로 충돌을 검사하고, 준비 시 한국 판매기간을 한 번만 채운다. 인증 기본값은 정확한 규격·CODE/NONE에 맞을 때만 적용한다. 출고지의 읽기 전용 지역비는 설정 수정값으로 받지 않는다.
- 공개 R2의 정확한 상품 경로와 실제 바이트를 읽는다. 최근 업로드 연결은 바이트 SHA-256까지 대조하고 현재 선택 순서를 적용한다. 연결이 없으면 기존 파일의 숫자 사진/사이즈/상품정보 순서임을 표시한다. 중복 번호·모호한 이름·삭제·미업로드·읽기 실패를 안내한다.
- 대표 1장·추가 최대 9장과 전체 상세설명 순서를 분리한다. 원본 변환·R2 쓰기·자동 임시저장·실제 등록은 수행하지 않는다.
- 직사각형은 양쪽 픽셀·형식·용량 조건을 만족하면 비차단 경고로 처리한다. 상세설명은 별도 제한이다. 새 등록/이미지 수정의 `target.warnings`를 저장·표시하며 가격/재고만 수정하는 준비에는 추가 이미지 검사를 실행하지 않는다. 여러 옵션의 동일 사진은 중복 검사하지 않으며 검사 요청을 500개 이하로 나눈다.
- 토큰 만료·재기동 뒤 로컬 보관 입력은 본인 정보와 새 CREATE 세션의 현재 계정을 확인한 경우만 복구한다. 명시적인 계속 버튼 전에는 저장·전송을 막는다. 날짜·가격·재고는 자동 재생성하지 않는다.

## 실제 실행한 검증

모든 Java 실행은 Java 21, `MOLEBUTTER_COUPANG_LIVE=false`, `MOLEBUTTER_ORDER_LIVE=false`, `CLOUDFLARE_R2_ENABLED=false`로 수행했다. 일반 빌드는 `target/prefill-check`, DB 검사는 `target/verification`에 분리했다.

- `./mvnw -o -Dmolebutter.build-directory=target/prefill-check verify`: **842개, 실패/오류/생략 0**. 후속 권한별 버튼 표시와 기본설정·공통 경고 보완은 관련 Java 검사·패키징, 아래 격리 DB 및 권한별 화면 회귀로 재확인했다.
- `CoupangPrefillServiceTest`: 서버 관측, 제조사·고시 고정 별칭·MPN, 브랜드 중복, 유효하지 않은 카테고리, 공통 계산기(이전 정상 사입가 30,000원 → 42,000원), 재고 0/미확인/다중 옵션, R2 순서·대표 한도·전체 설명, 최근 선택 연결, 누락·권한 오류, 계정/사용자/10분 만료, 인증 규격을 모의 포트로 검사한다.
- `ProductImageUploadServiceTest`: 완료 연결의 사용자·상품 식별과 SHA-256 일치/변경 바이트 거절을 추가했다. `ServerImageInspectionTest`는 양쪽 픽셀 499/500/5000/5001, 3MiB/초과, 형식, 직사각형 경고와 상세설명 분리를 검사한다.
- `CoupangClientTest`: 모의 쿠팡 HTTP에서 추천 응답 계약과 유효성 boolean을 엄격히 확인한다. 기존 간격·제한된 재시도·응답 유실 보호 검사도 통과했다.
- `bash scripts/test-integration.sh -Dtest=CoupangWorkingCopyFlowIT,CoupangRegistrationMigrationIT -Dsurefire.failIfNoSpecifiedTests=false`: **26개, 실패/오류/생략 0**. 임시 MySQL·Redis를 생성하고 제거했다. 준비 시 DB 작업본 행 없음, 첫 저장/재진입, 계정 및 revision, UNKNOWN, 비차단 경고의 실행 가능 상태 및 preview JSON 보존을 확인했다.
- `node --test src/test/js/*.test.cjs`: **271개, 실패/생략 0**. 새 준비 버튼의 중복 클릭·팝업 차단·실패 상태 해제 및 기존 폼/선택/작업본 회귀를 포함한다.
- `scripts/test-coupang-prefill-ui.cjs`: 실제 렌더링한 템플릿과 모의 API, 별도 headless Chrome에서 **1440px/390px 통과**. 신규 탭, 추천값과 0개 표시, 수동 수정 후 새로고침, 다른 토큰 격리, 만료 후 복구, 다른 사용자 복구 거절, 기본설정 명시적 저장·Enter 차단·revision 충돌 입력 보존·가로 넘침을 검사했다.
- `scripts/test-product-images-ui.cjs`: **2560/1440/390px 통과**. 기존 편집·이미지 순서·휴지통·업로드/다운로드·UNKNOWN 및 실패 입력 보존 회귀.
- `scripts/test-coupang-new-working-copy.cjs`, `test-coupang-period-notices-ui.cjs`, `test-r2-image-picker.cjs`, `test-coupang-submissions.cjs`: **PC/모바일 통과**. 신규 입력·세션 복구, 날짜·고시 보존, R2 선택 및 경고 표시·명시적 전송·응답 유실·UNKNOWN 확인을 검사했다.
- `scripts/test-coupang-registration-upgrade.sh`: 별도 임시 원본 DB와 복원 DB에서 V50 합성 데이터 → V51 전체 백업/격리 복원, 기존 행·판매 증거·이미지 바이트 동일성, 새 테이블의 무설정 상태 및 재실행 무변경 통과.
- `git diff --check` 통과.

화면 출력은 `target/ui-check/coupang-registration-defaults-{1440,390}.png` 및 기존 회귀 화면에 보관한다. 실제 R2/쿠팡 호출 대신 모의 포트·모의 HTTP·모의 저장소를 사용했다. 내부 상품 추천의 기존 어댑터/판정 회귀는 전체 Java 검사에 포함되며, 실계정의 LF/R2/쿠팡 연속 조회를 검증했다는 의미는 아니다.

## 운영과 실제 계정 검증의 경계

**운영 DB·실행 중인 IDE 앱·실제 쿠팡 상품값·실제 R2 객체는 변경하지 않았다.** 실행 중 앱을 재기동하거나 일반 `target/classes`에 배포하지 않았다. V51 운영 적용은 별도 오프라인 절차로 남겨 두었다.

이후 사용자의 별도 적용 요청으로 수행한 전체 백업·격리 복원 및 V51 운영 적용 결과는 [별도 운영 전환 기록](2026-10-20-v51-operating-migration.md)에 있다. 위 문단은 기능 구현·검증 당시의 범위를 나타낸다.

직사각형 이미지가 실제 계정의 쿠팡 등록 API에서 수용되는지는 검사하지 않았다. API 문서의 정사각형 안내와 Wing 안내를 구분한 앱의 경고 정책이며, 실제 거절은 기존 민감정보 제거 전송 기록으로 확인한다. 상세설명 이미지의 CDN 복제를 추정해 R2 원본을 삭제하지 않는다.
