#!/usr/bin/env bash
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
export PATH="/opt/homebrew/bin:$PATH"
reset_test=$(mktemp -d /tmp/molebutter-reset-test.XXXXXX)
reset_test_pid=''
cleanup(){ if [ -n "$reset_test_pid" ];then mysqladmin --no-defaults --socket="$reset_test/mysql.sock" -u root shutdown >/dev/null 2>&1 || kill "$reset_test_pid" 2>/dev/null || true;wait "$reset_test_pid" 2>/dev/null || true;fi;rm -rf "$reset_test"; }
trap cleanup EXIT
reset_test_port=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')
mysqld --no-defaults --initialize-insecure --datadir="$reset_test/mysql" >"$reset_test/init.log" 2>&1
mysqld --no-defaults --datadir="$reset_test/mysql" --socket="$reset_test/mysql.sock" --port="$reset_test_port" --bind-address=127.0.0.1 --mysqlx=OFF --pid-file="$reset_test/mysql.pid" --log-error="$reset_test/mysql.log" &
reset_test_pid=$!
reset_test_ready=false
for ((attempt=0;attempt<60;attempt++));do if mysqladmin --no-defaults --socket="$reset_test/mysql.sock" -u root ping >/dev/null 2>&1;then reset_test_ready=true;break;fi;sleep 1;done
[ "$reset_test_ready" = true ] || { echo 'Isolated source database failed';exit 1; }
mysql --no-defaults --socket="$reset_test/mysql.sock" -u root <<'SQL'
CREATE DATABASE sales_reset_source CHARACTER SET utf8mb4;
USE sales_reset_source;
CREATE TABLE user(id BIGINT PRIMARY KEY,name VARCHAR(100));
CREATE TABLE inventory_item(id BIGINT PRIMARY KEY,on_hand BIGINT);
INSERT INTO user VALUES(1,'preserved-user');
INSERT INTO inventory_item VALUES(1,7);
CREATE TABLE coupang_product_working_copy(id BIGINT PRIMARY KEY);
CREATE TABLE coupang_submission_preview(id BIGINT PRIMARY KEY,working_copy_id BIGINT,FOREIGN KEY(working_copy_id) REFERENCES coupang_product_working_copy(id));
CREATE TABLE coupang_execution(id BIGINT PRIMARY KEY,preview_id BIGINT,FOREIGN KEY(preview_id) REFERENCES coupang_submission_preview(id));
CREATE TABLE coupang_execution_step(id BIGINT PRIMARY KEY,execution_id BIGINT,FOREIGN KEY(execution_id) REFERENCES coupang_execution(id));
CREATE TABLE coupang_asset(id BIGINT PRIMARY KEY,relative_path VARCHAR(100));
CREATE TABLE coupang_image_edit(id BIGINT PRIMARY KEY,asset_id BIGINT,FOREIGN KEY(asset_id) REFERENCES coupang_asset(id));
INSERT INTO coupang_product_working_copy VALUES(1);
INSERT INTO coupang_submission_preview VALUES(1,1);
INSERT INTO coupang_execution VALUES(1,1);
INSERT INTO coupang_execution_step VALUES(1,1);
INSERT INTO coupang_asset VALUES(1,'fixture.png');
INSERT INTO coupang_image_edit VALUES(1,1);
SQL
mkdir "$reset_test/assets"
printf 'synthetic-image-bytes' > "$reset_test/assets/fixture.png"
cat > "$reset_test/application.properties" <<PROPERTIES
spring.datasource.url=jdbc:mysql://127.0.0.1:$reset_test_port/sales_reset_source?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul
spring.datasource.username=root
spring.datasource.password=
coupang.assets.directory=$reset_test/assets
server.port=1
PROPERTIES
export SPRING_CONFIG_LOCATION="file:$reset_test/application.properties"
bash scripts/reset-sales-data.sh --apply "$reset_test/backup-first"
test -f "$reset_test/backup-first/RESET_COMPLETE"
test -f "$reset_test/backup-first/coupang-assets/fixture.png"
test ! -d "$reset_test/assets"
reset_test_values=$(mysql --no-defaults --socket="$reset_test/mysql.sock" -u root -N -B sales_reset_source -e 'SELECT name FROM user;SELECT on_hand FROM inventory_item;SELECT COUNT(*) FROM coupang_product_working_copy;SELECT COUNT(*) FROM coupang_execution_step;')
test "$reset_test_values" = $'preserved-user\n7\n0\n0'
bash scripts/reset-sales-data.sh --apply "$reset_test/backup-second"
test -f "$reset_test/backup-second/RESET_COMPLETE"
"${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}/bin/java" -Dloader.path=molebutter-app/src/main/resources -Dloader.main=cc.ataglace.molebutter.app.internal.maintenance.SalesDataMaintenance -cp "${MOLEBUTTER_RESET_JAR:-molebutter-app/target/molebutter-0.0.1-SNAPSHOT.jar}" org.springframework.boot.loader.launch.PropertiesLauncher --audit "$reset_test/backup-first"
echo 'PASS maintenance command: backup, isolated restore, FK cleanup, preserved user/inventory, image backup, repeated empty reset'

mysql --no-defaults --socket="$reset_test/mysql.sock" -u root sales_reset_source <<'SQL'
INSERT INTO coupang_product_working_copy VALUES(2);
INSERT INTO coupang_asset VALUES(2,'retained.png');
CREATE TABLE legacy_coupang_asset_pin(asset_id BIGINT PRIMARY KEY,FOREIGN KEY(asset_id) REFERENCES coupang_asset(id));
INSERT INTO legacy_coupang_asset_pin VALUES(2);
SQL
mkdir "$reset_test/assets"
printf 'retained-audit-image' > "$reset_test/assets/retained.png"
if bash scripts/reset-sales-data.sh --apply "$reset_test/backup-guard"; then echo 'ERROR: audit references must block reset';exit 1;fi
test -f "$reset_test/assets/retained.png"
test "$(mysql --no-defaults --socket="$reset_test/mysql.sock" -u root -N -B sales_reset_source -e 'SELECT COUNT(*) FROM coupang_asset WHERE id=2')" = '1'
echo 'PASS legacy audit references block reset and protect local image files'
