package cc.ataglace.molebutter.app.internal.maintenance;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Isolated maintenance entrypoint. Loads settings through Spring; creates no
 * application workers.
 */
public final class SalesDataMaintenance {
    @Configuration(proxyBeanMethods = false)
    static class Settings {
    }
    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception failure) {
            System.err.println("판매 데이터 초기화 중단: " + safeReason(failure));
            System.exit(1);
        }
    }
    private static String safeReason(Exception e) {
        if (e instanceof IllegalStateException)
            return e.getMessage();
        return "백업·복원 검증 또는 DB/파일 작업 실패. 판매 데이터와 백업 상태를 확인하세요 (" + e.getClass().getSimpleName() + ").";
    }
    private static void run(String[] args) throws Exception {
        boolean inspecting = args.length == 1 && args[0].equals("--status"),
                migrating = args.length == 1 && args[0].equals("--migrate"),
                auditing = args.length == 2 && args[0].equals("--audit");
        if (!inspecting && !migrating && !auditing && (args.length != 2 || !args[0].equals("--apply")))
            throw new IllegalStateException("사용법: --apply <새 백업 디렉터리> | --migrate | --audit <백업 디렉터리> | --status");
        var app = new SpringApplication(Settings.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        app.setDefaultProperties(Map.of("logging.level.root", "ERROR"));
        try (var context = app.run()) {
            Environment env = context.getEnvironment();
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", env.getProperty("server.port", Integer.class, 8081)),
                        300);
                throw new IllegalStateException("앱을 중지한 뒤 초기화하세요.");
            } catch (ConnectException ignored) {
            }
            String url = env.getRequiredProperty("spring.datasource.url"),
                    user = env.getRequiredProperty("spring.datasource.username"),
                    password = env.getRequiredProperty("spring.datasource.password");
            if (migrating) {
                migrate(env.getProperty("spring.flyway.url", url), env.getProperty("spring.flyway.user", user),
                        env.getProperty("spring.flyway.password", password), "45");
                System.out.println("판매 스키마 마이그레이션 완료.");
                return;
            }
            if (inspecting) {
                try (var c = DriverManager.getConnection(url, user, password)) {
                    for (String table : new TreeSet<>(CoupangSchema.existing(c)))
                        try (var st = c.createStatement();
                                var r = st.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
                            r.next();
                            System.out.println(table + "=" + r.getLong(1));
                        }
                }
                return;
            }
            if (auditing) {
                Path backup = Path.of(args[1]).toAbsolutePath().normalize();
                var expected = new Properties();
                try (var in = Files.newInputStream(backup.resolve("preserved-before.properties"))) {
                    expected.load(in);
                }
                expected.remove("flyway_schema_history");
                try (var c = DriverManager.getConnection(url, user, password)) {
                    var current = digests(c);
                    CoupangSchema.TABLES.forEach(current::remove);
                    current.remove("flyway_schema_history");
                    var actual = new Properties();
                    actual.putAll(current);
                    if (!actual.equals(expected))
                        throw new IllegalStateException("보존 데이터 대조 실패. 백업을 유지하고 전환을 중단하세요.");
                    for (String table : CoupangSchema.existing(c))
                        try (var st = c.createStatement();
                                var r = st.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
                            r.next();
                            if (r.getLong(1) != 0)
                                throw new IllegalStateException(
                                        "초기화 후 판매 테이블 " + table + "에 " + r.getLong(1) + "개 행이 있습니다. 실행 중인 앱을 확인하세요.");
                        }
                }
                Files.writeString(backup.resolve("MIGRATION_VERIFIED"), "판매 데이터 비어 있음; 비판매 데이터 동일 (Flyway 적용 이력 제외)\n");
                System.out.println("마이그레이션 후 판매 초기화와 보존 데이터 동일성 확인 완료.");
                return;
            }
            Path backup = Path.of(args[1]).toAbsolutePath().normalize();
            Files.createDirectory(backup);
            Path assets = Path.of(env.getProperty("coupang.assets.directory", "./data/coupang-assets")).toAbsolutePath()
                    .normalize();
            if (backup.startsWith(assets) || Files.isSymbolicLink(assets))
                throw new IllegalStateException("백업 위치 또는 판매 이미지 경로를 확인하세요.");
            String verifyUrl = System.getenv("MOLEBUTTER_RESET_VERIFY_DB_URL");
            if (verifyUrl == null || !verifyUrl.startsWith("jdbc:mysql://127.0.0.1:"))
                throw new IllegalStateException("격리 복원 검증 DB가 필요합니다. reset-sales-data.sh를 사용하세요.");
            try (var c = DriverManager.getConnection(url, user, password)) {
                var order = CoupangSchema.deletionOrder(c);
                if (order.contains("coupang_asset")) {
                    try (var statement = c.createStatement();
                            var rows = statement.executeQuery("SELECT COUNT(*) FROM coupang_asset")) {
                        rows.next();
                        if (rows.getLong(1) > 0 && !Files.isDirectory(assets))
                            throw new IllegalStateException(
                                    "판매 이미지 경로가 없습니다. 실행 시 COUPANG_ASSETS_DIRECTORY를 실제 경로로 지정하세요.");
                    }
                }
                Files.writeString(backup.resolve("assets-location.txt"), assets.toString());
                var all = digests(c);
                var preserved = new TreeMap<>(all);
                CoupangSchema.TABLES.forEach(preserved::remove);
                var preservedProperties = new Properties();
                preservedProperties.putAll(preserved);
                try (var out = Files.newOutputStream(backup.resolve("preserved-before.properties"))) {
                    preservedProperties.store(out, "Preserved business table fingerprints");
                }
                var uri = URI.create(url.substring(5));
                String db = uri.getPath().substring(1);
                if (!db.matches("[A-Za-z0-9_]+"))
                    throw new IllegalStateException("DB 이름을 확인하세요.");
                Path dump = backup.resolve("database.sql");
                var dumpCommand = new ArrayList<>(List.of("mysqldump", "--single-transaction", "--hex-blob",
                        "--no-tablespaces", "--skip-add-locks", "--set-gtid-purged=OFF", "--host=" + uri.getHost(),
                        "--port=" + (uri.getPort() < 0 ? 3306 : uri.getPort()), "--user=" + user, db));
                process(dumpCommand, password, null, dump, backup.resolve("dump-error.log"));
                String host = System.getenv("MOLEBUTTER_RESET_VERIFY_PORT");
                if (host == null || !host.matches("[0-9]+"))
                    throw new IllegalStateException("격리 복원 포트를 확인하세요.");
                process(List.of("mysql", "--no-defaults", "--binary-mode", "--host=127.0.0.1", "--port=" + host,
                        "--user=root", "sales_reset_verify"), "", dump, backup.resolve("restore-output.log"),
                        backup.resolve("restore-error.log"));
                try (var restored = DriverManager.getConnection(verifyUrl, "root", "")) {
                    if (!all.equals(digests(restored)))
                        throw new IllegalStateException("복원 데이터 대조 실패. 기존 데이터는 삭제하지 않았습니다.");
                }
                Files.writeString(backup.resolve("verified-before.txt"), all.toString());
                Path savedAssets = backup.resolve("coupang-assets");
                boolean moved = false;
                c.setAutoCommit(false);
                try {
                    // Recheck the complete snapshot before any deletion.
                    if (!all.equals(digests(c)))
                        throw new IllegalStateException("백업 중 데이터가 변경되었습니다. 앱·워커 중지를 확인하세요.");
                    if (Files.exists(assets)) {
                        Files.move(assets, savedAssets, StandardCopyOption.ATOMIC_MOVE);
                        moved = true;
                    }
                    for (String table : order)
                        try (var statement = c.createStatement()) {
                            statement.executeUpdate("DELETE FROM `" + table + "`");
                        }
                    var after = digests(c);
                    CoupangSchema.TABLES.forEach(after::remove);
                    if (!preserved.equals(after))
                        throw new IllegalStateException("보존 데이터 대조 실패. 초기화를 취소했습니다.");
                    c.commit();
                    Files.writeString(backup.resolve("RESET_COMPLETE"),
                            "복원 검증 완료; 판매 데이터 " + order.size() + "개 테이블 초기화; 보존 데이터 동일\n");
                    System.out.println("판매 데이터 초기화 완료. DB 복원 검증 및 보존 데이터 동일성 확인. 백업: " + backup);
                } catch (Exception failure) {
                    c.rollback();
                    if (moved && !Files.exists(assets))
                        Files.move(savedAssets, assets, StandardCopyOption.ATOMIC_MOVE);
                    throw failure;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        }
    }
    public static void migrate(String url, String user, String password) {
        migrate(url, user, password, null);
    }
    public static void migrate(String url, String user, String password, String target) {
        var config = org.flywaydb.core.Flyway.configure().dataSource(url, user, password)
                .locations("classpath:db/migration").cleanDisabled(true);
        if (target != null)
            config.target(target);
        config.load().migrate();
    }
    private static void process(List<String> command, String password, Path input, Path output, Path error)
            throws Exception {
        var builder = new ProcessBuilder(command);
        builder.environment().put("MYSQL_PWD", password);
        if (input != null)
            builder.redirectInput(input.toFile());
        builder.redirectOutput(output.toFile());
        builder.redirectError(error.toFile());
        var process = builder.start();
        if (process.waitFor() != 0)
            throw new IllegalStateException("DB 백업 또는 격리 복원 명령 실패. 초기화를 중단했습니다.");
    }
    public static TreeMap<String, String> digests(Connection c) throws Exception {
        var tables = new ArrayList<String>();
        try (var s = c.createStatement();
                var r = s.executeQuery(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name")) {
            while (r.next())
                tables.add(r.getString(1));
        }
        var result = new TreeMap<String, String>();
        for (String table : tables) {
            if (!table.matches("[A-Za-z0-9_]+"))
                throw new IllegalStateException("테이블 이름을 확인하세요.");
            result.put(table, queryDigest(c, "SELECT * FROM `" + table + "`"));
        }
        return result;
    }
    public static String queryDigest(Connection c, String sql) throws Exception {
        var rows = new ArrayList<String>();
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
            int columns = r.getMetaData().getColumnCount();
            while (r.next()) {
                var row = MessageDigest.getInstance("SHA-256");
                for (int n = 1; n <= columns; n++) {
                    byte[] value = r.getBytes(n);
                    row.update(ByteBuffer.allocate(4).putInt(value == null ? -1 : value.length).array());
                    if (value != null)
                        row.update(value);
                }
                rows.add(HexFormat.of().formatHex(row.digest()));
            }
        }
        Collections.sort(rows);
        return rows.size() + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.join("\n", rows).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

}
