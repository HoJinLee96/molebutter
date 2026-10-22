# 신규 등록 결과의 쿠팡 변환값 비교

실행일: 2026-10-21 (한국 시각). 쿠팡 API·R2 호출, 운영 데이터 쓰기·IDE 앱 중지/재기동은 하지 않았다.

## 변경 범위

CREATE 결과 확인의 `createApplied`만 보완했다. 승인·식별자·옵션 연결 확인을 통과한 뒤 판매 시작·종료 일시, 검색어 순서, 상품 이미지 vendorPath/cdnPath와 상세 IMAGE/HTML img src URL 차이를 제외한다. 요청·조회 원문과 전송 전문은 복사본 정규화로 보존한다. 기존 상품 수정의 기간 반영·충돌 검사, revision·UNKNOWN/ACCEPTED 보호는 바꾸지 않는다. 새 API·테이블·마이그레이션은 없다.

검색어는 같은 개수의 값을 일대일 대응하므로 누락·변경·중복 때문에 다른 구성이 된 경우 계속 차이로 보고한다. 상품 이미지는 역할·imageOrder별 일대일 대응과 비어 있지 않은 경로를 확인하며 장수·역할·순서·나머지 메타정보를 비교한다. 상세설명은 행/세부행 개수·타입·텍스트·HTML 속성을 보존하며 IMAGE content와 img src 값만 제외한다. 따라서 URL 이외 구분값이 없는 상세 IMAGE 행의 사진 내용·순서를 독립적으로 증명하는 검사는 아니다. 문자열 NONE·null·누락·빈값을 전역으로 동일 취급하지 않는다.

## 실제 보존 자료 재실행

사용자가 지정한 실행 `900711115416699080`의 보존 등록 전문과 상품 `16422633267`의 20:45:07 관측 스냅샷을 SELECT 전용 트랜잭션에서 읽었다. 기존 런타임 설정으로 접속하고 자격 증명·주소·연락처·토큰은 출력하지 않았다. 새 비교 함수만 메모리에서 실행해 **creationReflectionMatches=true**, **expectedPreserved=true**, **observationPreserved=true**를 확인했다. 쿠팡 재조회·재전송은 하지 않았다. 원래 감사 결과 `CREATED_VALUES_DIFFER`는 변경하지 않았다.

실제 자료에서 확인한 차이는 판매기간 두 값, 동일한 17개 검색어의 순서, 상품 이미지 6개·상세 이미지 10개의 경로 변환이다. 원문이나 상세 주소 목록을 이 문서에 복사하지 않았다.

## 자동 검증

- `CoupangWorkingCopyWriteTest` 87개 통과: 실제 승인/결과 확인 경로, 등록 응답 ID 보존, 승인 재시도에서 CREATE 반복 금지, 규격 만료·계정·revision·UNKNOWN 보호 포함.
- 변환값 조합에서 `CONFIRMED / CREATED_CONFIRMED`, 요청·관측 JSON 미변경 확인.
- 검색어 누락·변경·중복, 판매가·정상가·상품명·제조사·속성 변경, 상품 이미지 누락·역할·순서·빈 경로, 상세 행 삭제·타입·빈 URL, HTML 문구·이미지 수·alt·data-src 변경은 `CREATED_VALUES_DIFFER` 유지.
- 기존 UPDATE 기간 변경이 미반영된 경우 `ACCEPTED / REFLECTION_PENDING` 유지.
- 임시 MySQL·Redis의 `CoupangWorkingCopyFlowIT` 33개 통과, 실패·오류·생략 0개. 운영 DB와 분리해 저장·완료 의도 제거·원래 전송 증거·UNKNOWN/ACCEPTED 보호를 회귀 검사했다.

공식 문서는 2026-10-03 수집 SOURCE의 상품 생성·조회와 이미지 다운로드 실패 FAQ를 참고했다. API가 모든 경우 같은 변환을 한다고 가정한 것이 아니라 사용자가 지정한 신규 결과 비교 정책을 적용한 것이다. 화면·실계정 API 쓰기를 검증한 결과는 아니다.
