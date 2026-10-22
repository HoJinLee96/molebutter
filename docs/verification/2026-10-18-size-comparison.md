# 사진 사이즈 편집 실물 비교 검증

검증일: 2026-10-18. 로컬 코드·실제 서버 렌더러·모의 API 화면 검증이다. 운영 DB, 실제 R2 객체, 실행 중인 IDE 앱을 변경하지 않았다. 새 테이블·마이그레이션은 없다.

## 구현

- 사진 사이즈 편집에 기본 `없음`인 비교 대상 선택란을 추가했다. 8종 v1 모델의 치수와 로컬 벡터 렌더러는 imaging 모듈에서 관리한다.
- 높이 점선의 수직 길이와 입력 cm로 배율을 계산한다. 비교 사용 중 높이 선을 수직으로 유지하고 사진 배율 변경은 사진·점선·비교 대상 배치를 함께 조절한다.
- 표 위 공간을 넘으면 공통 축소·이동한다. 서버의 `sceneTransform`을 프리뷰·핸들 표시·포인터 역변환에 동일하게 사용한다. 모델 좌표의 기존 경계에 닿으면 슬라이더의 공통 확대 배율을 제한한다.
- 프리뷰와 최종 PNG는 같은 렌더러를 사용한다. 생성 이미지의 편집 스냅샷에 비교 설정을 보존하고 ZIP·업로드 export에는 고정된 PNG 바이트를 사용한다.
- 모델 목록 실패·프리뷰 실패·생성 실패에서 입력을 보존한다. 이전 편집·선택·닫힌 모달의 지연 응답은 무시한다. 확인 버튼은 공통 FormActions로 연결했다.

## 모델 근거

아래 공식 문서를 2026-10-18 재확인했다. 실행 중에는 외부 규격이나 그림을 조회하지 않는다. 노트북 높이는 닫힌 상판을 위에서 본 세로 길이다.

| 모델 | 가로 × 높이(cm) | 근거 |
|---|---|---|
| 삼다수 500ml | 가로 실측 없음 × 21 | 사용자 제공 높이. 그림 가로/높이 비율 0.30 |
| iPhone 16 | 7.16 × 14.76 | [Apple](https://support.apple.com/en-us/121029) |
| Galaxy S26 기본형 | 7.17 × 14.96 | [Samsung](https://www.samsung.com/sec/smartphones/galaxy-s26/specs/) |
| iPad Air 11(M4) | 17.85 × 24.76 | [Apple](https://support.apple.com/en-us/126471) |
| iPad Air 13(M4) | 21.49 × 28.06 | [Apple](https://support.apple.com/en-us/126472) |
| MacBook Air 13(M5) | 30.41 × 21.50 | [Apple](https://www.apple.com/kr/macbook-air/specs/) |
| MacBook Air 15(M5) | 34.04 × 23.76 | [Apple](https://www.apple.com/kr/macbook-air/specs/) |
| MacBook Pro 16(M5 Pro/Max) | 35.57 × 24.81 | [Apple](https://www.apple.com/kr/macbook-pro/specs/) |

## 실행 결과

| 검사 | 결과 |
|---|---|
| Java 전체 verify | 770개, 실패·오류·생략 0 |
| JavaScript 전체 | 249개 통과 |
| 신규 비교 화면 | 1920·1440·390px 통과 |
| 기존 상품 이미지 화면 회귀 | 2560·1440·390px 통과 |
| diff 공백 검사 | 통과 |

서버 검사는 10cm·100px에서 물병 210px, 200px에서 420px, 8종 본체 비율, 공통 축소 후 비율과 표 보존, 수직 정렬, 비교 없는 기존 렌더링, 높이 누락·0 길이·모델·버전·좌표 오류를 포함한다. 프리뷰와 생성 PNG, 보관 바이트, ZIP의 `processed/사이즈.png`, 업로드 export 바이트의 일치를 대조했다. 카탈로그의 기존 상품 업무 권한도 검사했다.

화면 검사는 선택·선택 해제·초기화·재편집 복원, 역변환 드래그, 터치 포인터, 키보드 이동, 공통 배율 연동, 목록 재시도, 프리뷰·생성 실패 입력 보존, 입력 Enter 쓰기 금지·버튼 Enter/Space, 닫기·새 편집 이후 지연 응답 무시, 모달 배경 스크롤 잠금을 포함한다. 실제 PNG를 직접 확인하며 치수 숫자와 비교 캡션이 겹치지 않도록 배치를 보완했다.

브라우저는 실제 Java 렌더러가 생성한 PNG fixture와 로컬 모의 API를 사용하고 외부 요청을 차단한다. 편집 후 PNG 재계산의 서버 계약은 Java 검사에서 별도로 대조한다. 실제 모바일 기기나 운영 서버의 검증 기록은 아니다.

## 재현

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  CLOUDFLARE_R2_ENABLED=false MOLEBUTTER_COUPANG_LIVE=false MOLEBUTTER_ORDER_LIVE=false \
  ./mvnw -o -Dmolebutter.build-directory=target/size-comparison-check verify

node --test src/test/js/*.test.cjs
node scripts/test-size-comparison-ui.cjs
node scripts/test-product-images-ui.cjs
git diff --check
```

이 환경에서는 번들 Node와 Playwright 모듈 경로를 사용했다. 브라우저 스크립트 실행 전에 Java 검사를 실행해 fixture를 생성한다. Java 출력은 기존 IDE 출력과 분리된 `target/size-comparison-check`다. 결과 PNG는 `molebutter-app/target/size-comparison-check/comparison-fixtures/`, 화면 캡처는 루트의 `target/ui-check/size-comparison-{1920,1440,390}.png`에 생성한다.
