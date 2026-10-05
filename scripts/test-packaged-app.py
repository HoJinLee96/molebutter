#!/usr/bin/env python3
"""Validate the packaged artifact and boot it against test-integration.sh's isolated services."""
import io
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile

jar = Path(sys.argv[1]).resolve()
db_url = os.environ['MOLEBUTTER_TEST_DB_URL']
if not db_url.startswith('jdbc:mysql://127.0.0.1:') or '/molebutter_test?' not in db_url:
    raise SystemExit('Packaged smoke requires the isolated integration database')

with zipfile.ZipFile(jar) as artifact:
    names = artifact.namelist()
    assert 'BOOT-INF/classes/db/migration/V29__separate_catalog_procurement.class' in names
    assert 'BOOT-INF/classes/db/migration/V30__remove_shared_product_columns.class' in names
    assert 'BOOT-INF/classes/templates/products.html' in names
    assert 'BOOT-INF/classes/static/js/inventory.js' in names
    forbidden = ('application-secret/', 'bootstrap-admin-test.properties', 'test-fixtures/', '/fixture/', '/test-classes/')
    assert not any(any(part in name for part in forbidden) or name.endswith('-tests.jar') for name in names)
    module_jars = [name for name in names if name.startswith('BOOT-INF/lib/molebutter-')]
    assert len(module_jars) == 7, module_jars
    for name in module_jars:
        with zipfile.ZipFile(io.BytesIO(artifact.read(name))) as module:
            assert not any(any(part in path for part in forbidden) for path in module.namelist())
    assert not any('/archunit-' in name for name in names)

with socket.socket() as probe:
    probe.bind(('127.0.0.1', 0))
    port = probe.getsockname()[1]
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
args = [java, '-jar', str(jar), f'--server.port={port}', '--server.address=127.0.0.1',
        '--spring.config.import=', '--spring.profiles.active=',
        f'--spring.datasource.url={db_url}', '--spring.datasource.username=test_app',
        '--spring.datasource.password=isolated-test-app-password',
        '--spring.flyway.user=test_migrator', '--spring.flyway.password=isolated-test-migration-password',
        '--spring.data.redis.host=127.0.0.1', f"--spring.data.redis.port={os.environ['MOLEBUTTER_TEST_REDIS_PORT']}",
        '--spring.data.redis.password=', '--product.refresh.worker-enabled=false',
        '--product.changes.initialize-enabled=false',
        '--auth.jwt.secret=isolated-packaged-test-secret-at-least-32-bytes',
        '--resend.api-key=isolated-unused-test-key', '--resend.from=fixture@example.test']
with tempfile.TemporaryDirectory(prefix='molebutter-jar.') as working:
    log_path = Path(working) / 'boot.log'
    with log_path.open('w') as log:
        process = subprocess.Popen(args, cwd=working, stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 60
            while True:
                if process.poll() is not None:
                    raise AssertionError('Packaged app failed to boot:\n' + log_path.read_text()[-12000:])
                try:
                    with urllib.request.urlopen(f'http://127.0.0.1:{port}/signin', timeout=2) as response:
                        assert response.status == 200 and b'/js/' in response.read()
                    break
                except (OSError, urllib.error.URLError):
                    if time.monotonic() > deadline:
                        raise AssertionError('Packaged startup timed out:\n' + log_path.read_text()[-12000:])
                    time.sleep(0.2)
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/api/auth/csrf', timeout=3) as response:
                data = json.load(response)
                assert response.status == 200 and data['data']['token'] and data['data']['headerName']
            # Explicit JDBC maintenance preview uses the same packaged module graph without booting workers.
            settings = Path(working) / 'isolated.properties'
            settings.write_text(f'spring.datasource.url={db_url}\nspring.datasource.username=test_app\nspring.datasource.password=isolated-test-app-password\n')
            defaults = Path(working) / 'application.properties'
            defaults.write_text('')
            for command in ('ProductStatusRepairCommand', 'SearchStatusRepairCommand'):
                command_args = ['preview']
                if command == 'ProductStatusRepairCommand':
                    command_args.append(str(Path(working) / 'manifest.json'))
                command_args.extend([str(defaults), str(settings)])
                result = subprocess.run([java, f'-Dloader.main=cc.ataglace.molebutter.procurement.internal.{command}',
                                         '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher',
                                         *command_args], cwd=working, stdout=log, stderr=subprocess.STDOUT, timeout=20)
                if result.returncode:
                    log.flush()
                    raise AssertionError(f'{command} failed:\n' + log_path.read_text()[-12000:])
            print('PASS packaged JAR: seven business modules, migrations/resources, no secrets/test data, HTTP boot and repair previews')
        finally:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
