# 쿠팡 업무 통합: V46 데이터 보존 전환

> V46의 역사적 전환 절차다. 현재 작업본 전환은 [V47 절차](coupang-working-copy-migration.md)를 따른다. 아래 이전 drafts 주소와 기준값 편집은 현재 앱에서 제공하지 않는다.

V46은 판매 데이터를 초기화하지 않는다. 상품·주문·편집·이미지·전송 이력과 식별자·공개 토큰·요청 해시·중복 방지 키를 보존한다. 32개 `marketplace_*` 테이블을 `coupang_*`로 바꾸고 FK·인덱스·CHECK 이름과 편집 세션의 단일 binding을 전환한다. V1~V45와 V45의 역사적 SalesSchema는 변경하지 않는다.

## 적용

1. 앱과 워커를 정지하고 Java 21로 새 JAR을 빌드한다.
2. 기존 파일의 실제 절대 경로와 새 경로를 지정한다. 기존 이미지를 삭제하거나 R2를 정리하지 않는다.
3. 아래 명령을 실행한다. 새 비공개 백업 디렉터리를 사용한다.
4. 명령이 전체 DB를 덤프하고 별도 MySQL에 복원해 모든 테이블 지문을 대조한다. 판매 파일을 백업하고 바이트 지문을 확인한다.
5. V46을 적용하고 테이블명·binding 형식 외 모든 행·값을 다시 대조한다. 파일 디렉터리를 새 위치로 이동하고 바이트를 대조한다.
6. `UPGRADE_COMPLETE`를 확인한 뒤 새 앱을 재기동한다. 쿠팡 재조회·가져오기는 필요할 때 명시적으로 실행한다.

```sh
export JAVA_HOME=/path/to/jdk21
export COUPANG_PREVIOUS_ASSETS_DIRECTORY=/absolute/old/data/marketplace-assets
export COUPANG_ASSETS_DIRECTORY=/absolute/new/data/coupang-assets
bash scripts/upgrade-coupang-domain.sh --apply backups/coupang-domain-YYYYMMDD-HHMMSS
```

기존 실행 설정은 Spring이 읽는다. 비밀 파일을 직접 열거나 수정하지 않는다. `MOLEBUTTER_UPGRADE_JAR`로 검증용 JAR을 지정할 수 있다. 실행 중인 작업, 타 마켓 값, 불완전한 V45 스키마, 기존 새 이미지 디렉터리, 백업·복원 불일치가 있으면 중단한다. 이미 전환된 DB에 재실행하면 데이터 변경 없이 거절한다. Redis 전체 초기화는 사용하지 않는다.

앱 기동의 Flyway만 실행해도 DB 전환은 가능하지만 파일 백업·이동까지 수행하지 않는다. 실제 적용에는 위 유지보수 명령을 사용한다. 앱·워커 정지는 명령의 포트 확인만으로 대체하지 않는다.

## 새 주소

| 업무 | 주소 |
|---|---|
| 쿠팡 목록 | `/coupang` |
| 내부 초안 | `/coupang/drafts/...` |
| 등록상품 | `/coupang/products/...` |
| 주문 | `/coupang/orders` |
| API | `/api/coupang/...` |
| 공개 이미지 | `/coupang/images/{token}` |

이전 앱 주소의 별칭은 없다. 브라우저 저장 키는 V46 이행 스크립트가 값 확인 후 새 이름으로 옮긴다. 키 충돌 시 양쪽 입력을 보존하고 완료 표시를 남기지 않는다. 과거 전송 증거는 원본 JSON을 다시 저장하지 않고 쿠팡 전용 판독기로 읽는다. 신규 전송은 schemaVersion 2와 단일 target을 저장한다.

## 복구

앱을 정지한 채 백업의 `database.sql`을 전체 복원하고 `sales-assets`를 `asset-locations.txt`에 기록한 이전 경로로 복사한다. 전환 후 새 이미지 디렉터리가 남아 있다면 별도로 보존한 뒤 이전 앱과 V45 DB를 함께 사용한다. MySQL DDL은 전체 트랜잭션으로 되돌아가지 않으므로 중간 실패 때 새 앱을 켜거나 Flyway repair로 건너뛰지 않는다. 검증된 백업을 사용해 복구한다.

백업에는 개인정보와 내부 자료가 포함되므로 비공개로 보관한다. 쿠팡 공식 원문, 공용 R2, 매입용 브라우저 프로필과 회원·권한·근태·내부 상품·매입·보유 재고는 전환 과정에서 삭제하지 않는다.
