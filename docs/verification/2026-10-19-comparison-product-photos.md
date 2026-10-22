# 비교 대상 제품 사진과 내부 모델명

검증일: 2026-10-19. 운영 DB·실제 R2 객체·실행 중인 IDE 앱은 변경하지 않았다. 새 엔드포인트·DB 구조·마이그레이션은 없다.

## 구현

전자기기 7종의 단순 도형을 로컬 투명 PNG로 교체했다. 휴대폰·아이패드는 세로 정면, 맥북은 닫힌 상판을 위에서 본 모습이다. 갤럭시는 제조사의 투명 PNG를 그대로 사용했다. Apple 6종은 공식 사진을 입력으로 built-in `imagegen`에서 배경·다른 기기·외부 치수선을 제거했다. iPhone은 공식 정면 도해를 보조 자료로 사용해 정면으로 보정했다. 따라서 이 6종은 공식 원본을 그대로 복사한 파일이 아니라 AI 편집을 거친 비교용 이미지다. CLI/API fallback은 사용하지 않았다.

가로·높이는 기존 고정 모델의 본체 치수를 그대로 사용한다. 투명 여백을 제외한 가장 큰 연결 본체를 해당 사각형에 그리며 사진 캔버스 크기로 px/cm를 계산하지 않는다. 제품 높이 점선, 공통 확대·축소, 자동 맞춤과 최종 출력 배율은 유지한다. 전자기기 새 선택은 v2이며 v1 편집본도 같은 기존 치수·좌표를 유지하고 사진으로 표시한다. 삼다수의 과거 v1은 다른 본체 비율의 기존 도형으로 읽을 수 있고, 현재 선택 v2는 기존 누끼 PNG를 유지한다.

`삼다수 500ml`, `iPhone 16` 등 핵심 이름은 모든 비교 대상 본체 안쪽 하단에 반투명 흰 바탕으로 표시한다. M4/M5·기준 높이는 출력 문구에서 제외하되 모델 목록과 계산 치수는 보존한다. 긴 이름은 줄바꿈하고 본체 크기에 맞춰 글자를 조정한다. 이름이 본체 밖 공간을 차지하지 않아 캡션 때문에 상품 전체를 추가 축소하지 않는다. 매우 작은 대상의 글자도 내부 영역에서만 그린다.

실행 중 외부 이미지를 다운로드하지 않는다. 프리뷰·최종 PNG·재편집·ZIP·R2용 export는 동일한 렌더러와 번들 파일을 사용한다.

## 저장 파일과 출처

모든 파일은 `molebutter-imaging/src/main/resources/imaging/comparisons/`에 있다. 기존 삼다수 파일은 유지했다.

| 파일 | 공식 입력 이미지 |
|---|---|
| `iphone-16-v2.png` | [Apple iPhone 16](https://cdsassets.apple.com/live/7WUAS350/images/tech-specs/iphone-16.png), [정면 보조 도해](https://ipcdn-web.apple.com/assets/v2/web/f7924eae-6280-4678-b84d-12b3956c8995) |
| `galaxy-s26-v2.png` | [Samsung 공식 스토어 정면 PNG](https://shop.samsung.com/ie/images/products/30419/38382/600x600/M1PU512.png) |
| `ipad-air-11-m4-v2.png` | [Apple iPad Air 11 M4](https://cdsassets.apple.com/live/7WUAS350/images/tech-specs/ipad-air-11-inch-m4.png) |
| `ipad-air-13-m4-v2.png` | [Apple iPad Air 13 M4](https://cdsassets.apple.com/live/7WUAS350/images/tech-specs/ipad-air-13-inch-m4.png) |
| `macbook-air-13-m5-v2.png` | [Apple MacBook Air 13 상판](https://www.apple.com/v/macbook-air/specs/c/images/specs/13-inch/mba_13_size1__eyfditb7ixea_large.jpg) |
| `macbook-air-15-m5-v2.png` | [Apple MacBook Air 15 상판](https://www.apple.com/v/macbook-air/specs/c/images/specs/15-inch/mba_15_size_top__f0yqeuf0a4y2_large.jpg) |
| `macbook-pro-16-m5-v2.png` | [Apple MacBook Pro 16 상판](https://www.apple.com/v/macbook-pro/specs/d/images/specs/16-inch/dimensions_1_16_inch__gknf878bnyqa_large.jpg) |
| `samdasoo-500-v2.png` | 기존 사용자 제공 삼다수 사진의 누끼 파일 |

## 검증

Java 출력 경로는 IDE와 분리한 `target/size-comparison-check`를 사용한다. Java 렌더러는 8종 투명 리소스·본체 치수·v1 호환·내부 이름 경계·100px/200px 높이 교정·공통 배율·프리뷰와 최종 PNG를 검사한다. 모의 화면은 실제 Java PNG를 사용한다.

| 검사 | 결과 |
|---|---|
| Java 전체 `verify` | 797개 통과, 실패·오류·생략 0 |
| 최종 관련 Java 재검사 | 렌더러 40개·서비스 7개·렌더링 안전 4개 통과 |
| JavaScript 전체 | 250개 통과 |
| 사이즈 편집 모의 화면 | 1920·1440·390px 통과 |
| 기존 이미지 화면 회귀 | 2560·1440·390px 통과 |
| 시각 확인 | 8종 출력 PNG와 PC·모바일 편집 캡처 확인 |

전체 Java 검증 뒤 v1 전자기기 편집본의 사진 표시와 더 이상 사용하지 않는 전자기기 도형 분기를 정리하고 관련 Java 51개를 다시 실행했다. 브라우저 검증은 로컬 모의 API이며 실제 운영 서버·모바일 기기 검증은 아니다. 공식 이미지의 읽기 외에 쿠팡·R2에 연결하거나 운영 DB·IDE 프로세스를 변경하지 않았다.

재현 명령은 [출력 배율 검증](2026-10-19-size-output-scale.md#재현)의 동일한 Maven·Node 명령을 사용한다. 실제 렌더러 PNG는 `molebutter-app/target/size-comparison-check/comparison-fixtures/`, 화면 캡처는 `target/ui-check/size-comparison-{1920,1440,390}.png`에 생성한다.

## 최종 imagegen 프롬프트

아래 입력은 built-in 도구에 `transparent_background=true`로 전달했다. 최종 선택 결과를 위 프로젝트 경로에 복사했다. 갤럭시는 이미 투명한 공식 PNG여서 생성 프롬프트가 없다.

### iphone-16

```text
Use case: background-extraction. Image 1 is the actual official Apple iPhone 16 product photograph and edit target. Extract ONLY the blue front-facing phone on the far right. Remove all other phones and background. Image 2 is an official iPhone 16 hardware diagram used ONLY to guide a straight, upright orthographic front silhouette. Rectify the extracted phone to a straight-on portrait front view with body width/height ratio 7.16/14.76. Preserve the exact blue frame, black bezel, Dynamic Island and blue-purple circular wallpaper of the photographed phone. No other device, no back view, no diagram numbers, no guide lines, no added text, no labels, no watermark. Entire phone intact. True transparent alpha outside, opaque screen inside. Tight transparent padding.
```

### ipad-air-11-m4

```text
Use case: background-extraction. Edit target: supplied official manufacturer product image. Apple iPad Air 11-inch (M4), portrait front. Keep ONLY the complete front tablet already visible on the right; remove the separate back tablet behind it. Preserve screen wallpaper, bezel, camera and corners exactly. Body aspect width/height 17.85/24.76. Remove ONLY background, other devices, dimension guide lines outside the body, and shadows. Do not redesign or create a different product. Return exactly one full device, no perspective or rotation changes, no keyboard, no open lid. True transparent alpha outside the silhouette, opaque screen/metal body inside. No captions, no extra text, no watermark. Tight transparent canvas with the entire body intact and minimal padding. Intended as a calibrated physical-size comparison asset.
```

### ipad-air-13-m4

```text
Use case: background-extraction. Edit target: supplied official manufacturer product image. Apple iPad Air 13-inch (M4), portrait front. Keep ONLY the complete front tablet already visible on the right; remove the separate back tablet behind it. Preserve screen wallpaper, bezel, camera and corners exactly. Body aspect width/height 21.49/28.06. Remove ONLY background, other devices, dimension guide lines outside the body, and shadows. Do not redesign or create a different product. Return exactly one full device, no perspective or rotation changes, no keyboard, no open lid. True transparent alpha outside the silhouette, opaque screen/metal body inside. No captions, no extra text, no watermark. Tight transparent canvas with the entire body intact and minimal padding. Intended as a calibrated physical-size comparison asset.
```

### macbook-air-13-m5

```text
Use case: background-extraction. Edit target: supplied official manufacturer product image. Apple MacBook Air 13-inch M5, CLOSED lid viewed straight from above, as in this exact source. Preserve the pale blue metal surface and centered Apple logo exactly. Body aspect width/height 30.41/21.50. Remove ONLY background, other devices, dimension guide lines outside the body, and shadows. Do not redesign or create a different product. Return exactly one full device, no perspective or rotation changes, no keyboard, no open lid. True transparent alpha outside the silhouette, opaque screen/metal body inside. No captions, no extra text, no watermark. Tight transparent canvas with the entire body intact and minimal padding. Intended as a calibrated physical-size comparison asset.
```

### macbook-air-15-m5

```text
Use case: background-extraction. Edit target: supplied official manufacturer product image. Apple MacBook Air 15-inch M5, CLOSED lid viewed straight from above, as in this exact source. Preserve the pale blue metal surface and centered Apple logo exactly. Body aspect width/height 34.04/23.76. Remove ONLY background, other devices, dimension guide lines outside the body, and shadows. Do not redesign or create a different product. Return exactly one full device, no perspective or rotation changes, no keyboard, no open lid. True transparent alpha outside the silhouette, opaque screen/metal body inside. No captions, no extra text, no watermark. Tight transparent canvas with the entire body intact and minimal padding. Intended as a calibrated physical-size comparison asset.
```

### macbook-pro-16-m5

```text
Use case: background-extraction. Edit target: supplied official manufacturer product image. Apple MacBook Pro 16-inch M5 Pro/Max, CLOSED lid viewed straight from above, as in this exact source. Preserve silver metal and centered black Apple logo exactly. Body aspect width/height 35.57/24.81. Remove ONLY background, other devices, dimension guide lines outside the body, and shadows. Do not redesign or create a different product. Return exactly one full device, no perspective or rotation changes, no keyboard, no open lid. True transparent alpha outside the silhouette, opaque screen/metal body inside. No captions, no extra text, no watermark. Tight transparent canvas with the entire body intact and minimal padding. Intended as a calibrated physical-size comparison asset.
```
