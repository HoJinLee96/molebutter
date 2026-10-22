# 사이즈 비교 배율·삼다수 사진 교체 검증

검증일: 2026-10-19. 운영 DB·실제 R2 객체·실행 중인 IDE 앱을 변경하지 않은 로컬 검증이다. DB 구조·마이그레이션·외부 API 계약 변경은 없다.

## 원인과 수정

비교 모드에서 슬라이더는 사진·치수 점선·비교 대상을 공통 확대했다. 서버는 그 확대된 현재 장면을 매번 같은 영역에 꽉 맞춰 축소했다. 자동 맞춤이 작동하는 구간에서는 `사용자 확대 × 자동 축소`가 상쇄되어, 입력 배율이 증가해도 실제 상품 PNG 크기가 거의 같아졌다. 이전 모의 화면은 배치 요청과 핸들 연동만 검사해 이 출력 픽셀 문제를 놓쳤다.

서버의 공통 맞춤 기준을 현재 배율이 아닌 도달 가능한 최대 배치로 고정했다. 최대 배치는 기존 2.5 상한과 점선의 0~1 좌표 경계에서 계산한다. 프런트엔드는 동일한 최대값을 슬라이더 상한에 사용해 움직여도 반영되지 않는 범위를 없앴다. 순수한 확대·축소에서는 맞춤 기준이 유지되므로 사진·높이 점선·비교 대상 크기가 함께 변한다. 점선과 위치를 직접 조정하면 새로운 최대 배치를 계산한다. 비교를 끄면 기존 사진 편집 동작으로 돌아간다.

삼다수는 사용자가 첨부한 무라벨 500ml 사진을 내장 `imagegen`의 배경 제거 기능으로 처리해 `molebutter-imaging/src/main/resources/imaging/comparisons/samdasoo-500-v2.png`에 저장했다. 흰 배경 대신 실제 알파를 가진 PNG다. 서버는 분리된 작은 알파 점을 기준에서 제외하고 병 본체의 연결된 알파 영역으로 가로/높이 비율을 산정한다. 여백을 포함한 정사각형 PNG 전체가 21cm로 축소되는 일을 방지한다. 가로 실측값은 추가하지 않았다. 카탈로그는 새 선택에 v2를 제공하며, 이전 v1 그림은 기존 편집본 복원용으로 유지한다. 전자기기 모델·치수는 유지한다.

프리뷰와 최종 생성은 같은 서버 렌더러를 계속 사용한다. 생성 PNG와 기존 ZIP·업로드 export 바이트 보존 검사도 통과했다. 실행 중 비교용 이미지를 외부에서 조회하지 않는다.

## 검증 결과

| 검사 | 결과 |
|---|---|
| Java 전체 verify | 779개 통과, 실패·오류·생략 0 |
| 자동 맞춤 구간을 강화한 렌더러 재검사 | 22개 통과 |
| JavaScript 전체 | 250개 통과 |
| 사이즈 편집 모의 화면 | 1920·1440·390px 통과 |
| 기존 상품 이미지 화면 회귀 | 2560·1440·390px 통과 |
| diff 공백 검사 | 통과 |

8종 모두 자동 맞춤이 작동하는 확대 구간의 실제 PNG에서 상품색 픽셀 면적 증가를 대조했다. 높이 점선 길이와 비교 대상 높이도 같은 배율로 증가한다. 100px/10cm에서 물병 210px, 200px/10cm에서 420px라는 원래 교정 기준은 유지한다. 본체 비율·출력 해상도·표 보호·수직 고정·잘못된 모델과 좌표·높이 입력의 기존 검사도 통과했다.

화면 검사는 Java 렌더러가 만든 0.6·1.2배 PNG를 모의 API로 반환하고 브라우저 캔버스에서 실제 상품색 픽셀을 센다. 두 배 확대 시 상품색 면적이 3배 이상 증가하는지 확인해 레이아웃 값만 바뀌는 회귀를 잡는다. 비교 해제·초기화·재편집 복원·마우스/터치·키보드·프리뷰 및 생성 실패 입력 보존·지연 응답 격리·FormActions·모달 스크롤 잠금도 검사한다. 실제 모바일 기기나 운영 서버 검증을 뜻하지 않는다.

Java 전체 검사는 IDE 출력과 분리된 `target/size-comparison-check`에서 실행했다. 이후 테스트를 자동 맞춤 구간으로 강화해 해당 렌더러 22개를 다시 실행했다. JavaScript와 화면 검사는 로컬 모의 API만 사용하며 외부 트래픽을 차단했다.

## 재현 명령

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  CLOUDFLARE_R2_ENABLED=false MOLEBUTTER_COUPANG_LIVE=false MOLEBUTTER_ORDER_LIVE=false \
  ./mvnw -o -Dmolebutter.build-directory=target/size-comparison-check verify

JAVA_HOME=/opt/homebrew/opt/openjdk@21 CLOUDFLARE_R2_ENABLED=false \
  ./mvnw -o -Dmolebutter.build-directory=target/size-comparison-check -pl molebutter-app -am \
  -Dtest=SizeGuideComparisonRendererTest -Dsurefire.failIfNoSpecifiedTests=false test

node --test src/test/js/*.test.cjs
node scripts/test-size-comparison-ui.cjs
node scripts/test-product-images-ui.cjs
git diff --check
```

현재 환경에서는 번들 Node·Playwright 경로를 사용한다. Java 검사가 실제 PNG fixture를 `molebutter-app/target/size-comparison-check/comparison-fixtures/`에 만들고 화면 캡처는 루트 `target/ui-check/size-comparison-{1920,1440,390}.png`에 생성한다.

## 이미지 작업 프롬프트

최종 채택 파일은 첫 배경 제거 결과다. 후속 정리 시안은 채택하지 않았다. 내장 도구에 `transparent_background=true`와 사용자의 두 번째 첨부 사진을 전달했다.

```text
Use case: background-extraction. Edit target: the supplied photograph of a Samdasoo label-free 500ml water bottle. Remove only the white background to true transparent alpha. Preserve the identical bottle silhouette, cap, proportions, embossed Korean lettering, clear PET material, water and fine ribs. No redesign, no new label, no crop of bottle, no shadow, no extra elements. Upright front view, centered; tightly fit the entire cap-to-base bottle within a transparent canvas with minimal transparent padding. This will be a calibrated 21cm tall physical-size comparison asset.
```
