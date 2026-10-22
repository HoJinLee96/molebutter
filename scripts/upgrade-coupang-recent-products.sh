#!/usr/bin/env bash
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
export PATH="/opt/homebrew/bin:$PATH"
if [ "$#" != 2 ] || [ "$1" != '--apply' ]; then echo 'Usage: scripts/upgrade-coupang-recent-products.sh --apply <new-backup-directory>'; exit 2; fi
for tool in mysqld mysql mysqldump mysqladmin python3; do command -v "$tool" >/dev/null || { echo "Required tool missing: $tool"; exit 1; }; done
upgrade_temp=$(mktemp -d /tmp/molebutter-upgrade.XXXXXX)
upgrade_pid=''
cleanup(){ if [ -n "$upgrade_pid" ]; then mysqladmin --no-defaults --socket="$upgrade_temp/mysql.sock" -u root shutdown >/dev/null 2>&1 || kill "$upgrade_pid" 2>/dev/null || true; wait "$upgrade_pid" 2>/dev/null || true; fi; rm -rf "$upgrade_temp"; }
trap cleanup EXIT
upgrade_port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
mysqld --no-defaults --initialize-insecure --datadir="$upgrade_temp/mysql" >"$upgrade_temp/init.log" 2>&1
mysqld --no-defaults --datadir="$upgrade_temp/mysql" --socket="$upgrade_temp/mysql.sock" --port="$upgrade_port" --bind-address=127.0.0.1 --mysqlx=OFF --pid-file="$upgrade_temp/mysql.pid" --log-error="$upgrade_temp/mysql.log" &
upgrade_pid=$!
upgrade_ready=false
for ((attempt=0;attempt<60;attempt++)); do if mysqladmin --no-defaults --socket="$upgrade_temp/mysql.sock" -u root ping >/dev/null 2>&1; then upgrade_ready=true; break; fi; sleep 1; done
[ "$upgrade_ready" = true ] || { echo 'Isolated restore database could not start'; exit 1; }
mysql --no-defaults --socket="$upgrade_temp/mysql.sock" -u root -e 'CREATE DATABASE coupang_upgrade_verify CHARACTER SET utf8mb4;'
export MOLEBUTTER_UPGRADE_VERIFY_PORT="$upgrade_port"
export MOLEBUTTER_UPGRADE_VERIFY_DB_URL="jdbc:mysql://127.0.0.1:$upgrade_port/coupang_upgrade_verify?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul"
upgrade_java="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}/bin/java"
"$upgrade_java" -Dloader.path=molebutter-app/src/main/resources -Dloader.main=cc.ataglace.molebutter.app.internal.maintenance.CoupangRecentProductsMaintenance -cp "${MOLEBUTTER_UPGRADE_JAR:-molebutter-app/target/molebutter-0.0.1-SNAPSHOT.jar}" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
