package cc.ataglace.molebutter.app.internal.maintenance;

import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import db.migration.V47__coupang_working_copies;
import static org.assertj.core.api.Assertions.*;

class CoupangV47PreparationIT {
    @Test
    void offlinePreparationPreservesMediaAndEvidenceAndRejectsUnsafeOrInterruptedTransitions() throws Exception {
        String base = System.getenv("MOLEBUTTER_TEST_DB_URL");
        Assumptions.assumeTrue(base != null);
        String url = base.replace("/molebutter_test?", "/molebutter_v47_preparation_test?");
        String account = "a".repeat(64), option = UUID.randomUUID().toString(), asset = UUID.randomUUID().toString();
        Flyway.configure().dataSource(url, "test_migrator", "isolated-test-migration-password").target("46").load()
                .migrate();
        try (var c = DriverManager.getConnection(url, "test_migrator", "isolated-test-migration-password");
                var s = c.createStatement()) {
            s.executeUpdate(
                    "INSERT INTO coupang_asset(id,relative_path,media_type,width,height,bytes,created_by,created_at) VALUES('"
                            + asset + "','original.png','image/png',500,500,25,1,NOW(6))");
            s.executeUpdate("INSERT INTO coupang_asset_publication VALUES('" + asset + "',REPEAT('b',64),NOW(6))");
            String original = CoupangWorkingCopyFixture.seedLinked(c, account, option, asset);
            var json = new ObjectMapper();
            // An actual saved price intent must remain byte-for-byte unchanged.
            var other = json.readTree(original).asObject();
            other.path("configuration").path("coupang").asObject().remove("source");
            try (var q = c.prepareStatement(
                    "INSERT INTO coupang_draft(id,revision,document_json,created_by,updated_by,created_at,updated_at) VALUES(12,7,?,1,1,NOW(6),NOW(6))")) {
                q.setString(1, other.toString());
                q.executeUpdate();
            }
            var savedIntent = json.createObjectNode();
            savedIntent.set("observed", other);
            savedIntent.putArray("changes").addObject().put("path", "options.price").put("optionId", option)
                    .put("value", "130000");
            try (var q = c.prepareStatement(
                    "INSERT INTO coupang_draft_target_edit(draft_id,account_key,draft_revision,mapping_json,intent_json,updated_by,updated_at) VALUES(12,?,7,'null',?,1,NOW(6))")) {
                q.setString(1, account);
                q.setString(2, savedIntent.toString());
                q.executeUpdate();
            }
            var before = SalesDataMaintenance.digests(c);
            assertThatThrownBy(() -> V47__coupang_working_copies.check(c, account)).hasMessageContaining("이미지 식별자");
            assertThatThrownBy(() -> CoupangV47Preparation.prepare(c, account, "https://app.example.test"))
                    .hasMessageContaining("전체 백업");
            System.setProperty("molebutter.working-copy.verified", "true");
            System.setProperty("molebutter.working-copy.account", account);
            try {
                // Inserted preparation rows must roll back when the unchanged V47 preflight
                // rejects active work.
                s.executeUpdate(
                        "INSERT INTO coupang_submission_account(account_key,lease_owner,lease_until) VALUES(REPEAT('a',64),'worker',DATE_ADD(NOW(6),INTERVAL 1 HOUR))");
                var active = SalesDataMaintenance.digests(c);
                assertThatThrownBy(() -> CoupangV47Preparation.prepare(c, account, "https://app.example.test"))
                        .hasMessageContaining("Active leases");
                assertThat(SalesDataMaintenance.digests(c)).isEqualTo(active);
                s.executeUpdate("DELETE FROM coupang_submission_account");
                s.executeUpdate("UPDATE coupang_draft SET registration_account=REPEAT('c',64) WHERE id=11");
                var mismatch = SalesDataMaintenance.digests(c);
                assertThatThrownBy(() -> CoupangV47Preparation.prepare(c, account, "https://app.example.test"))
                        .hasMessageContaining("Account mismatch");
                assertThat(SalesDataMaintenance.digests(c)).isEqualTo(mismatch);
                s.executeUpdate("UPDATE coupang_draft SET registration_account=NULL WHERE id=11");
                try (var q = c.prepareStatement(
                        "INSERT INTO coupang_draft(id,revision,document_json,registration_account,import_product_id,created_by,updated_by,created_at,updated_at) VALUES(13,8,?,?,'555',1,1,NOW(6),NOW(6))")) {
                    q.setString(1, original);
                    q.setString(2, account);
                    q.executeUpdate();
                }
                var duplicate = SalesDataMaintenance.digests(c);
                assertThatThrownBy(() -> CoupangV47Preparation.prepare(c, account, "https://app.example.test"))
                        .hasMessageContaining("Duplicate linked product");
                assertThat(SalesDataMaintenance.digests(c)).isEqualTo(duplicate);
                s.executeUpdate("DELETE FROM coupang_draft WHERE id=13");
                var badMapping = json.createObjectNode().put("accountKey", account).put("sellerProductId", "555")
                        .put("channelProductId", "unexpected-channel");
                badMapping.putArray("options").addObject().put("optionId", option).put("sellerProductItemId", "111")
                        .put("vendorItemId", "222");
                try (var q = c.prepareStatement(
                        "INSERT INTO coupang_listing_mapping(draft_id,account_key,external_product_id,mapping_json,updated_at) VALUES(11,?,'555',?,NOW(6))")) {
                    q.setString(1, account);
                    q.setString(2, badMapping.toString());
                    q.executeUpdate();
                }
                var invalidChannel = SalesDataMaintenance.digests(c);
                assertThatThrownBy(() -> CoupangV47Preparation.prepare(c, account, "https://app.example.test"))
                        .hasMessageContaining("다른 채널 연결");
                assertThat(SalesDataMaintenance.digests(c)).isEqualTo(invalidChannel);
                s.executeUpdate("DELETE FROM coupang_listing_mapping WHERE draft_id=11");
                var missing = json.readTree(original).asObject();
                missing.path("configuration").path("coupang").asObject().remove("source");
                try (var q = c.prepareStatement("UPDATE coupang_draft SET document_json=? WHERE id=11")) {
                    q.setString(1, missing.toString());
                    q.executeUpdate();
                }
                var missingSource = SalesDataMaintenance.digests(c);
                assertThatThrownBy(() -> CoupangV47Preparation.prepare(c, account, "https://app.example.test"))
                        .hasMessageContaining("lacks an authoritative");
                assertThat(SalesDataMaintenance.digests(c)).isEqualTo(missingSource);
                try (var q = c.prepareStatement("UPDATE coupang_draft SET document_json=? WHERE id=11")) {
                    q.setString(1, original);
                    q.executeUpdate();
                }
                assertThat(SalesDataMaintenance.digests(c)).isEqualTo(before);
                var prepared = CoupangV47Preparation.prepare(c, account, "https://app.example.test");
                assertThat(prepared).extracting(CoupangV47Preparation.Observation::id).containsExactly(11L);
                assertThatThrownBy(() -> CoupangV47Preparation.requireComplete(c)).hasMessageContaining("완료되지 않은 V47");
                V47__coupang_working_copies.check(c, account);
                Flyway.configure().dataSource(url, "test_migrator", "isolated-test-migration-password").target("47")
                        .load().migrate();
                assertThatThrownBy(() -> CoupangV47Preparation.requireComplete(c)).hasMessageContaining("완료되지 않은 V47");
                try (var q = c
                        .prepareStatement("UPDATE legacy_coupang_target_edit SET intent_json=? WHERE draft_id=11")) {
                    q.setString(1, prepared.getFirst().intent().replace("fresh Coupang title", "FRESH Coupang title"));
                    q.executeUpdate();
                }
                var tampered = SalesDataMaintenance.digests(c);
                assertThatThrownBy(() -> CoupangV47Preparation.finish(c, prepared, () -> null))
                        .hasMessageContaining("전환용 입력이 변경");
                assertThat(SalesDataMaintenance.digests(c)).isEqualTo(tampered);
                try (var q = c
                        .prepareStatement("UPDATE legacy_coupang_target_edit SET intent_json=? WHERE draft_id=11")) {
                    q.setString(1, prepared.getFirst().intent());
                    q.executeUpdate();
                }
                CoupangV47Preparation.finish(c, prepared, () -> null);
                CoupangV47Preparation.requireComplete(c);
                CoupangWorkingCopyFixture.verifyLinked(c, asset);
                var after = SalesDataMaintenance.digests(c);
                assertThat(after.get("legacy_coupang_working_copy_source")).isEqualTo(before.get("coupang_draft"));
                assertThat(after.get("legacy_coupang_target_edit")).isEqualTo(before.get("coupang_draft_target_edit"));
                assertThat(after.get("coupang_working_copy_asset")).isEqualTo(before.get("coupang_draft_asset"));
                assertThat(after.get("coupang_asset_publication")).isEqualTo(before.get("coupang_asset_publication"));
                for (String table : before.keySet())
                    if (table.startsWith("coupang_execution"))
                        assertThat(after.get(table)).as(table).isEqualTo(before.get(table));
                assertThat(after.get("coupang_submission_preview")).isEqualTo(before.get("coupang_submission_preview"));
                try (var r = s.executeQuery("SELECT document_json FROM coupang_product_working_copy WHERE id=12")) {
                    r.next();
                    assertThat(json.readTree(r.getString(1)).path("options").get(0).path("registration")
                            .path("salePrice").asString()).isEqualTo("130000");
                }
                System.setProperty("molebutter.intent.verified", "true");
                assertThat(Flyway.configure().dataSource(url, "test_migrator", "isolated-test-migration-password")
                        .target("48").load().migrate().migrationsExecuted).isEqualTo(1);
                assertThat(Flyway.configure().dataSource(url, "test_migrator", "isolated-test-migration-password")
                        .target("48").load().migrate().migrationsExecuted).isZero();
                assertThatThrownBy(() -> CoupangV47Preparation.finish(c, prepared, () -> null))
                        .hasMessageContaining("전환용 입력이 변경");
                CoupangV47Preparation.requireComplete(c);
            } finally {
                for (String property : List.of("molebutter.working-copy.verified", "molebutter.working-copy.account",
                        "molebutter.intent.verified"))
                    System.clearProperty(property);
            }
        }
    }
}
