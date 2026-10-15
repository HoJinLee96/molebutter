# WCWA705R2 실검증과 외부 삭제 후 재등록 — 2026-10-14

## 구현

통합 편집기의 최초 탭 조회·최신값 재조회·전송 전 조회에서 같은 삭제 판정을 사용한다. 자동 주기 조회와 ESM 연동은 추가하지 않았다.

| 마켓 | 삭제 확정 근거 | 그 밖의 조회 실패 |
|---|---|---|
| 쿠팡 | 요청 상품 ID·판매자 계정을 검증한 정상 상세 응답의 `상품삭제` 또는 `DELETED` | 권한·통신·호출 제한과 일반 404는 조회 미확인 |
| 스마트스토어 | 기존 원상품 또는 연결 채널 GET의 `404 NOT_FOUND`가 두 번 확인됨 | 일반 404, 다른 업무 코드, 인증·권한 오류는 조회 미확인 |
| 롯데ON·11번가 | 현재 대조한 공식 계약에서는 안전하게 확정할 근거가 부족함 | 판매종료·판매중지·빈 목록·조회 실패를 삭제로 추정하지 않음; 자동 재등록 전환 미지원 |

편집 응답의 `listing`은 상태·사유·확인 시각·연결 세대번호·재등록 가능 여부를 제공한다. 삭제 확정은 `DELETED_CONFIRMED`, 탭은 `DELETED`로 표시하고 기존 입력을 보존한다. 수정 전송은 차단하며 **최신값 다시 조회**, **새로 등록 준비**를 제공한다. 안내를 닫거나 탭을 이동하는 것만으로 연결은 바뀌지 않는다.

`POST /api/marketplaces/editor/recreate`는 인증 사용자, 현재 세션·revision·마켓·연결 세대·중복 방지 UUID·현재 공통 입력·방문한 마켓의 변경을 받는다. 외부에서 삭제를 다시 확인한 뒤 짧은 DB 트랜잭션으로 입력을 저장하고 이전 연결을 이력에 보존한다. 실행 중·결과 미확인·접수 후 확인 대기인 요청이 있으면 전환하지 않는다.

대상 마켓만 CREATE로 바꾸며 마지막 신뢰 가능한 마켓 조회값과 보관된/현재 변경을 재사용한다. 공통 기준·옵션 UUID·다른 마켓의 연결과 변경은 유지한다. 과거 외부 상품·채널·옵션 ID, 쿠팡 원본 조회·승인 전용 값은 새 등록 입력에서 제거한다. 재등록용 마켓 입력은 공통 기준과 별도로 보관하여 이후 공통값 변경으로 덮어쓰지 않는다. 마지막 입력이 없으면 임의로 문서를 만들지 않고 안내한다.

V42는 연결 상태·세대, 연결/입력/변경 이력, 이력 이미지 참조 테이블을 추가한다. 기존 연결·초안·실행 문서는 변경하지 않으며 세대 필드가 없는 기존 JSON은 1세대로 읽는다. 과거 가져오기 정보로 이전 연결을 자동 복원하지 않는다. 단독 수정용 내부 초안은 재등록 준비만으로 사용자 공통 상품이 되지 않는다.

이전 세션·준비된 실행·늦은 실행 결과는 연결 세대를 확인한다. 새 세대와 다르면 실행·연결 갱신을 차단한다. 참조 이미지와 의존 원본은 이력에 고정하여 미참조 파일 정리에서 보호한다. 같은 요청의 재시도는 입력 해시까지 확인하며 다른 마켓·입력에 같은 UUID를 재사용할 수 없다.

## WCWA705R2 실제 앱 확인

사용자가 서버 재시작·강력 새로고침 후 준비한 로그인 Chrome 화면을 직접 조작했다. 대상은 공통 상품 `897779631605467192`, 상품코드 `WCWA705R2`다.

| 항목 | 실제 확인 결과 |
|---|---|
| 공통 기준 | 정상가 128000, 판매가 87000, 수량 50, 기존 옵션·이미지·브랜드·카테고리 보존 |
| 새 공통 필드 | 배송 방식·택배사·배송비·반품비·출고 소요일·지역 추가비·판매기간·A/S 책임자·연락처·안내가 미입력 상태. 자동으로 채우지 않음 |
| 쿠팡 연결 | 상품 16414267189, 옵션 96185881235의 실제 응답을 표시. 무료배송, CJ대한통운, 출고 2일, 초도/회수 각 4000원, 기존 주소 확인 |
| 쿠팡 문제 발견 | 등록 전 임시 변경을 등록 후 연결과 충돌로 판정하여 FAILED 상태로 잠김. 현재 판매기간과 과거 임시 변경의 차이도 표시됨 |
| 스마트스토어 연결 | 원상품 13747403202의 최신 응답 조회 성공. 판매가 128000·즉시 할인 41000·옵션 추가금 0·재고 50 확인 |
| 스마트스토어 배송·A/S | 무료배송, CJ대한통운, 묶음배송 아니요, 반품/교환 각 4000원, 기존 출고/반품 주소, A/S 전화·안내 유지 |
| 입력 보존 | 스마트스토어 A/S 안내를 시험 입력한 후 공통 탭 왕복 시 유지. 공통 A/S 책임자를 시험 입력해도 기존 스마트스토어 개별 안내를 덮어쓰지 않음 |
| 내부 저장·재진입 | 모든 시험값을 원래 값으로 복원한 뒤, 만료 세션을 갱신하고 실제 `임시 저장 완료` 안내 확인. 새로고침, 목록 이동, 동일 상품 재진입, 스마트스토어 탭 최초 조회 후 원래 값 유지 확인 |
| 미연결 마켓 | 롯데ON·11번가는 이 상품의 연결 ID·주소/정책이 없으므로 실상품 적용 결과를 확인하지 않음 |

기존 초안의 새 공통 필드가 비어 있어 네 마켓에 새 공통 배송·판매기간·A/S 값이 실제 적용되었다고 판단하지 않는다. 명시적 공통 선택 적용·개별값 우선순위와 네 마켓 매핑은 기존 자동 회귀와 모의 화면에서 확인하며, 이번 실제 화면에서는 기존값 보존과 내부 재진입을 확인했다. 실제 마켓 상품 저장·전송 준비·이미지 업로드·상품 삭제·정책 변경은 실행하지 않았다.

쿠팡 호환 오류는 이미 CREATE로 전송한 임시 변경의 revision과 다른 마켓 설정이 이후 공통 저장·구현 보완으로 달라지면서 발생했다. 실제 DB를 읽기 전용으로 비교한 결과 등록 실행·변경 목록은 같고, 기준 문서의 차이는 롯데ON·11번가 설정뿐이었다. 수정된 비교 함수를 실제 스냅샷에 적용하여 일치 판정도 확인했다. 실제 CREATE 실행·상품 ID·계정·연결 세대·변경 내용과 해당 마켓의 기준값이 일치할 때만 등록 전 변경을 정리한다. 비교에서 초안 revision·전송 대상 선택·다른 마켓 설정은 제외하며 공통값·해당 마켓 입력의 다른 값은 무시하지 않는다. 임의 연결이나 다른 입력에는 적용하지 않는다.

등록은 성공하고 승인만 실패한 경우도 등록 단계의 증거로 판정한다. 승인 대기 중 변경이 없는 방문 탭 때문에 공통 기준 저장이 막히지 않도록 보완하되 개별 변경의 기존 잠금은 유지한다. 전송 결과 자체를 성공으로 바꾸거나 요청값을 재전송하지 않는다. 쿠팡 READY 복원과 공통 저장은 격리 회귀에서 확인했으며, 변경된 최종 백엔드의 실계정 쿠팡 재진입은 다음 서버 재시작 후 확인해야 한다.

## 실행한 자동 검증

| 검사 | 결과 |
|---|---|
| 관련 Java 단위·어댑터·JSON 호환·모듈 경계·뷰 | 253개 통과, 실패·오류·스킵 0 |
| 전체 JS | 320개 통과 |
| 격리 MySQL·Redis 통합 | 112개 통과: 편집 49, 전송 35, 공통 초안 8, 네이버 18, 업그레이드 2; 실패·오류·스킵 0 |
| 실제 템플릿·제어 코드의 모의 Chrome | 1440px·390px 통과; 삭제 안내·전환·개별 입력·저장·새로고침 검사 |
| 실행 JAR | 별도 빌드 디렉터리에서 패키징; 표준 실행 경로에 최종본 배치 |

통합 검사는 쿠팡/네이버 삭제 확정·원상품/채널 구분·일반 404/권한 오류, 재등록 시 외부 ID 제거와 공통 UUID 유지, 다른 마켓·보관된 변경 보존, 단독 내부 초안 유지, 중복 요청·동시 전환·실패 롤백·오래된 세션·실행 차단, 새 등록 성공 후 새 연결과 이전 이력 보존을 포함한다. 이력 이미지는 현재 초안 참조를 제거하고 정리 유예 시간을 넘긴 뒤에도 유지되는지 검사한다. V39→V42 업그레이드에서 기존 문서·revision·연결·실행을 보존하고 V42 재실행이 무변경인지 확인한다.

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./mvnw -o -Dmolebutter.build-directory=target/recreation-verification -pl molebutter-app -am -Dtest=CoupangClientTest,CoupangWriteGatewayTest,NaverGatewayTest,NaverProductDocumentsTest,NaverWriteGatewayTest,MarketplaceProductEditorTest,MarketplaceTargetEditingTest,SubmissionExecutionTest,MarketplaceSnapshotCompatibilityTest,RecreationDocumentsTest,SubmissionIntentCompatibilityTest,LayoutViewTest,ModuleArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false package
MOLEBUTTER_COUPANG_LIVE=false MOLEBUTTER_ORDER_LIVE=false CLOUDFLARE_R2_ENABLED=false bash scripts/test-integration.sh -pl molebutter-app -am -Dtest=MarketplaceEditingFlowIT,MarketplaceSubmissionFlowIT,MarketplaceDraftFlowIT,NaverProductFlowIT,MigrationUpgradeIT -Dsurefire.failIfNoSpecifiedTests=false
node --test src/test/js/*.test.cjs
node scripts/test-unified-marketplace-editor.cjs
```

기본 `target/classes`는 IDE가 갱신하므로 Java 검증은 별도 디렉터리에서 실행했다. 비밀 파일은 열거나 변경하지 않았으며 최종 JAR에 포함하지 않는다. 서버는 사용자가 재시작했고 에이전트는 재시작하지 않았다. 이번 최종 Java 변경과 V42는 최종 JAR로 다시 시작해야 모두 반영된다.

## 규격 근거

프로젝트 최신 README→OVERVIEW/INDEX→대상 SOURCE·FAQ·KNOWN-ISSUES 순서로 대조했다. 쿠팡 [상품 조회](https://developers.coupang.com/ko/api/products/querying-product), 네이버 [원상품 조회](https://apicenter.commerce.naver.com/docs/commerce-api/current/read-origin-product-product)와 연결 채널 조회 계약을 사용한다. 롯데ON 상품 상세 94의 일반 404와 판매종료 코드, 11번가 상품 조회 1620과 인증/판매자 오류 FAQ를 삭제 확정 근거로 확대하지 않는다. 제공된 SOURCE·원문은 변경하지 않았다.
