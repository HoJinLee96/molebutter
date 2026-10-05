# 롯데ON 재고·판매업체명 API 조사

> 문서 유형: 과거 조사·설계·검증 기록. 수치·외부 응답·적용 여부는 기록 당시 기준이며 현재 실행 지침이 아니다. 개인 경로와 target 산출물도 당시 근거로 보존한다. 현재 정책과 실행 방법은 [문서 목차](README.md)에서 확인한다.

## 결론과 실제 응답

제공된 상품은 아래 API 한 번으로 재고와 업체명을 함께 확인할 수 있었다.

```http
GET https://pbf.lotteon.com/product/v2/detail/search/base/sitm/LO2630710241_2630710242
```

2026-09-27 20:16:36 KST 응답 기준 HTTP 200, JSON `returnCode="200"`. 쿠키·로그인·API 키 없이 `Accept: application/json`, `User-Agent: Mozilla/5.0` 헤더로 성공했다. 각 헤더의 필수 여부는 따로 검증하지 않았다.

| 정보 | 응답 경로 | 확인값 |
| --- | --- | --- |
| **업체명** | **`data.basicInfo.trNm`** | **주식회사 LF** |
| 업체명 교차 확인 | `data.slrInfo.trBase.trNm` | 주식회사 LF |
| 업체 식별자 | `data.basicInfo.trNo` / `data.slrInfo.trBase.trNo` | LO10004813 |
| 판매 채널/매장 표시 이름 | `data.basicInfo.lrtrNm` / `data.slrInfo.trBase.lrtrNm` | LFMALL |
| 별도 판매자 표시명 | `data.slrInfo.sellerNm` | 헤지스 |
| 하위 거래처 식별자 | `data.basicInfo.lrtrNo` | SLO1000000015008 |
| 판매자 구분 | `data.basicInfo.trGrpCd` / `trGrpNm` | SR / 일반셀러 |
| 원상품 번호 | `data.basicInfo.pdNo` | LO2630710241 |
| 선택 옵션 상품번호 | `data.basicInfo.sitmNo` | LO2630710241_2630710242 |
| 모델 코드 | `data.basicInfo.mdlNo` | WCBA5E052BK |
| 선택 옵션 | `data.basicInfo.sitmNm` | FREE |
| 선택 옵션 재고 | `data.stckInfo.stkQty` | **2** |
| 재고 관리 여부 | `data.stckInfo.stkMgtYn` | Y |
| 재고 숨김 여부 | `data.stckInfo.hideStkQty` | false |
| 선택 옵션 판매 상태 | `data.basicInfo.sitmSlStatCd` | SALE |

`주식회사 LF`는 추정하거나 `LFMALL`을 수동 변환한 이름이 아니라 API에 명시된 문자열이다. `slrInfo.sellerNm`은 이번 응답에서 `헤지스`이므로 법인 업체명 대신 사용하면 안 된다. 이 상품은 일반셀러이므로 업체명을 백화점 지점명으로 처리하면 안 된다. 업체 식별에는 `trNo`, 하위 거래처 구분에는 `lrtrNo`를 별도로 보존할 수 있으나 다른 상품에서도 동일한 단위로 유지되는지는 추가 검증이 필요하다.

## 호출 예제

```sh
curl --fail-with-body --silent --show-error --max-time 25 \
  'https://pbf.lotteon.com/product/v2/detail/search/base/sitm/LO2630710241_2630710242' \
  -H 'Accept: application/json' \
  -H 'User-Agent: Mozilla/5.0' \
  | jq '{
      returnCode,
      productId: .data.basicInfo.pdNo,
      itemId: .data.basicInfo.sitmNo,
      model: .data.basicInfo.mdlNo,
      company: .data.basicInfo.trNm,
      sellerCompany: .data.slrInfo.trBase.trNm,
      shop: .data.basicInfo.lrtrNm,
      sellerId: .data.basicInfo.trNo,
      option: .data.basicInfo.sitmNm,
      stock: .data.stckInfo.stkQty,
      status: .data.basicInfo.sitmSlStatCd
    }'
```

입력에는 제공된 URL의 **`sitmNo` 쿼리 값 전체**를 사용한다. 상품 경로의 `LO2630710241`만 넣는 호출은 이번에 검증하지 않았다. 검색어 `WCBA052`, 네이버 이미지 번호 `59139939089`, 유입 추적 파라미터는 이번 API 요청에 사용하지 않았다. 이미지 번호와 롯데ON 상품 간의 ID 매핑은 이번 응답으로 확인하지 않았다.

## 옵션별 재고 구조

이 상품은 FREE 한 개뿐이지만 `optionList`는 비어 있지 않다. 선택지 값과 재고를 아래처럼 연결할 수 있다.

```json
{
  "optionList": [{
    "title": "사이즈",
    "options": [{
      "label": "FREE",
      "value": "720679812FREE",
      "disabled": false
    }]
  }],
  "optionMappingInfo": {
    "720679812FREE": {
      "spdNo": "LO2630710241",
      "sitmNo": "LO2630710241_2630710242",
      "spdNoSlStatCd": "SALE",
      "sitmNoSlStatCd": "SALE",
      "stkQty": 2,
      "hideStkQty": false
    }
  }
}
```

위 JSON은 `data.optionInfo`의 관련 필드만 발췌했다. `optionList[].options[].value`가 이번 단일 옵션의 `optionMappingInfo` 키와 일치하며, 매핑의 `sitmNo`와 선택 상품 ID도 일치한다. `stckInfo.stkQty`와 옵션 매핑 수량이 모두 2임을 검증했다. 여러 옵션 축의 조합 키 생성 규칙과 다중 옵션 상품 전체의 동작은 아직 검증하지 않았다. 선택된 상품의 재고를 모든 옵션의 합계로 해석하지 않는다.

## 기존 코드와 차이

- at-a-glance 재고 조회 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/infra/product/LotteOnStockFetcher.java:34`는 같은 API의 `data.stckInfo.stkQty`를 읽는다. 다만 누락값을 `asLong(0)`으로 바꾸므로 그대로 가져오면 조회 미확인을 품절로 오인할 수 있다.
- at-a-glance 지점 조회 — `/Users/leehj/workspace/at-a-glance/src/main/java/store/korcokrlee/ataglance/infra/product/LotteOnProductDetailClient.java:44`는 `lrtrNm`과 `trNm`에서 지점명 패턴을 추출한다. `주식회사 LF` 같은 법인명을 원문 업체명으로 반환하는 경로와는 다르다.
- molebutter 상품 상세 — `/Users/leehj/workspace/molebutter/src/main/java/cc/ataglace/molebutter/infra/product/MallOptionParser.java:72`는 `lrtrNm`을 `trNm`보다 먼저 읽으므로 이번 상품의 표시 이름은 `LFMALL`이 된다. **업체명이 목적이면 `trNm`을 명시적으로 읽어야 한다.**
- molebutter 매장 메타데이터 — `/Users/leehj/workspace/molebutter/src/main/java/cc/ataglace/molebutter/infra/product/SupplierMetadataParser.java:14`는 롯데ON에서 지점명만 찾는다. 일반 판매업체명·업체 ID를 별도로 보존하는 처리가 필요하다.
- molebutter 재고 파서 — `/Users/leehj/workspace/molebutter/src/main/java/cc/ataglace/molebutter/infra/product/MallOptionParser.java:100`는 빈 `optionList`와 단일 SKU를 요구한다. 이번 상품은 `optionList`가 있으므로 현재 조건에서는 재고를 추출하지 못한다. 옵션 매핑을 검증해 읽는 경로가 필요하다.

이번 작업은 API 조사 및 실제 읽기 호출 검증이며 애플리케이션의 업체명·재고 판별 동작은 변경하지 않았다. 이 주소는 기존 코드에서 사용하는 프런트엔드 상세 API이며 공식 외부 연동 계약으로 확인한 것은 아니다. 수량은 조회 당시 값이다. 연동 시 HTTP 상태뿐 아니라 `returnCode`, 요청/응답 상품 ID 일치, 재고 숫자 타입 및 숨김 여부를 확인하고 실패·필드 누락을 0으로 대체하지 않는다.

위 절대경로와 줄 번호는 조사 당시의 소스 위치 기록이다. 다른 환경에서 열리는 링크가 아니며, 당시 조사 결론과 근거 위치를 보존한다.
