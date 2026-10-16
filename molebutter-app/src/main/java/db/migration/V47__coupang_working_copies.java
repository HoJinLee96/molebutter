package db.migration;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.api.migration.*;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.coupang.internal.LegacyCoupangInputs;
import cc.ataglace.molebutter.coupang.internal.CoupangSnapshots;
/**
 * Offline, lossless input migration. All ambiguous rows are checked before the
 * first DDL.
 */
public final class V47__coupang_working_copies extends BaseJavaMigration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Row(long id, String account, String product, String document, String changes, String mapping,
            String source) {
    }
    @Override
    public void migrate(Context context) throws Exception {
        var c = context.getConnection();
        var rows = preflight(c, System.getProperty("molebutter.working-copy.account"));
        if (!Boolean.getBoolean("molebutter.working-copy.verified")) {
            boolean populated = !rows.isEmpty();
            for (String table : List.of("coupang_execution", "coupang_asset", "coupang_order", "coupang_order_claim",
                    "coupang_listing_history", "user", "catalog_product", "inventory_item"))
                try (var st = c.createStatement(); var r = st.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
                    r.next();
                    populated |= r.getLong(1) > 0;
                }
            if (populated)
                throw new SQLException(
                        "V47 requires verified offline backup/restore; use upgrade-coupang-working-copies.sh");
        }
        try (var s = c.createStatement()) {
            s.execute("CREATE TABLE legacy_coupang_listing_state LIKE coupang_listing_state");
            s.execute("INSERT INTO legacy_coupang_listing_state SELECT * FROM coupang_listing_state");
            s.execute("CREATE TABLE legacy_coupang_working_copy_source LIKE coupang_draft");
            s.execute("INSERT INTO legacy_coupang_working_copy_source SELECT * FROM coupang_draft");
            s.execute(
                    "CREATE TABLE legacy_coupang_asset_pin(asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,CONSTRAINT fk_legacy_coupang_asset_pin FOREIGN KEY(asset_id) REFERENCES coupang_asset(id))");
            s.execute("INSERT INTO legacy_coupang_asset_pin SELECT id FROM coupang_asset");
            s.execute(
                    "RENAME TABLE coupang_draft TO coupang_product_working_copy,coupang_draft_asset TO coupang_working_copy_asset,coupang_listing_mapping TO legacy_coupang_listing_mapping,coupang_draft_target_edit TO legacy_coupang_target_edit,coupang_target_saved TO legacy_coupang_target_saved,coupang_comparison_resolution TO legacy_coupang_comparison_resolution,coupang_media_comparison TO legacy_coupang_media_comparison");
            s.execute(
                    "ALTER TABLE coupang_product_working_copy ADD COLUMN account_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,ADD COLUMN seller_product_id VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NULL,ADD COLUMN changes_json LONGTEXT NULL,ADD COLUMN mapping_json LONGTEXT NULL,ADD COLUMN editor_source_json LONGTEXT NULL,ADD COLUMN applied_revision BIGINT NULL");
        }
        try (var s = c.prepareStatement(
                "UPDATE coupang_product_working_copy SET account_key=?,seller_product_id=?,document_json=?,changes_json=?,mapping_json=?,editor_source_json=?,applied_revision=(SELECT applied_revision FROM legacy_coupang_listing_mapping WHERE draft_id=? LIMIT 1) WHERE id=?")) {
            for (var row : rows) {
                s.setString(1, row.account());
                s.setString(2, row.product());
                s.setString(3, row.document());
                s.setString(4, row.changes());
                s.setString(5, row.mapping());
                s.setString(6, row.source());
                s.setLong(7, row.id());
                s.setLong(8, row.id());
                s.addBatch();
            }
            s.executeBatch();
        }
        var columns = new ArrayList<String[]>();
        try (var s = c.createStatement();
                var r = s.executeQuery(
                        "SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND LEFT(table_name,8)='coupang_' AND column_name IN ('draft_id','draft_revision','previous_draft_id')")) {
            while (r.next())
                columns.add(new String[]{r.getString(1), r.getString(2)});
        }
        for (var column : columns)
            try (var s = c.createStatement()) {
                s.execute("ALTER TABLE `" + column[0] + "` RENAME COLUMN `" + column[1] + "` TO `"
                        + column[1].replace("draft_revision", "working_revision").replace("draft_id", "working_copy_id")
                        + "`");
            }
        try (var s = c.createStatement()) {
            s.execute(
                    "ALTER TABLE coupang_product_working_copy DROP COLUMN import_account,DROP COLUMN import_product_id,DROP COLUMN registration_account,DROP COLUMN editor_kind,DROP COLUMN product_code,MODIFY account_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,MODIFY changes_json LONGTEXT NOT NULL,ADD UNIQUE KEY uq_coupang_working_copy_product(account_key,seller_product_id)");
            s.execute("ALTER TABLE coupang_listing_state DROP COLUMN observed_json");
            s.executeUpdate("UPDATE coupang_edit_session SET revoked=TRUE");
            s.executeUpdate(
                    "UPDATE coupang_submission_preview p SET expires_at=CURRENT_TIMESTAMP(6)-INTERVAL 1 DAY WHERE NOT EXISTS(SELECT 1 FROM coupang_execution e WHERE e.preview_id=p.id)");
        }
        renameConstraints(c);
    }
    public static List<Long> unassigned(Connection c) throws SQLException {
        var out = new ArrayList<Long>();
        try (var s = c.createStatement();
                var r = s.executeQuery(
                        "SELECT d.id FROM coupang_draft d LEFT JOIN coupang_listing_mapping m ON m.draft_id=d.id WHERE m.account_key IS NULL AND d.import_account IS NULL AND d.registration_account IS NULL")) {
            while (r.next())
                out.add(r.getLong(1));
        }
        return out;
    }
    public static void check(Connection c, String account) throws Exception {
        preflight(c, account);
    }
    private static List<Row> preflight(Connection c, String configured) throws Exception {
        for (String table : List.of("coupang_execution", "coupang_execution_step", "coupang_order_job"))
            requireZero(c,
                    "SELECT COUNT(*) FROM `" + table + "`" + " WHERE status IN ('QUEUED','RUNNING','CANCELLING')",
                    "Active jobs must be settled before V47");
        for (String table : List.of("coupang_submission_account", "coupang_order_account_lock"))
            requireZero(c,
                    "SELECT COUNT(*) FROM `" + table + "`"
                            + " WHERE lease_owner IS NOT NULL AND lease_until>CURRENT_TIMESTAMP(6)",
                    "Active leases must stop before V47");
        requireZero(c,
                "SELECT COUNT(*) FROM (SELECT draft_id FROM coupang_listing_mapping GROUP BY draft_id HAVING COUNT(*)>1) x",
                "Multiple account mappings");
        var output = new ArrayList<Row>();
        var owners = new HashSet<String>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM coupang_draft ORDER BY id")) {
            while (r.next()) {
                long id = r.getLong("id");
                var original = JSON.readTree(r.getString("document_json"));
                var nativeDoc = CoupangWorkingCopyDocuments.legacy(JSON, original);
                String account = null, product = null, mapping = null, source = null;
                var accounts = new HashSet<String>();
                for (String col : List.of("import_account", "registration_account"))
                    if (r.getString(col) != null)
                        accounts.add(r.getString(col));
                try (var q = c.prepareStatement(
                        "SELECT account_key,external_product_id,mapping_json FROM coupang_listing_mapping WHERE draft_id=?")) {
                    q.setLong(1, id);
                    try (var m = q.executeQuery()) {
                        if (m.next()) {
                            accounts.add(m.getString(1));
                            product = m.getString(2);
                            mapping = m.getString(3);
                        }
                    }
                }
                if (product == null)
                    product = r.getString("import_product_id");
                var changes = List.<CoupangEditing.Change>of();
                boolean savedIntent = false;
                try (var q = c.prepareStatement(
                        "SELECT account_key,intent_json,mapping_json FROM coupang_draft_target_edit WHERE draft_id=?")) {
                    q.setLong(1, id);
                    try (var m = q.executeQuery()) {
                        if (m.next()) {
                            savedIntent = true;
                            accounts.add(m.getString(1));
                            var intent = JSON.readTree(m.getString(2));
                            nativeDoc = CoupangWorkingCopyDocuments.legacy(JSON, intent.path("observed"));
                            changes = LegacyCoupangInputs.intent(JSON, intent.path("observed"), intent.path("changes"));
                            nativeDoc = JSON
                                    .valueToTree(CoupangInputs.apply(JSON,
                                            JSON.treeToValue(nativeDoc, CoupangWorkingCopies.Document.class), changes))
                                    .asObject();
                            if (mapping == null && !JSON.readTree(m.getString(3)).isNull())
                                mapping = m.getString(3);
                            if (m.next())
                                throw new SQLException("Ambiguous saved input for working copy " + id);
                        }
                    }
                }
                if (accounts.size() > 1)
                    throw new SQLException("Account mismatch for working copy " + id);
                account = accounts.isEmpty() ? configured : accounts.iterator().next();
                if (account == null || !account.matches("[a-f0-9]{64}"))
                    throw new SQLException("Unassigned working copy " + id
                            + " requires offline account report and configured account");
                if (product != null && !owners.add(account + ":" + product))
                    throw new SQLException("Duplicate linked product for working copy " + id);
                var config = original.has("configuration")
                        ? original.path("configuration")
                        : original.path("markets").path("COUPANG");
                if (config.path("coupang").hasNonNull("source"))
                    source = JSON.writeValueAsString(config.path("coupang").path("source"));
                if (product != null && mapping == null) {
                    var cp = config.path("coupang");
                    if (!product.equals(cp.path("sellerProductId").asString()))
                        throw new SQLException("Missing option mapping for working copy " + id);
                    var m = JSON.createObjectNode();
                    m.put("accountKey", account);
                    m.put("sellerProductId", product);
                    var links = m.putArray("options");
                    for (var o : cp.path("options")) {
                        var link = links.addObject();
                        for (String key : List.of("optionId", "sellerProductItemId", "vendorItemId"))
                            link.set(key, o.path(key));
                    }
                    mapping = JSON.writeValueAsString(m);
                }
                if (mapping != null)
                    mapping = JSON.writeValueAsString(
                            CoupangSnapshots.read(JSON, mapping, CoupangWriteGateway.Mapping.class));
                if (product != null && !savedIntent) {
                    if (source == null)
                        throw new SQLException("Linked working copy " + id
                                + " lacks an authoritative Coupang observation; review conversion before V47");
                    var observed = JSON.readValue(source, CoupangEditor.EditorDocument.class);
                    var links = JSON.readValue(mapping, CoupangWriteGateway.Mapping.class);
                    var fresh = CoupangInputs.from(observed, links);
                    var previous = JSON.treeToValue(nativeDoc, CoupangWorkingCopies.Document.class);
                    var publications = new HashMap<String, String>();
                    for (String asset : CoupangWorkingCopyDocuments.assetIds(previous))
                        try (var q = c
                                .prepareStatement("SELECT token FROM coupang_asset_publication WHERE asset_id=?")) {
                            q.setString(1, asset);
                            try (var r2 = q.executeQuery()) {
                                if (r2.next())
                                    publications.put(asset, r2.getString(1));
                            }
                        }
                    var options = new ArrayList<CoupangWorkingCopies.Option>();
                    for (var o : fresh.options()) {
                        var prior = previous.options().stream().filter(v -> v.id().equals(o.id())).findFirst()
                                .orElseThrow();
                        var images = o.images().stream().map(image -> {
                            var old = prior.images().stream().filter(v -> Objects.equals(v.url(), image.url())
                                    || v.assetId() != null && publications.containsKey(v.assetId())
                                            && image.url().endsWith("/coupang/images/" + publications.get(v.assetId())))
                                    .findFirst().orElse(null);
                            return old == null
                                    ? image
                                    : new CoupangWorkingCopies.Image(old.id(),
                                            Objects.equals(old.url(), image.url()) || image.url()
                                                    .equals(System.getProperty("molebutter.working-copy.public-base",
                                                            "") + "/coupang/images/" + publications.get(old.assetId()))
                                                                    ? old.assetId()
                                                                    : null,
                                            image.url(), image.type(), image.order());
                        }).toList();
                        var contents = o.contents().stream().map(content -> {
                            var old = prior.contents().stream().filter(v -> Objects.equals(v.type(), content.type())
                                    && (Objects.equals(v.content(), content.content())
                                            || v.assetId() != null && publications.containsKey(v.assetId())
                                                    && content.content().endsWith(
                                                            "/coupang/images/" + publications.get(v.assetId()))))
                                    .findFirst().orElse(null);
                            return old == null
                                    ? content
                                    : new CoupangWorkingCopies.Content(old.id(), old.type(), old.detailType(),
                                            content.content(),
                                            Objects.equals(old.content(), content.content()) || content.content()
                                                    .equals(System.getProperty("molebutter.working-copy.public-base",
                                                            "") + "/coupang/images/" + publications.get(old.assetId()))
                                                                    ? old.assetId()
                                                                    : null);
                        }).toList();
                        options.add(new CoupangWorkingCopies.Option(o.id(), o.itemName(), o.registration(),
                                o.attributes(), images, contents, o.notices(), o.certifications(), o.noticeCategory()));
                    }
                    nativeDoc = JSON.valueToTree(new CoupangWorkingCopies.Document(fresh.basic(), options,
                            fresh.delivery(), fresh.settings(), fresh.documents())).asObject();
                }
                if (mapping != null) {
                    var m = JSON.readTree(mapping);
                    if (!account.equals(m.path("accountKey").asString())
                            || !Objects.equals(product, m.path("sellerProductId").asString(null)))
                        throw new SQLException("Mapping account/product mismatch for working copy " + id);
                    var optionIds = new HashSet<String>();
                    var remoteIds = new HashSet<String>();
                    for (var o : m.path("options")) {
                        if (!optionIds.add(o.path("optionId").asString())
                                || !remoteIds.add(o.path("sellerProductItemId").asString()))
                            throw new SQLException("Duplicate option mapping for working copy " + id);
                    }
                    var expected = new HashSet<String>();
                    for (var o : nativeDoc.path("options"))
                        expected.add(o.path("id").asString());
                    if (!optionIds.equals(expected))
                        throw new SQLException("Incomplete option mapping for working copy " + id);
                }
                var document = CoupangWorkingCopyDocuments
                        .normalize(JSON.treeToValue(nativeDoc, CoupangWorkingCopies.Document.class), JSON);
                output.add(new Row(id, account, product, JSON.writeValueAsString(document),
                        JSON.writeValueAsString(changes), mapping, source));
            }
        }
        return output;
    }
    private static void requireZero(Connection c, String query, String reason) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(query)) {
            r.next();
            if (r.getLong(1) > 0)
                throw new SQLException(reason);
        }
    }
    private static String quote(String value) {
        if (!value.matches("[A-Za-z0-9_]+"))
            throw new IllegalArgumentException();
        return "`" + value + "`";
    }
    private static void renameConstraints(Connection c) throws SQLException {
        record ForeignKey(String table, String name, String parent, String columns, String references,
                String deleteRule, String updateRule) {
        }
        var keys = new ArrayList<ForeignKey>();
        try (var st = c.createStatement();
                var rows = st.executeQuery(
                        "SELECT k.table_name,k.constraint_name,k.referenced_table_name,GROUP_CONCAT(CONCAT('`',k.column_name,'`') ORDER BY k.ordinal_position),GROUP_CONCAT(CONCAT('`',k.referenced_column_name,'`') ORDER BY k.ordinal_position),r.delete_rule,r.update_rule FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.table_name=k.table_name AND r.constraint_name=k.constraint_name WHERE k.table_schema=DATABASE() AND k.referenced_table_name IS NOT NULL AND k.constraint_name LIKE '%draft%' AND LEFT(k.table_name,8)='coupang_' GROUP BY k.table_name,k.constraint_name,k.referenced_table_name,r.delete_rule,r.update_rule")) {
            while (rows.next())
                keys.add(new ForeignKey(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                        rows.getString(5), rows.getString(6), rows.getString(7)));
        }
        for (var key : keys)
            try (var st = c.createStatement()) {
                st.execute("ALTER TABLE " + quote(key.table()) + " DROP FOREIGN KEY " + quote(key.name())
                        + ", ADD CONSTRAINT " + quote(key.name().replace("draft", "working_copy")) + " FOREIGN KEY ("
                        + key.columns() + ") REFERENCES " + quote(key.parent()) + " (" + key.references()
                        + ") ON DELETE " + key.deleteRule() + " ON UPDATE " + key.updateRule());
            }
        var indexes = new ArrayList<String[]>();
        try (var st = c.createStatement();
                var rows = st.executeQuery(
                        "SELECT DISTINCT table_name,index_name FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name LIKE '%draft%' AND LEFT(table_name,8)='coupang_'")) {
            while (rows.next())
                indexes.add(new String[]{rows.getString(1), rows.getString(2)});
        }
        for (var index : indexes)
            try (var st = c.createStatement()) {
                st.execute("ALTER TABLE " + quote(index[0]) + " RENAME INDEX " + quote(index[1]) + " TO "
                        + quote(index[1].replace("draft", "working_copy")));
            }
        var checks = new ArrayList<String[]>();
        try (var st = c.createStatement();
                var rows = st.executeQuery(
                        "SELECT t.table_name,t.constraint_name,k.check_clause FROM information_schema.table_constraints t JOIN information_schema.check_constraints k ON k.constraint_schema=t.constraint_schema AND k.constraint_name=t.constraint_name WHERE t.constraint_schema=DATABASE() AND t.constraint_type='CHECK' AND t.constraint_name LIKE '%draft%' AND LEFT(t.table_name,8)='coupang_'")) {
            while (rows.next())
                checks.add(new String[]{rows.getString(1), rows.getString(2), rows.getString(3)});
        }
        for (var check : checks)
            try (var st = c.createStatement()) {
                st.execute("ALTER TABLE " + quote(check[0]) + " DROP CHECK " + quote(check[1]) + ", ADD CONSTRAINT "
                        + quote(check[1].replace("draft", "working_copy")) + " CHECK (" + check[2] + ")");
            }

    }
}
