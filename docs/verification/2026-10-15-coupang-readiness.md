# 쿠팡 단독 개발 전 모듈·IDE 점검

검증일: 2026-10-15. 기존 쿠팡 전환 WIP를 이어서 점검했으며 커밋·푸시하지 않았다. 새 판매 초기화나 운영 DB 변경 명령은 실행하지 않았다.

## 결론과 모듈별 확인

루트 집계 POM과 실제 12개 모듈의 reactor 빌드가 모두 성공했다. 회원·근태·내부 상품·매입·보유 재고·이미지 도구를 보존해야 하므로 추가 Maven 모듈 삭제는 하지 않았다. [각 모듈의 책임과 유지 이유](../modular-architecture.md)를 따른다.

| 모듈 | Maven verify | 직접 포함된 단위 검사 수 |
|---|---|---:|
| common | 성공 | 0 |
| storage | 성공 | 50 |
| identity | 성공 | 4 |
| operations | 성공 | 0 |
| catalog | 성공 | 11 |
| procurement | 성공 | 185 |
| inventory | 성공 | 7 |
| attendance | 성공 | 0 |
| media | 성공 | 21 |
| coupang | 성공 | 201 |
| imaging | 성공 | 196 |
| app | 성공 | 137 |

합계 812개 성공. 모듈에 직접 단위 검사가 없는 업무도 app의 통합 검사에서 근태·알림·인증·저장 동작을 확인한다. ModuleArchitectureTest의 공개 계약·내부 접근·패키지 소유권·순환 의존성 검사가 통과했다. 실행 JAR에는 app 자원과 업무 라이브러리 11개가 포함된다.

## IDE의 빨간 표시

처음 VS Code에서 오류 197개·경고 445개가 표시됐지만 같은 소스의 Maven 빌드는 성공했다. 오류는 Lombok 생성자에 Java 25 기능이 필요하다는 진단이었다. 설치된 redhat.java 1.57.0의 ECJ/Lombok 호환성 문제와 일치하며 유지보수자가 1.56.0 사용을 안내한다. [공식 이슈 #4521](https://github.com/redhat-developer/vscode-java/issues/4521).

공식 배포 Java 확장을 1.56.0으로 되돌렸고 설치 메타데이터의 pinned=true와 실제 언어 서버 프로세스 경로를 확인했다. 프로젝트 Java 21은 유지한다. 로컬 `.vscode/settings.json`에 JavaSE-21 런타임을 명시했으며 진단 숨김·소스 수준 25 변경은 하지 않았다. 시도했던 별도 Lombok javaagent 설정은 제거했다. 언어 서버가 기존 진단을 다시 분석한 뒤 오류 0개·경고 305개·정보 6개와 Java: Ready를 확인했다.

남은 노란 경고는 빌드 실패와 다르다. 확인한 항목은 테스트의 미사용 import, Jackson JsonNode.asText()의 폐기 예정 안내, 보존하는 과거 마이그레이션의 중복 import 등이다. 경고 0개를 달성한 것은 아니다. 과거 마이그레이션의 경고를 없애려고 파일을 수정하지 않았다.

## 제거와 수정

- 현재 라우트에서 사용하지 않는 이전 전용 편집 HTML 1개와 JS 3개를 제거했다. 공유 coupang-editor-model.js와 상품 폼·브랜드 선택 도구는 통합 화면에서 계속 사용한다.
- 이전 화면 전용 JS 테스트 3개와 브라우저 스크립트 2개를 제거하고, 혼합 모델 검사 파일에서는 사라진 화면을 실행하는 6개 검사와 전용 harness를 제거했다. 공유 모델 계약 검사는 유지한다. 현재 통합 편집·주문·전송 화면의 PC/모바일 회귀 검사를 실행했다. 이전 편집 자원이 남은 생성 출력 5개도 제거했다.
- Java·JS에 남은 항상 참/거짓 분기, 단일 쿠팡을 배열로 감싼 선택 코드, 미사용 지역 변수·검증 도우미·마켓 인자를 정리했다.
- 유효 판매 수량의 오류 경로를 markets.COUPANG...에서 configuration.overrides.options...로 수정했다. 100000 수량의 오류가 현재 JSON 경로와 최대 99999 안내를 반환하는 회귀 검사를 추가했다.
- 주 소스 110개 파일에서 중복·미사용 import 258개를 정리했다. 쿠팡 컨트롤러 3개의 폐기 예정 UNPROCESSABLE_ENTITY를 같은 HTTP 422인 UNPROCESSABLE_CONTENT로 변경했다.
- AGENTS.md의 현재 전환 안내를 V46 데이터 보존 절차로 맞추고 모듈·테스트 문서를 갱신했다. 쿠팡 독립 등록·저장 API는 현재 공개 계약이므로 유지했다. 외부 쓰기 보호·옵션 UUID·계정·revision·전송 복구 기능은 제거하지 않았다.

## 실행한 검증

- Java 21, Maven 전체 verify: 812개 성공, 실패·오류 0.
- 현재 JavaScript: 218개 성공, 실패 0. 삭제한 이전 화면 전용 검사는 현재 통합 화면의 검사 수와 구분한다.
- 전체 격리 MySQL·Redis 통합: 295개 중 290개 성공·실계정 조건 5개 생략, 실패·오류 0. 신규 DB, V44→V45와 데이터가 있는 V45→V46 전환, 참조·증거·잠금·실패 차단을 포함한다.
- 최종 JAR을 재생성한 뒤 V45→V46 데이터 보존 검사 1개와 packaged smoke를 별도 격리 DB에서 다시 통과했다.
- 1440px PC·390px 모바일: CREATE/UPDATE 편집·입력 Enter 쓰기 금지·명시적 버튼 실행·임시 저장·재진입·실패 후 입력 보존·단일 target 요청 통과. 주문 상세·수집 취소/재개와 전송 중복 방지·UNKNOWN 차단·실패 복구 통과.
- 과거 마이그레이션·역사적 스키마 파일 47개와 쿠팡 공식 자료 1791개는 이전 해시와 바이트가 동일하다. Git diff 공백 검사 통과.

검증 로그와 제거 import 목록은 `output/coupang-readiness/`에 있다. 전체 통합 결과는 integration.log, 마지막 1개 검사와 최종 JAR 기동은 package-smoke.log로 구분한다. 테스트는 모의 API와 임시 DB를 사용했고 실제 쿠팡 쓰기·공용 R2 삭제는 실행하지 않았다.

## 로컬 실행 반영과 복구

기존 실행 JAR과 소스는 `backups/coupang-readiness-before-20261015/`에 보관했다. 검증된 JAR을 기존 실행 경로로 반영하고 8081 포트의 앱을 재기동했다. 기동 로그에서 기존 DB 버전 46과 추가 마이그레이션 없음, 정상 Started 상태를 확인했다. 로그인·통합 편집 JS는 200, 제거한 JS 3개는 404다. 재기동은 기존 런타임 설정을 사용했으며 비밀 파일을 열거나 수정하지 않았다.

실행 JAR SHA-256: `a346701ca2e7887cd70eb6716be375f01a7fb98b9b4d7d1a16165c15b5330a3a`. 실제 실행 경로와 검증 JAR의 해시가 같다. 기동 로그는 `molebutter-app/logs/coupang-readiness-boot.log`, HTTP·해시 결과는 `output/coupang-readiness/runtime.json`이다.
