#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for tool in mysqld mysql mysqladmin redis-server redis-cli python3; do
  command -v "$tool" >/dev/null || { echo "Required tool missing: $tool"; exit 1; }
done
test_dir=$(mktemp -d /tmp/molebutter-it.XXXXXX)
mysql_pid=''
redis_pid=''
cleanup() {
  if [ -n "$mysql_pid" ]; then
    mysqladmin --no-defaults --socket="$test_dir/mysql.sock" -u root shutdown >/dev/null 2>&1 || kill "$mysql_pid" 2>/dev/null || true
    wait "$mysql_pid" 2>/dev/null || true
  fi
  if [ -n "$redis_pid" ]; then kill "$redis_pid" 2>/dev/null || true; wait "$redis_pid" 2>/dev/null || true; fi
  rm -rf "$test_dir"
}
trap cleanup EXIT
free_port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()'; }
mysql_port=$(free_port)
redis_port=$(free_port)
while [ "$redis_port" = "$mysql_port" ]; do redis_port=$(free_port); done
echo 'Preparing isolated MySQL and Redis (no existing database is modified).'
mysqld --no-defaults --initialize-insecure --datadir="$test_dir/mysql" >"$test_dir/mysql-init.log" 2>&1
mysqld --no-defaults --datadir="$test_dir/mysql" --socket="$test_dir/mysql.sock" \
  --port="$mysql_port" --bind-address=127.0.0.1 --mysqlx=OFF \
  --pid-file="$test_dir/mysql.pid" --log-error="$test_dir/mysql.log" &
mysql_pid=$!
redis-server --bind 127.0.0.1 --port "$redis_port" --save '' --appendonly no \
  --dir "$test_dir" >"$test_dir/redis.log" 2>&1 &
redis_pid=$!
ready=false
for ((attempt=0; attempt<60; attempt++)); do
  if mysqladmin --no-defaults --socket="$test_dir/mysql.sock" -u root ping >/dev/null 2>&1 && \
      redis-cli -h 127.0.0.1 -p "$redis_port" ping >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
if [ "$ready" != true ]; then cat "$test_dir/mysql.log" "$test_dir/redis.log"; exit 1; fi
mysql --no-defaults --socket="$test_dir/mysql.sock" -u root <<'SQL'
CREATE DATABASE molebutter_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'test_app'@'localhost' IDENTIFIED BY 'isolated-test-app-password';
CREATE USER 'test_migrator'@'localhost' IDENTIFIED BY 'isolated-test-migration-password';
GRANT SELECT, INSERT, UPDATE, DELETE ON molebutter_test.* TO 'test_app'@'localhost';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES ON molebutter_test.* TO 'test_migrator'@'localhost';
CREATE DATABASE molebutter_upgrade_test CHARACTER SET utf8mb4;
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES ON molebutter_upgrade_test.* TO 'test_migrator'@'localhost';
SQL
export MOLEBUTTER_TEST_DB_URL="jdbc:mysql://127.0.0.1:$mysql_port/molebutter_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Seoul"
export MOLEBUTTER_TEST_REDIS_PORT="$redis_port"
./mvnw -o -Dmolebutter.build-directory=target/verification -Pintegration-tests clean test "$@"
