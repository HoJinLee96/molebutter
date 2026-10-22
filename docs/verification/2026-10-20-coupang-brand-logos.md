# 브랜드별 상세설명 로고 검증

검증일: 2026-10-20 한국 시각. 운영 DB·실행 중인 IDE 앱·실제 쿠팡 상품값·R2 객체는 변경하지 않았다. 실제 공통 설정 저장은 사용자 작업으로 남겼다. 신규 테이블·마이그레이션을 추가하지 않았다.

## 구현

- 기존 계정 등록 기본설정 JSON의 선택 필드 `brandLogos`에 브랜드명/별칭/공개 common 이미지 URL을 보관한다. 생략은 유지, 빈 배열은 해제하며 기존 revision 충돌 검사를 재사용한다. 기존 브랜드 목록 입력 후보와 직접 입력, R2 검사 후 미리보기를 제공한다.
- 노출상품명과 저장된 브랜드명·별칭을 NFKC/공백/대소문자 정리 후 독립된 단어/구절로 비교한다. 하나만 일치할 때 최초 신규 준비에 적용한다. 미일치/복수 일치는 추정하지 않는다.
- 공통 안내 → 브랜드 로고 → 상품 이미지 순서. 대표/추가 이미지에는 로고를 넣지 않는다. 현재 옵션에만 수동 선택/URL 적용/로고 없음/제목으로 다시 찾기를 적용하며 교체/제거를 확인한다.
- `Content.purpose=BRAND_LOGO`와 UUID를 기존 작업본 JSON에 보존한다. 같은 URL의 일반 행은 자동 분류하거나 중복 삽입하지 않는다. 로컬 purpose는 쿠팡 전문에 포함하지 않는다.
- 늦은 응답은 계정/작업본/revision/옵션/제목/설명 문맥 및 저장·전송 중 여부를 다시 확인한다. 실패·취소 시 적용된 설명과 미적용 URL 입력을 보존한다. 설정 변경·화면 재렌더링으로 자동 적용하지 않는다.

## 검증 결과

- `scripts/test-integration.sh`: Java 전체 1,167개 중 1,162개 통과, 실패/오류 0, 기존 조건부 생략 5개(AuthenticationFlowIT 4, 실제 계정 CoupangOrderLiveIT 1). 새 임시 MySQL/Redis와 모의 HTTP, `target/verification` 빌드 경로 사용.
- `node --test src/test/js/*.test.cjs`: 283개 통과.
- `scripts/test-coupang-brand-logo-ui.cjs`: 1440px/390px 통과. 수동 교체/제거, 교체 취소, R2 검사 실패, 제목 변경 시 자동 미적용, 복수/미일치의 기존 로고 보존, 지연 응답 무시, 새로고침 복원, 설정 Enter 무동작, 설정 충돌 입력 보존, 브랜드 추가/삭제/미리보기와 가로 넘침 검증.
- `scripts/test-coupang-prefill-ui.cjs`: 기존 등록 준비·검색어·토큰 격리·공통 설정·Enter·충돌 PC/모바일 회귀 통과.
- `CoupangWorkingCopyFlowIT`: 실제 격리 DB의 설정 생략/명시적 해제·계정 분리·revision·파일 검사 실패 롤백 및 purpose/행 UUID 저장·재진입·수정 보존 통과.
- `CoupangWorkingCopyWriteTest`: 모의 쿠팡 전송 본문에 이미지 주소가 포함되고 purpose/BRAND_LOGO는 포함되지 않음 확인. 기존 원문/미디어 보존·revision·충돌·UNKNOWN·ACCEPTED 보호는 전체 회귀에 포함.
- 별도 `target/brand-logo` 패키지 빌드. 운영 앱 시작이나 패키지 교체는 실행하지 않음.
- 수정 범위 `git diff --check` 통과. 기존의 다른 작업 중 파일은 정리하거나 되돌리지 않음.

## 제공된 실제 로고의 읽기 전용 확인

`https://assets.molebutter.link/common/daks_logo.png`를 공개 HTTPS로 GET하여 HTTP 200, Content-Type image/png, 15,715 bytes를 확인했다. 실제 파일을 Java ImageIO로 디코딩한 크기는 534×512px이며 기존 `ImageRules.errors("DESCRIPTION", ...)` 검사 결과는 오류 없음이다.

이는 공개 URL 파일 읽기·앱 상세설명 규격 확인이다. 실제 계정의 R2 SDK 조회 권한, 쿠팡 등록 수용, 실제 공통 설정 저장이나 판매 전송까지 검증한 결과로 해석하지 않는다. 서버 R2 조회/검사는 격리 검증에서 모의 객체를 사용했다. 실제 파일은 이동·변환·삭제·업로드하지 않았다.
