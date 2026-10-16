package cc.ataglace.molebutter;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.coupang.api.*;
import static org.assertj.core.api.Assertions.*;
class CoupangWorkingCopyMigrationIT {
    @Test
    void v46InputsIdsAssetsAndEvidenceSurviveAndPreflightRunsBeforeDDL() throws Exception {
        String base = System.getenv("MOLEBUTTER_TEST_DB_URL");
        Assumptions.assumeTrue(base != null);
        String url = base.replace("/molebutter_test?", "/molebutter_working_copy_test?");
        var json = new ObjectMapper();
        String account = "a".repeat(64), option = UUID.randomUUID().toString(), asset = UUID.randomUUID().toString();
        var old = json
                .readTree(
                        """
                                {"common":{"name":"internal name","productName":"product","brand":"brand","manufacturer":"maker"},"options":[],"stockMode":"OPTION","media":{"images":[],"contents":[]},"delivery":{},"configuration":{"categoryCode":"10","coupang":{"settings":[],"delivery":[],"options":[],"documents":[]}}}
                                """)
                .asObject();
        old.path("options").asArray().add(json.readTree("{\"id\":\"" + option
                + "\",\"name\":\"option\",\"price\":\"120000\",\"quantity\":\"9\",\"sku\":\"sku\"}"));
        old.path("media").path("images").asArray().add(json.readTree("{\"id\":\"" + asset + "\",\"assetId\":\"" + asset
                + "\",\"url\":\"/api/coupang/assets/" + asset + "\",\"representative\":true,\"order\":0}"));
        var observation = old.deepCopy();
        observation.path("common").asObject().put("name", "coupang name");
        var intent = json.createObjectNode();
        intent.set("observed", observation);
        var change = intent.putArray("changes").addObject();
        change.put("path", "options.price");
        change.put("optionId", option);
        change.put("value", "130000");
        String original = old.toString(), evidence = "{\"unchanged\":true,\"hash\":\"old hash\"}";
        Flyway.configure().dataSource(url, "test_migrator", "isolated-test-migration-password").target("46").load()
                .migrate();
        try (var c = DriverManager.getConnection(url, "test_migrator", "isolated-test-migration-password");
                var s = c.createStatement()) {
            try (var q = c.prepareStatement(
                    "INSERT INTO coupang_draft(id,revision,document_json,created_by,updated_by,created_at,updated_at) VALUES(1,7,?,42,43,NOW(6),NOW(6))")) {
                q.setString(1, original);
                q.executeUpdate();
            }
            assertThat(db.mv(c,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='coupang_product_working_copy'"))
                    .isZero();
            assertThatThrownBy(() -> db.migrate(url)).hasStackTraceContaining("Unassigned working copy");
            assertThat(db.mv(c, "SELECT COUNT(*) FROM coupang_draft")).isEqualTo(1);
            db.repair(url);
            System.setProperty("molebutter.working-copy.account", account);
            try {
                try (var q = c.prepareStatement(
                        "INSERT INTO coupang_draft_target_edit(draft_id,account_key,draft_revision,mapping_json,intent_json,updated_by,updated_at) VALUES(1,?,7,'null',?,43,NOW(6))")) {
                    q.setString(1, account);
                    q.setString(2, intent.toString());
                    q.executeUpdate();
                }
                s.executeUpdate(
                        "INSERT INTO coupang_submission_account(account_key,lease_owner,lease_until) VALUES(REPEAT('a',64),'worker',DATE_ADD(NOW(6),INTERVAL 1 HOUR))");
                assertThatThrownBy(() -> db.migrate(url)).hasStackTraceContaining("Active leases");
                db.repair(url);
                s.executeUpdate("UPDATE coupang_submission_account SET lease_owner=NULL,lease_until=NULL");
                s.executeUpdate(
                        "INSERT INTO coupang_asset(id,relative_path,media_type,width,height,bytes,created_by,created_at) VALUES('"
                                + asset + "','original.png','image/png',500,500,100,42,NOW(6))");
                s.executeUpdate("INSERT INTO coupang_draft_asset VALUES(1,'" + asset + "')");
                s.executeUpdate("INSERT INTO coupang_asset_publication VALUES('" + asset + "',REPEAT('b',64),NOW(6))");
                try (var q = c.prepareStatement(
                        "INSERT INTO coupang_submission_preview(id,draft_id,draft_revision,created_by,requested,document_json,prepared_json,created_at,expires_at) VALUES(2,1,7,42,FALSE,?,?,NOW(6),'2030-01-01')")) {
                    q.setString(1, original);
                    q.setString(2, evidence);
                    q.executeUpdate();
                }
                var linked = old.deepCopy();
                var serverDoc = new CoupangEditor.EditorDocument(
                        new CoupangEditor.Basic("555", null, "실제 쿠팡 이름", "노출 이름", "제품", "brand", null, "10", "승인완료"),
                        new CoupangEditor.Limits(true, true, true),
                        List.of(new CoupangEditor.EditOption("111", "222", "option",
                                new CoupangCatalog.CurrentInventory("222", 180000L, 4L, true), null, true, List.of(),
                                List.of(new CoupangCatalog.Image(0, "REPRESENTATION",
                                        "https://app.example.test/coupang/images/" + "b".repeat(64))),
                                List.of(), List.of(), List.of(), List.of())),
                        List.of(), List.of(), List.of());
                linked.path("configuration").path("coupang").asObject().put("sellerProductId", "555").set("source",
                        json.valueToTree(serverDoc));
                var links = linked.path("configuration").path("coupang").path("options").asArray();
                links.add(json.readTree(
                        "{\"optionId\":\"" + option + "\",\"sellerProductItemId\":\"111\",\"vendorItemId\":\"222\"}"));
                try (var q = c.prepareStatement(
                        "INSERT INTO coupang_draft(id,revision,document_json,import_account,import_product_id,created_by,updated_by,created_at,updated_at) VALUES(3,8,?,?,'555',42,43,NOW(6),NOW(6))")) {
                    q.setString(1, linked.toString());
                    q.setString(2, account);
                    q.executeUpdate();
                }
                String mapping = "{\"accountKey\":\"" + account
                        + "\",\"sellerProductId\":\"555\",\"channelProductId\":null,\"options\":[{\"optionId\":\""
                        + option + "\",\"sellerProductItemId\":\"111\",\"vendorItemId\":\"222\"}]}";
                try (var q = c.prepareStatement(
                        "INSERT INTO coupang_listing_mapping(draft_id,account_key,external_product_id,mapping_json,updated_at) VALUES(3,?,'555',?,NOW(6))")) {
                    q.setString(1, account);
                    q.setString(2, mapping);
                    q.executeUpdate();
                }
                try (var q = c.prepareStatement(
                        "INSERT INTO coupang_listing_state(draft_id,account_key,listing_version,state,observed_json) VALUES(3,?,4,'ACTIVE',?)")) {
                    q.setString(1, account);
                    q.setString(2, original);
                    q.executeUpdate();
                }
                assertThatThrownBy(() -> db.migrate(url)).hasStackTraceContaining("verified offline backup/restore");
                db.repair(url);
                System.setProperty("molebutter.working-copy.verified", "true");
                db.migrate(url);
                assertThat(db.mv(c, "SELECT revision FROM coupang_product_working_copy WHERE id=1")).isEqualTo(7);
                assertThat(db.mv(c, "SELECT created_by FROM coupang_product_working_copy WHERE id=1")).isEqualTo(42);
                try (var r = s.executeQuery(
                        "SELECT document_json,changes_json FROM coupang_product_working_copy WHERE id=1")) {
                    r.next();
                    var d = json.readTree(r.getString(1));
                    assertThat(d.path("basic").path("sellerProductName").asString()).isEqualTo("coupang name");
                    assertThat(d.path("options").get(0).path("registration").path("salePrice").asString())
                            .isEqualTo("130000");
                    assertThat(d.path("options").get(0).path("id").asString()).isEqualTo(option);
                    assertThat(r.getString(2)).contains("options.registration.salePrice");
                }
                try (var r = s.executeQuery(
                        "SELECT document_json,mapping_json FROM coupang_product_working_copy WHERE id=3")) {
                    r.next();
                    var d = json.readTree(r.getString(1));
                    assertThat(d.path("basic").path("sellerProductName").asString()).isEqualTo("실제 쿠팡 이름");
                    assertThat(d.path("options").get(0).path("registration").path("salePrice").asString())
                            .isEqualTo("180000");
                    assertThat(r.getString(2)).doesNotContain("channelProductId");
                    assertThat(d.path("options").get(0).path("images").get(0).path("id").asString()).isEqualTo(asset);
                }
                assertThat(db.mv(c, "SELECT listing_version FROM coupang_listing_state WHERE working_copy_id=3"))
                        .isEqualTo(4);
                assertThat(db.mv(c,
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coupang_listing_state' AND column_name='observed_json'"))
                        .isZero();
                assertThat(db.mv(c, "SELECT COUNT(*) FROM coupang_working_copy_asset WHERE working_copy_id=1"))
                        .isEqualTo(1);
                assertThat(db.mv(c, "SELECT COUNT(*) FROM legacy_coupang_asset_pin WHERE asset_id='" + asset + "'"))
                        .isEqualTo(1);
                try (var r = s
                        .executeQuery("SELECT document_json FROM legacy_coupang_working_copy_source WHERE id=1")) {
                    r.next();
                    assertThat(r.getString(1)).isEqualTo(original);
                }
                try (var r = s.executeQuery("SELECT prepared_json FROM coupang_submission_preview WHERE id=2")) {
                    r.next();
                    assertThat(r.getString(1)).isEqualTo(evidence);
                }
                assertThat(db.mv(c, "SELECT COUNT(*) FROM coupang_submission_preview WHERE expires_at<NOW(6)"))
                        .isEqualTo(1);
                assertThat(db.migrate(url)).isZero();
            } finally {
                System.clearProperty("molebutter.working-copy.account");
                System.clearProperty("molebutter.working-copy.verified");
            }
        }
    }
    static class db {
        static long mv(Connection c, String sql) throws SQLException {
            try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
                r.next();
                return r.getLong(1);
            }
        }
        static int migrate(String url) {
            return Flyway.configure().dataSource(url, "test_migrator", "isolated-test-migration-password").target("47")
                    .load().migrate().migrationsExecuted;
        }
        static void repair(String url) {
            Flyway.configure().dataSource(url, "test_migrator", "isolated-test-migration-password").target("47").load()
                    .repair();
        }
    }
}
