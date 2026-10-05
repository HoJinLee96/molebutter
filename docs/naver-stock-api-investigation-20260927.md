# 네이버 쇼핑윈도 재고 API 조사

> 문서 유형: 과거 조사·설계·검증 기록. 수치·외부 응답·적용 여부는 기록 당시 기준이며 현재 실행 지침이 아니다. 개인 경로와 target 산출물도 당시 근거로 보존한다. 현재 정책과 실행 방법은 [문서 목차](README.md)에서 확인한다.

## 실제 호출 결과

2026-09-27 19:51:35 KST에 아래 요청이 HTTP 200 / `application/json`으로 성공했다. 쿠키·로그인·API 키 없이 호출했다. 응답은 `Cache-Control: max-age=120`이므로 주문 시점의 실시간 재고를 보장하지 않는다.

```sh
curl --fail-with-body --silent --show-error --max-time 25 \
  'https://shopping.naver.com/v2/channel-products/10513327648' \
  -H 'Accept: application/json' \
  -H 'User-Agent: Mozilla/5.0'
```

재고 경로는 `contents.stockQuantity`이며 조회 당시 값은 **41**이다. 이 헤더 조합으로 성공했다는 의미이며, 각 헤더가 필수인지는 따로 검증하지 않았다.

응답에서 필요한 필드만 발췌:

```json
{
  "_id": "10513327648",
  "channel": {
    "name": "헤지스ACC",
    "verticalType": "DEPARTMENT",
    "storeCategory": {
      "wholeNames": ["현대백화점", "목동점"]
    }
  },
  "contents": {
    "id": 10513327648,
    "name": "[헤지스] [HIWA4F450] 블랙 단색 나일론 미니숄더백",
    "productNo": 10462757506,
    "stockQuantity": 41,
    "productStatusType": "SALE",
    "channelProductStatusType": "NORMAL",
    "channelProductDisplayStatusType": "ON",
    "optionUsable": false,
    "epInfo": {"syncNvMid": 88057833008},
    "naverShoppingSearchInfo": {"modelName": "HIWA4F450"}
  }
}
```

## ID 구분

| 값 | 의미 / 사용처 |
| --- | --- |
| `HIWA450` | 사용자가 제공한 네이버 검색어. API 입력 ID가 아님 |
| `HIWA4F450` | 실제 응답의 모델 코드. 검색어와 구분하여 보존 |
| `10513327648` | 제공된 `/window-products/department/10513327648` URL의 채널 상품번호. 이번 API 경로에 사용 |
| `88057833008` | `contents.epInfo.syncNvMid`. 제공된 이미지 파일명의 번호와 일치하며 채널 상품번호와 다름 |
| `10462757506` | 응답의 `contents.productNo`. 이번 요청 경로에 사용하지 않음 |

`NaPm`, `nl-query` 추적·검색 파라미터나 이미지 URL은 이번 재고 요청에 필요하지 않았다.

## at-a-glance 참고 구현

- `../at-a-glance/src/main/java/store/korcokrlee/ataglance/infra/product/NaverShoppingChannelProductClient.java`: 기본 주소 `https://shopping.naver.com/v2/channel-products/`, GET 요청, 루트 또는 `contents` 아래의 `stockQuantity` 추출.
- `../at-a-glance/src/main/java/store/korcokrlee/ataglance/infra/product/NaverSmartStoreStockFetcher.java`: snapshot의 재고를 사용하며, 누락된 재고를 0으로 대체하지 않는다.

## molebutter 연동 시 확인할 부분

- `MallOptionGateway.inspect()`는 이미 같은 API 주소를 사용한다.
- `MallOptionParser.details()`는 네이버 매장 정보만 반환하며 옵션 목록을 의도적으로 비운다.
- `SupplierLookupService`는 네이버를 `DEFERRED`로 반환한다. API 성공과 현재 UI에서 재고를 표시하는 것은 별개의 상태다.
- 기존 `MallOptionParser.naver()`는 루트 `id` 및 `optionInfo.optionUsable`을 기대한다. 이번 실제 응답은 `contents.id` 및 `contents.optionUsable`이므로 그대로는 단일상품 재고를 추출하지 못한다.
- 연동한다면 요청 상품번호와 응답 ID의 일치를 확인하고, `contents.optionUsable=false`가 명시된 경우에 단일상품 재고로 취급해야 한다. 모델은 `contents.naverShoppingSearchInfo.modelName`에서 읽는다.
- 이 상품에는 옵션 배열이 없다. 옵션 상품의 구조·옵션별 수량은 추가 샘플 검증이 필요하며, 전체 `stockQuantity`를 개별 옵션 재고로 사용해서는 안 된다.

이번 작업은 API 조사와 단일 샘플의 실제 호출 검증이며 애플리케이션 재고 조회 기능을 활성화하지 않았다.

## 범위와 실패 처리

제공된 링크는 `DEPARTMENT` 백화점 쇼핑윈도 상품이다. 이 한 건의 성공으로 일반 스마트스토어·브랜드스토어 전체의 호환성을 확정할 수 없다.

이 주소는 at-a-glance에서 사용하는 쇼핑 프런트엔드 조회 경로다. 공식 판매자용 커머스API의 `/external/...` 주소와 구분해야 하며, 공개된 공식 재고 API 계약으로 확인한 것은 아니다. 공식 API 문서는 [네이버 커머스API 상품 목록 조회](https://apicenter.commerce.naver.com/docs/commerce-api/current/search-product)를 참고한다.

403·429·HTML 오류 페이지·필드 누락은 재고 0이 아니라 조회 제한/미확인으로 다뤄야 한다. 성공 JSON도 상품 ID와 숫자 필드의 타입을 검증한 뒤 사용한다.

## 추가 검증: 브랜드패션 WCBA5E052BK

2026-09-27 20:47:43 KST, 제공된 브랜드패션 상품도 같은 헤더로 비로그인 조회에 성공했다.

```http
GET https://shopping.naver.com/v2/channel-products/13197489089
```

- HTTP 200 / `application/json`, `Cache-Control: max-age=120`.
- 루트 `_id`와 `contents.id`는 모두 요청 상품번호 `13197489089`와 일치한다.
- `channel.name="닥스 DAKS"`, `channel.verticalType="BRAND_FASHION"`.
- `contents.naverShoppingSearchInfo.modelName="WCBA5E052BK"`.
- `contents.epInfo.syncNvMid=90742000186`으로 제공된 이미지 파일 번호와 일치한다.
- `contents.productStatusType="SALE"`, `contents.stockQuantity=2`.
- `contents.optionUsable=true`이며 옵션 배열은 **`contents.optionCombinations`에 직접 존재**한다. 이 응답에는 `optionInfo` 중첩이 없다.

```json
{
  "optionUsable": true,
  "stockQuantity": 2,
  "optionCombinations": [
    {
      "id": 56549039311,
      "optionName1": "FREE",
      "stockQuantity": 2,
      "price": 0,
      "regOrder": 0
    }
  ]
}
```

위는 `contents`의 관련 필드 발췌다. **FREE 옵션 2개**가 명시적으로 확인되었다. 앞선 백화점 단일상품 샘플에 더해 브랜드패션의 단일 옵션 상품까지 확인한 결과이며, 다중 옵션 조합이나 모든 스마트스토어 유형을 검증한 것은 아니다. 재고 파서를 연동할 때에는 `contents.optionCombinations[].stockQuantity`를 읽는 경로도 필요하다. 이번 추가 확인 역시 애플리케이션 기능 활성화 없이 조사 문서만 갱신했다.
