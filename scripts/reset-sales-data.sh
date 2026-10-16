#!/usr/bin/env bash
# Historical V45-only reset. V47+ audit references intentionally block resets; use the current preservation migration.
set -euo pipefail
umask 077
cd "$(dirname "$0")/.."
export PATH="/opt/homebrew/bin:$PATH"
if [ "$#" != 2 ] || [ "$1" != '--apply' ]; then echo 'Usage: scripts/reset-sales-data.sh --apply <new-backup-directory>'; exit 2; fi
for tool in mysqld mysql mysqldump mysqladmin python3; do command -v "$tool" >/dev/null || { echo "Required tool missing: $tool"; exit 1; }; done
reset_temp=$(mktemp -d /tmp/molebutter-reset.XXXXXX)
reset_pid=''
cleanup(){ if [ -n "$reset_pid" ]; then mysqladmin --no-defaults --socket="$reset_temp/mysql.sock" -u root shutdown >/dev/null 2>&1 || kill "$reset_pid" 2>/dev/null || true; wait "$reset_pid" 2>/dev/null || true; fi; rm -rf "$reset_temp"; }
trap cleanup EXIT
reset_port=$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()')
mysqld --no-defaults --initialize-insecure --datadir="$reset_temp/mysql" >"$reset_temp/init.log" 2>&1
mysqld --no-defaults --datadir="$reset_temp/mysql" --socket="$reset_temp/mysql.sock" --port="$reset_port" --bind-address=127.0.0.1 --mysqlx=OFF --pid-file="$reset_temp/mysql.pid" --log-error="$reset_temp/mysql.log" &
reset_pid=$!
reset_ready=false
for ((attempt=0;attempt<60;attempt++)); do if mysqladmin --no-defaults --socket="$reset_temp/mysql.sock" -u root ping >/dev/null 2>&1; then reset_ready=true; break; fi; sleep 1; done
[ "$reset_ready" = true ] || { echo 'Isolated restore database could not start'; exit 1; }
mysql --no-defaults --socket="$reset_temp/mysql.sock" -u root -e 'CREATE DATABASE sales_reset_verify CHARACTER SET utf8mb4;'
export MOLEBUTTER_RESET_VERIFY_PORT="$reset_port"
export MOLEBUTTER_RESET_VERIFY_DB_URL="jdbc:mysql://127.0.0.1:$reset_port/sales_reset_verify?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul"
reset_java="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}/bin/java"
"$reset_java" -Dloader.path=molebutter-app/src/main/resources -Dloader.main=cc.ataglace.molebutter.app.internal.maintenance.SalesDataMaintenance -cp "${MOLEBUTTER_RESET_JAR:-molebutter-app/target/molebutter-0.0.1-SNAPSHOT.jar}" org.springframework.boot.loader.launch.PropertiesLauncher "$@"
