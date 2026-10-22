# 비교 선택 전후 출력 배율과 현재 배치 맞춤

검증일: 2026-10-19. 운영 DB·실제 R2 객체·실행 중인 IDE 앱을 변경하지 않은 코드·로컬 모의 검증이다. 신규 테이블·마이그레이션·엔드포인트는 없다.

## 변경

이전 구현은 비교 없음에선 슬라이더 값을 그대로 사용하고, 비교가 있으면 미래 최대 배치를 위한 별도 맞춤 배율을 곱했다. 원본 사진의 배경 여백도 맞춤에 포함했고 슬라이더 최대값까지 동적으로 변경해 같은 조절 바 위치가 같은 출력 크기를 나타내지 못했다.

슬라이더 범위를 0.01~2.5로 고정했다. 서버는 현재 실제 상품 본체·치수선과 숫자·비교 대상·캡션을 표 위 영역에 배치한다. 상품 사진의 흰색·투명 여백과 미래 확대 공간을 제외하며, 현재 내용이 공간을 넘을 때만 축소·이동한다. 공간이 충분하면 비교 추가·제거 시 상품 크기를 유지한다. 비교 때문에 축소가 필요하면 그 결과를 반환 레이아웃의 사진 배율과 모든 좌표에 반영한다. 슬라이더는 이 최종 배율을 표시하고 재편집·PNG 생성도 같은 레이아웃을 사용한다.

현재 배치의 최대 출력 배율은 별도 문구로 표시한다. 슬라이더를 그 이상 움직이면 해당 배율에서 제한해 초과 구간에서 반복 요청하거나 자동 축소가 확대를 상쇄하지 않는다. 큰 비교 대상과 작은 상품의 실제 크기 관계 때문에 필요한 축소는 유지한다. 대상만 줄이거나 비율을 바꾸지 않는다. 상품·점선·비교 대상의 확대·축소는 항상 같은 비율이다.

출력 레이아웃은 선택적 `outputSpace=true`를 보존한다. 원본 배경 중심이 가상 좌표 밖에 놓일 수 있어 출력 좌표만 유한한 ±10000 범위를 허용하고 배율은 0.01~2.5로 제한한다. 과거 입력의 좌표·배율 범위와 생성자 호환은 유지한다. 기존 `sceneTransform`은 새 응답에서 항등 변환이다. 추가 `photoView`는 최대 출력 배율과 실제 상품 중심의 오프셋을 제공해 사진 이동 핸들이 배경 중앙에 놓이지 않게 한다. 서버는 클라이언트의 최대 배율을 신뢰하지 않고 다시 맞춘다.

## 결과

| 검사 | 결과 |
|---|---|
| Java 전체 verify | 781개 통과, 실패·오류·생략 0 |
| 최종 관련 Java 재검사 | 렌더러 24개·서비스 7개·렌더링 안전 4개 통과 |
| JavaScript 전체 | 250개 통과 |
| 사이즈 편집 모의 화면 | 1920·1440·390px 통과 |
| 기존 이미지 화면 회귀 | 2560·1440·390px 통과 |
| diff 공백 검사 | 통과 |

Java 전체 검사 후 오류 경로의 렌더링 자원 정리와 가상 좌표 안전성, 원본 한쪽으로 치우친 상품의 핸들 중심 검사를 보완하고 관련 Java 검사를 재실행했다.

서버는 8종 모델의 원래 본체 비율·높이 교정·최종 PNG 해상도·표 보존, 확대 전후 실제 상품색 픽셀 면적 증가, 현재 배치의 최대 크기, 프리뷰/생성 PNG·ZIP·업로드 export 바이트 일치를 검사한다. 2000×2000 원본의 한쪽에 작은 지갑만 있는 사례에서 비교 선택 전후 2배 출력과 상품 픽셀 면적이 유지되는지 대조했다. 공간이 부족해 축소한 결과는 숨은 카메라 배율 없이 최종 사진 배율에 반영되며 재진입 PNG 바이트도 동일하다. 이전 모델 버전, 잘못된 높이·모델·유한하지 않은 좌표, 과거 좌표 계약도 검사한다.

모의 화면은 실제 Java PNG를 사용해 지갑의 비교 추가·제거 전후 픽셀 면적과 슬라이더 값·범위를 대조한다. 축소된 8종 PNG에서도 슬라이더 값이 반환된 최종 배율과 일치해야 한다. 별도 실제 PNG 확대 사례의 상품 픽셀 증가, 상한 표시, 마우스·터치·키보드·재편집 복원·실패 입력 보존·지연 응답·FormActions·모달 스크롤 잠금도 검사한다. 브라우저 테스트는 모의 API이며 실제 모바일 기기나 운영 서버 검증을 뜻하지 않는다.

## 재현

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  CLOUDFLARE_R2_ENABLED=false MOLEBUTTER_COUPANG_LIVE=false MOLEBUTTER_ORDER_LIVE=false \
  ./mvnw -o -Dmolebutter.build-directory=target/size-comparison-check verify

JAVA_HOME=/opt/homebrew/opt/openjdk@21 CLOUDFLARE_R2_ENABLED=false \
  ./mvnw -o -Dmolebutter.build-directory=target/size-comparison-check -pl molebutter-app -am \
  -Dtest=SizeGuideComparisonRendererTest,SizeGuideServiceTest,RenderSafetyTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

node --test src/test/js/*.test.cjs
node scripts/test-size-comparison-ui.cjs
node scripts/test-product-images-ui.cjs
git diff --check
```

현재 환경에서는 번들 Node·Playwright 경로를 사용했다. Java 출력은 IDE 출력과 분리된 `target/size-comparison-check`다. 실제 PNG fixture는 `molebutter-app/target/size-comparison-check/comparison-fixtures/`, 화면 캡처는 루트 `target/ui-check/size-output-wallet-{1920,1440,390}-with.png`와 `size-comparison-{1920,1440,390}.png`에 생성한다.
