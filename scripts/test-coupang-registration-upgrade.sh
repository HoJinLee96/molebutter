#!/usr/bin/env bash
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
export PATH="/opt/homebrew/bin:$PATH"
upgrade_source_test=$(mktemp -d /tmp/molebutter-domain-command-test.XXXXXX)
upgrade_source_test_pid=''
cleanup(){ if [ -n "$upgrade_source_test_pid" ];then mysqladmin --no-defaults --socket="$upgrade_source_test/mysql.sock" -u root shutdown >/dev/null 2>&1 || kill "$upgrade_source_test_pid" 2>/dev/null || true;wait "$upgrade_source_test_pid" 2>/dev/null || true;fi;rm -rf "$upgrade_source_test"; }
trap cleanup EXIT
upgrade_source_test_port=$(python3 -c 'import socket;s=socket.socket();s.bind(("127.0.0.1",0));print(s.getsockname()[1]);s.close()')
mysqld --no-defaults --initialize-insecure --datadir="$upgrade_source_test/mysql" >"$upgrade_source_test/init.log" 2>&1
mysqld --no-defaults --datadir="$upgrade_source_test/mysql" --socket="$upgrade_source_test/mysql.sock" --port="$upgrade_source_test_port" --bind-address=127.0.0.1 --mysqlx=OFF --pid-file="$upgrade_source_test/mysql.pid" --log-error="$upgrade_source_test/mysql.log" &
upgrade_source_test_pid=$!
upgrade_source_test_ready=false
for ((attempt=0;attempt<60;attempt++));do if mysqladmin --no-defaults --socket="$upgrade_source_test/mysql.sock" -u root ping >/dev/null 2>&1;then upgrade_source_test_ready=true;break;fi;sleep 1;done
[ "$upgrade_source_test_ready" = true ] || { echo 'Isolated source database failed';exit 1; }
mysql --no-defaults --socket="$upgrade_source_test/mysql.sock" -u root -e 'CREATE DATABASE coupang_upgrade_source CHARACTER SET utf8mb4;'
mkdir "$upgrade_source_test/new-assets"
printf 'synthetic-preserved-image' > "$upgrade_source_test/new-assets/original.png"
export SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:$upgrade_source_test_port/coupang_upgrade_source?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul"
export SPRING_DATASOURCE_USERNAME=root SPRING_DATASOURCE_PASSWORD=''
export COUPANG_PREVIOUS_ASSETS_DIRECTORY="$upgrade_source_test/old-assets"
export COUPANG_ASSETS_DIRECTORY="$upgrade_source_test/new-assets"
export SERVER_PORT=1
cat > "$upgrade_source_test/application.properties" <<PROPERTIES
spring.datasource.url=$SPRING_DATASOURCE_URL
spring.datasource.username=root
spring.datasource.password=
coupang.previous-assets-directory=$COUPANG_PREVIOUS_ASSETS_DIRECTORY
coupang.assets.directory=$COUPANG_ASSETS_DIRECTORY
server.port=1
PROPERTIES
export SPRING_CONFIG_LOCATION="file:$upgrade_source_test/application.properties"
upgrade_java="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}/bin/java"
upgrade_test_jar="${MOLEBUTTER_UPGRADE_JAR:-molebutter-app/target/molebutter-0.0.1-SNAPSHOT.jar}"
upgrade_test_classes="${MOLEBUTTER_UPGRADE_TEST_CLASSES:-molebutter-app/target/test-classes}"
upgrade_fixture(){ "$upgrade_java" -Dloader.path="$upgrade_test_classes" -Dloader.main=cc.ataglace.molebutter.app.internal.maintenance.CoupangRegistrationFixture -cp "$upgrade_test_jar" org.springframework.boot.loader.launch.PropertiesLauncher "$@"; }
export COUPANG_VENDOR_ID=A-fixture
upgrade_fixture seed "$upgrade_source_test/before.txt"
bash scripts/upgrade-coupang-registration.sh --apply "$upgrade_source_test/backup"
test -f "$upgrade_source_test/backup/UPGRADE_COMPLETE"
cmp "$upgrade_source_test/new-assets/original.png" "$upgrade_source_test/backup/sales-assets/original.png"
upgrade_fixture verify
bash scripts/upgrade-coupang-registration.sh --apply "$upgrade_source_test/backup"
