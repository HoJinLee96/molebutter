package cc.ataglace.molebutter.app.internal.maintenance;

import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import org.springframework.boot.*;
import org.springframework.context.annotation.Configuration;
import db.migration.V47__coupang_working_copies;
import db.migration.V48__coupang_intent_and_audit;

/**
 * Offline domain upgrade with a verified full restore; never resets sales data
 * or starts workers.
 */
public final class CoupangWorkingCopyMaintenance {
    @Configuration(proxyBeanMethods = false)
    static class Settings {
    }
    public static void main(String[] args) {
        main(args, 47);
    }
    static void main(String[] args, int target) {
        try {
            run(args, target);
        } catch (Exception failure) {
            System.err.println("쿠팡 전환 중단: " + (failure instanceof IllegalStateException
                    ? failure.getMessage()
                    : failure.getClass().getSimpleName() + "; 백업을 보존하고 DB·파일 상태를 확인하세요."));
            System.exit(1);
        }
    }
    private static void run(String[] args, int target) throws Exception {
        if (args.length != 2 || !args[0].equals("--apply"))
            throw new IllegalStateException("사용법: --apply <새 백업 디렉터리>");
        var app = new SpringApplication(Settings.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        app.setDefaultProperties(Map.of("logging.level.root", "ERROR"));
        try (var context = app.run()) {
            var env = context.getEnvironment();
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", env.getProperty("server.port", Integer.class, 8081)),
                        300);
                throw new IllegalStateException("앱과 워커를 중지한 뒤 전환하세요.");
            } catch (ConnectException stopped) {
            }
            String url = env.getRequiredProperty("spring.datasource.url"),
                    user = env.getRequiredProperty("spring.datasource.username"),
                    password = env.getRequiredProperty("spring.datasource.password");
            String verify = System.getenv("MOLEBUTTER_UPGRADE_VERIFY_DB_URL"),
                    port = System.getenv("MOLEBUTTER_UPGRADE_VERIFY_PORT");
            if (verify == null || !verify.startsWith("jdbc:mysql://127.0.0.1:") || port == null
                    || !port.matches("[0-9]+"))
                throw new IllegalStateException("쿠팡 전환 스크립트의 격리 복원 DB가 필요합니다.");
            var backup = Path.of(args[1]).toAbsolutePath().normalize();
            var current = Path.of(env.getProperty("coupang.assets.directory", "./data/coupang-assets")).toAbsolutePath()
                    .normalize();
            if (backup.startsWith(current) || Files.isSymbolicLink(current))
                throw new IllegalStateException("이미지 경로와 새 백업 위치를 확인하세요.");
            try (var connection = DriverManager.getConnection(url, user, password)) {
                try (var st = connection.createStatement();
                        var r = st.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version='" + target
                                + "' AND success=TRUE")) {
                    r.next();
                    if (r.getLong(1) == 1) {
                        System.out.println("쿠팡 V" + target + "은 이미 적용되어 있습니다. 데이터와 파일을 변경하지 않았습니다.");
                        return;
                    }
                }
                if (target == 48)
                    try (var st = connection.createStatement();
                            var r = st.executeQuery(
                                    "SELECT COUNT(*) FROM flyway_schema_history WHERE version='47' AND success=TRUE")) {
                        r.next();
                        if (r.getLong(1) != 1)
                            throw new IllegalStateException("V47 적용 후 V48 전환 명령을 사용하세요.");
                    }
                Files.createDirectory(backup);
                String vendor = env.getProperty("coupang.vendor-id", "").trim();
                if (target == 47 && vendor.isEmpty())
                    throw new IllegalStateException("기존 실행 설정에 쿠팡 계정이 필요합니다.");
                String account = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(vendor.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                var full = SalesDataMaintenance.digests(connection);
                var before = preserved(full);
                var evidence = preservationEvidence(full, target);
                var immutablePreviews = SalesDataMaintenance.queryDigest(connection,
                        "SELECT p.* FROM coupang_submission_preview p JOIN coupang_execution e ON e.preview_id=p.id");
                var files = fileHashes(current);
                try (var st = connection.createStatement();
                        var rows = st.executeQuery("SELECT relative_path FROM coupang_asset")) {
                    while (rows.next()) {
                        var relative = Path.of(rows.getString(1));
                        var asset = current.resolve(relative).normalize();
                        if (relative.isAbsolute() || !asset.startsWith(current)
                                || !files.containsKey(current.relativize(asset).toString()))
                            throw new IllegalStateException(
                                    "DB가 참조하는 판매 이미지 파일이 없습니다. COUPANG_ASSETS_DIRECTORY와 파일을 확인하세요.");
                    }
                }
                var uri = URI.create(url.substring(5));
                String database = uri.getPath().substring(1);
                if (!database.matches("[A-Za-z0-9_]+"))
                    throw new IllegalStateException("DB 이름을 확인하세요.");
                var dump = backup.resolve("database.sql");
                process(List.of("mysqldump", "--single-transaction", "--hex-blob", "--no-tablespaces",
                        "--skip-add-locks", "--set-gtid-purged=OFF", "--host=" + uri.getHost(),
                        "--port=" + (uri.getPort() < 0 ? 3306 : uri.getPort()), "--user=" + user, database), password,
                        null, dump, backup.resolve("dump-error.log"));
                process(List.of("mysql", "--no-defaults", "--binary-mode", "--host=127.0.0.1", "--port=" + port,
                        "--user=root", "coupang_upgrade_verify"), "", dump, backup.resolve("restore-output.log"),
                        backup.resolve("restore-error.log"));
                try (var restored = DriverManager.getConnection(verify, "root", "")) {
                    if (!full.equals(SalesDataMaintenance.digests(restored)))
                        throw new IllegalStateException("백업 복원 대조에 실패했습니다. 데이터를 변경하지 않았습니다.");
                }
                if (Files.exists(current))
                    copyTree(current, backup.resolve("sales-assets"));
                if (!files.equals(fileHashes(backup.resolve("sales-assets"))))
                    throw new IllegalStateException("이미지 백업 대조에 실패했습니다.");
                Files.writeString(backup.resolve("verified-before.txt"), full.toString());
                Files.writeString(backup.resolve("canonical-before.txt"), before.toString());
                Files.writeString(backup.resolve("asset-locations.txt"), current + "\n");
                if (!full.equals(SalesDataMaintenance.digests(connection)) || !files.equals(fileHashes(current)))
                    throw new IllegalStateException("백업 중 데이터가 변경되었습니다.");
                if (target == 47) {
                    var unassigned = V47__coupang_working_copies.unassigned(connection);
                    Files.writeString(backup.resolve("preflight.txt"),
                            "V47; accountless working copies assigned to current configured account: " + unassigned
                                    + "\n");
                    System.setProperty("molebutter.working-copy.account", account);
                    System.setProperty("molebutter.working-copy.verified", "true");
                    System.setProperty("molebutter.working-copy.public-base",
                            env.getProperty("coupang.assets.public-base-url", "").replaceFirst("/+$", ""));
                } else if (target == 48) {
                    V48__coupang_intent_and_audit.check(connection);
                    Files.writeString(backup.resolve("preflight.txt"),
                            "V48; obsolete history is archived; previous row-list inputs remain stored and require explicit re-preparation\n");
                    System.setProperty("molebutter.intent.verified", "true");
                } else
                    throw new IllegalStateException("지원하지 않는 전환 버전입니다.");
                try {
                    if (target == 47)
                        V47__coupang_working_copies.check(connection, account);
                    SalesDataMaintenance.migrate(env.getProperty("spring.flyway.url", url),
                            env.getProperty("spring.flyway.user", user),
                            env.getProperty("spring.flyway.password", password), Integer.toString(target));
                } finally {
                    for (String property : List.of("molebutter.working-copy.account",
                            "molebutter.working-copy.verified", "molebutter.working-copy.public-base",
                            "molebutter.intent.verified"))
                        System.clearProperty(property);
                }
                if (!before.equals(preserved(SalesDataMaintenance.digests(connection))))
                    throw new IllegalStateException("전환 후 행·참조 데이터가 일치하지 않습니다. 재기동하지 말고 백업을 보존하세요.");
                if (!immutablePreviews.equals(SalesDataMaintenance.queryDigest(connection,
                        "SELECT p.* FROM coupang_submission_preview p JOIN coupang_execution e ON e.preview_id=p.id")))
                    throw new IllegalStateException("실행된 미리보기 증거가 변경되었습니다. 재기동하지 말고 복원하세요.");
                Files.writeString(backup.resolve("immutable-previews.sha256"), immutablePreviews);
                var after = SalesDataMaintenance.digests(connection);
                for (var proof : evidence.entrySet())
                    if (!Objects.equals(proof.getValue(), after.get(proof.getKey())))
                        throw new IllegalStateException("전환 후 판매 증거·원본·참조 지문이 다릅니다. 재기동하지 말고 복원하세요.");
                Files.writeString(backup.resolve("verified-after.txt"), after.toString());
                if (!files.equals(fileHashes(current)))
                    throw new IllegalStateException("전환 후 이미지 데이터가 일치하지 않습니다.");
                Files.writeString(backup.resolve("UPGRADE_COMPLETE"), "V" + target
                        + " applied; all business rows, immutable evidence, IDs and asset bytes preserved\n");
                System.out.println("쿠팡 V" + target + " 전환 완료. 전체 DB 격리 복원·행 지문·이미지 바이트 동일성 확인. 백업: " + backup);
            }
        }
    }
    private static Map<String, String> preservationEvidence(Map<String, String> values, int target) {
        var out = new TreeMap<String, String>();
        var renamed = target == 48
                ? Map.of("coupang_listing_history", "legacy_coupang_listing_history", "coupang_listing_history_asset",
                        "legacy_coupang_listing_history_asset", "coupang_editor_history_link",
                        "legacy_coupang_editor_history_link")
                : Map.of("coupang_listing_state", "legacy_coupang_listing_state", "coupang_draft",
                        "legacy_coupang_working_copy_source", "coupang_draft_asset", "coupang_working_copy_asset",
                        "coupang_listing_mapping", "legacy_coupang_listing_mapping", "coupang_draft_target_edit",
                        "legacy_coupang_target_edit", "coupang_target_saved", "legacy_coupang_target_saved",
                        "coupang_comparison_resolution", "legacy_coupang_comparison_resolution",
                        "coupang_media_comparison", "legacy_coupang_media_comparison");
        values.forEach((k, v) -> {
            if ((k.startsWith("coupang_") || k.startsWith("legacy_coupang_"))
                    && !Set.of("coupang_edit_session", "coupang_submission_preview").contains(k))
                out.put(renamed.getOrDefault(k, k), v);
        });
        return out;
    }
    private static Map<String, String> preserved(Map<String, String> values) {
        var out = new TreeMap<String, String>();
        values.forEach((k, v) -> {
            if (!k.startsWith("coupang_") && !k.startsWith("legacy_coupang_") && !k.equals("flyway_schema_history"))
                out.put(k, v);
        });
        return out;
    }
    private static void process(List<String> command, String password, Path input, Path output, Path error)
            throws Exception {
        var builder = new ProcessBuilder(command);
        builder.environment().put("MYSQL_PWD", password);
        if (input != null)
            builder.redirectInput(input.toFile());
        builder.redirectOutput(output.toFile());
        builder.redirectError(error.toFile());
        if (builder.start().waitFor() != 0)
            throw new IllegalStateException("DB 백업 또는 격리 복원 명령 실패. 백업을 보존하세요.");
    }
    private static Map<String, String> fileHashes(Path directory) throws Exception {
        var result = new TreeMap<String, String>();
        if (!Files.exists(directory))
            return result;
        try (var paths = Files.walk(directory)) {
            for (var path : paths.toList()) {
                if (Files.isSymbolicLink(path))
                    throw new IllegalStateException("판매 이미지 디렉터리에 심볼릭 링크가 있습니다.");
                if (Files.isRegularFile(path))
                    result.put(directory.relativize(path).toString(), HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            }
        }
        return result;
    }
    private static void copyTree(Path from, Path to) throws Exception {
        try (var paths = Files.walk(from)) {
            for (var path : paths.toList()) {
                var destination = to.resolve(from.relativize(path));
                if (Files.isDirectory(path))
                    Files.createDirectories(destination);
                else
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }
}
