# 공통 배송·판매기간·A/S 설정 — 2026-10-14

## 구현 범위

통합 상품 편집 화면의 공통 기준에 빠진 항목을 추가하고 기존 배송·반품 항목을 정리했다. 쿠팡·스마트스토어·롯데ON·11번가의 기존 등록·수정 어댑터에 연결했다. ESM은 제외한다.

| 구역 | 입력과 저장 |
|---|---|
| 배송 | 배송 방식, 이름으로 고르는 택배사, 묶음배송, 출고 소요일 |
| 배송비 | 무료·유료·조건부 무료·착불, 기본 배송비, 무료배송 기준 |
| 도서산간 | 배송 가능 여부, 제주 추가비, 제주 외 추가비 |
| 반품 | 초도 배송비와 회수 배송비 각각 편도 입력, 왕복 합계 표시 |
| 판매기간 | 시작·종료 일시, 현재부터 2099-12-31 23:59까지 채우는 체크 |
| 과세·A/S | 과세·면세, A/S 책임자·연락처·안내 원문 |

새 JSON 필드는 `common.afterServiceManager`, `afterServiceTelephone`, `saleStartedAt`, `saleEndedAt`, `delivery.shippingDays`, `jejuCharge`, `remoteCharge`다. 기존 생성자·저장 API와 JSON 문서를 유지하며 스키마 마이그레이션은 추가하지 않았다. 과거 초안에서 새 필드는 빈 값으로 읽는다. 기존 A/S 안내에서 책임자나 전화번호를 추측하여 분리하지 않는다. 명시적인 `0`과 빈 값을 구분한다.

공통 택배사 선택은 CJ대한통운·롯데택배·한진택배·로젠택배·우체국택배·경동택배를 제공한다. 쿠팡/네이버의 코드와 11번가/롯데ON 코드는 별도로 변환한다. 대응표에 없는 코드를 임의로 만들지 않으며 마켓 전용 선택은 기존 조회 기능을 사용한다.

## 마켓 적용과 제한

| 마켓 | 적용 | 직접 대응하지 않는 값·검증 |
|---|---|---|
| 쿠팡 | 배송·택배사·묶음배송·반품 편도비·과세·판매기간, 옵션별 출고 소요일, A/S 상품고시 | 지역 추가비는 출고지의 선택 택배사 정책과 비교한다. 직접 수정한 옵션별 출고 소요일·고시를 유지한다. 공통 A/S를 명시적으로 적용할 때 대응 고시가 없으면 선택을 요구한다. |
| 스마트스토어 | 배송비·택배사·묶음배송·지역 추가비·회수 반품비·판매기간·A/S | 일반배송 출고 소요일, 배송 가능 지역, 별도 초도 배송비는 자동 변환하지 않고 미적용 안내를 표시한다. 교환비로 대체하지 않는다. |
| 롯데ON | 배송 유형·택배사·묶음배송·발송예정일수·판매기간·과세·A/S | 지역 그룹은 조회·선택한다. 배송비는 상품 필드에 금액을 만들어 넣지 않고 선택한 정책과 비교한다. 별도 초도 반품비를 확인할 수 없으면 정책 확인과 개별값 선택을 요구한다. |
| 11번가 | 배송 방식·택배사·배송 가능 지역·지역 추가비·묶음배송·회수 반품비·과세·A/S | 출고 소요일을 발송마감 템플릿 번호로 변환하지 않는다. 기존 템플릿을 직접 선택한다. 별도 초도 배송비·교환비는 자동으로 동일시하지 않는다. |

무료배송은 전송 전문의 기본 배송비·조건부 무료 기준만 0으로 만든다. 반품비와 지역 추가비 및 저장된 공통 입력은 유지한다. 유료/착불에서도 사용하지 않는 조건부 무료 기준은 0이다. 기존 상품의 배송과 무관한 수정에는 이 정규화를 새 변경으로 추가하지 않는다.

공통 판매기간은 유효한 시작·종료, 종료가 시작 이후, 2099년 이하를 확인한다. 각 마켓은 기존 규격 검사도 적용한다. 네이버의 시작 분 00/종료 분 59 등 날짜 정밀도, 롯데ON 발송예정일수 0~3, 쿠팡 옵션별 출고 소요일 범위를 별도로 검사한다. 11번가의 직접 입력 기간은 날짜 단위와 최대 3년을 확인한다. 공통의 현재 시각·2099 종료를 11번가용으로 잘라내거나 단축하지 않고 해당 마켓에서 수정하도록 안내한다. 기존 상품의 기간과 무관한 수정에 새 기간 제한을 소급 적용하지 않는다.

A/S 책임자·연락처는 안내 원문과 함께 마켓 형식으로 조립한다. 롯데ON은 `epnLst`의 `AS_CNTS`로 보내며 설명·주의사항의 다른 유형을 보존한다. 공통의 면세와 과거 네이버 `DUTYFREE` 값은 각 마켓의 면세 코드로 변환하고, 영세 등 전용 유형은 마켓 탭에서 지정한다.

## 정책 조회·선택

- 쿠팡: 출고지 조회의 활성 `remoteInfos` 중 선택한 택배사 정책을 하나로 확인하고 제주/기타 추가비를 비교한다. 문서의 `jejuFee`와 예시의 `jeju`를 모두 읽되 서로 다른 값이면 미확인으로 처리한다. 요청 세션 안의 읽기는 중첩 잠금 없이 기존 호출 간격을 공유한다.
- 스마트스토어: 기존 metadata에 `bundle-groups` 목록과 `bundle-group` 상세를 추가했다. 화면에서 그룹명·지역비를 보고 기존 그룹을 선택한다. 큰 ID는 문자열로 보존한다. 명시적으로 고른 그룹은 활성 여부를 확인하며, 묶음배송과 지역비 적용에 그룹이 필요한 경우 직접 선택을 요구한다. AREA_2/AREA_3 정책과 공통의 제주/기타 금액을 각각 비교한다. 상세 검증 조회는 오래된 메타데이터 캐시를 사용하지 않는다.
- 롯데ON: 기존 배송비 정책 목록에서 선택한 활성 정책의 유형·기본비·조건부 기준·회수비·지역비를 비교한다. `deliveryIntent`는 앱 내부 적용 의도이며 외부 전문에는 넣지 않는다. **선택한 정책 금액 사용**을 누르면 해당 마켓에서 공통 금액 비교를 해제한 개별값으로 저장한다. 과거 초안에 이 상태가 없으면 기존 개별 정책값을 보존한다.
- 정책 금액은 정수 입력과 응답의 십진수를 정확히 비교한다. `2500`과 `2500.00`은 같지만 `2500.01`, 값 누락, 읽기 실패를 같은 금액으로 취급하지 않는다. 정책 불일치·빈 목록·권한 오류·미확인은 검증 완료와 전송 준비를 막는다.

입력 검증·전송 준비·실행 직전의 관련 배송 변경에서 같은 검사를 사용한다. 기존 상품의 가격·재고 등 배송과 무관한 수정은 새 정책 조회 때문에 차단하지 않는다. 출고지·정책·묶음배송 그룹을 생성하거나 수정하는 외부 요청은 추가하지 않았다.

신규 등록의 공통 상속, 마켓 개별값, 명시적 선택 항목 적용을 기존 편집 세션에 연결했다. 공통 저장만으로 외부 상품을 변경하지 않는다. 방문하지 않은 마켓의 보관된 변경은 그대로 두며 탭 이동·임시 저장·재진입에서 적용 상태를 복원한다.

## 실행한 검증

| 검사 | 결과 |
|---|---|
| 관련 Java 단위·아키텍처·뷰 회귀 | 340개 통과, 실패·오류·스킵 0 |
| 전체 JS 테스트 | 319개 통과 |
| 격리 MySQL/Redis 통합 | 98개 통과: 편집 37, 전송 35, 공통 초안 8, 네이버 18 |
| 통합 화면 Chrome | PC 1440px·모바일 390px 통과 |
| 네이버 공용 폼 Chrome | PC 1440px·모바일 390px 통과 |
| 실행 JAR | 빌드 및 소스/클래스 포함 확인 |

회귀 검사에는 무료·유료·조건부 무료·착불 전문, 기존 개별값과 옵션별 소요일, 지역비 0/누락/십진수 비교, 정책 불일치·비활성·조회 실패, 큰 묶음배송 그룹 ID, A/S 설명 유형 보존, 날짜 정밀도·상한, 과세 코드, JSON 호환을 포함한다. 모의 쿠팡·네이버·롯데ON·11번가 CREATE/UPDATE 경로와 가격·재고 전용 변경도 검사했다.

브라우저는 현재 Thymeleaf HTML과 실제 화면 제어 코드를 사용하고 외부 마켓 요청은 모의 API로 대체했다. 새 입력·왕복 합계·기간 체크, 이름으로 택배사 선택, 임시 저장·새로고침·재진입, 탭 왕복, 가로 넘침, 조회 입력 Enter와 저장 입력 Enter의 쓰기 금지를 확인했다. 네이버 그룹 검색·선택 자체로 쓰기가 발생하지 않는 것도 검사했다.

### 재실행

Java 21, Node/Playwright 및 Maven을 사용한다. IDE가 기본 `target/classes`를 갱신하는 환경과 격리하기 위해 별도 빌드 디렉터리를 사용했다.

```sh
mvn -Dmolebutter.build-directory=target/shipping-verification -pl molebutter-app -am -Dtest=CoupangCommonDeliveryTest,CoupangClientTest,CoupangProductDocumentsTest,CoupangWriteGatewayTest,NaverCommonProjectionTest,NaverDeliveryPolicyTest,NaverCatalogTest,NaverGatewayTest,NaverProductDocumentsTest,NaverWriteGatewayTest,LotteonDeliveryPolicyTest,LotteonProductMappingTest,ElevenstCommonPeriodTest,ElevenstIntegrationTest,MarketplaceTargetEditingTest,MarketplaceSnapshotCompatibilityTest,SubmissionExecutionTest,LayoutViewTest,ModuleArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false package
MOLEBUTTER_COUPANG_LIVE=false MOLEBUTTER_ORDER_LIVE=false CLOUDFLARE_R2_ENABLED=false bash scripts/test-integration.sh -pl molebutter-app -am -Dtest=MarketplaceEditingFlowIT,MarketplaceSubmissionFlowIT,MarketplaceDraftFlowIT,NaverProductFlowIT -Dsurefire.failIfNoSpecifiedTests=false
node --test src/test/js/*.test.cjs
node scripts/test-unified-marketplace-editor.cjs
node scripts/test-naver-product-form.cjs
```

최종 실행 JAR는 `molebutter-app/target/molebutter-0.0.1-SNAPSHOT.jar`다. 별도 `target/shipping-verification`에서 검증한 JAR를 이 경로에도 배치한다. 비밀 설정은 포함하지 않는다.

## 규격 근거와 미실행 항목

최신 `marketplace-api-docs/README.md`, 각 OVERVIEW/INDEX/KNOWN-ISSUES와 관련 SOURCE·FAQ를 확인했다. 주요 대상은 쿠팡 생성·수정·출고지·지역 추가비, 네이버 v2 상품·배송 구조체·판매기간·묶음배송 그룹, 롯데ON 87/90 상품·166 배송비 정책·묶음배송 FAQ, 11번가 1003/1619 상품과 관련 판매기간·발송 템플릿 공지다. 다른 마켓의 규격으로 배송 조건을 대신하지 않았으며 SOURCE·원문은 변경하지 않았다.

공식 온라인 문서의 쿠팡 [출고지 조회](https://developers.coupang.com/ko/api/logistics/query-a-shipping-location)와 네이버 [묶음배송 그룹 목록](https://apicenter.commerce.naver.com/docs/commerce-api/current/get-delivery-bundle-group-list-product)도 다시 확인했다. 네이버 목록 API의 판매자센터 미방문 시 기본 그룹 생성에 관한 제공자 측 동작과, 앱에서 그룹 생성 API를 호출하지 않는다는 점은 구분한다.

이번 실행에서는 실계정의 새 정책 조회, 실제 마켓 상품 등록·수정·이미지 업로드, 공유 정책 변경, 운영 DB 변경, 서버 재시작을 수행하지 않았다. 계정별 정책 내용과 권한은 재시작 후 조회로 확인해야 한다.
