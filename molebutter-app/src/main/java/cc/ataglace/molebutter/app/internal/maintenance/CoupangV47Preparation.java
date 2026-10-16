package cc.ataglace.molebutter.app.internal.maintenance;

import java.sql.*;
import java.util.*;
import java.util.concurrent.Callable;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.coupang.api.*;
import db.migration.V47__coupang_working_copies;

/**
 * Offline bridge into the immutable V47 saved-observation reader; never creates
 * user changes.
 */
final class CoupangV47Preparation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MARKER = "media-identity-v1";
    record Observation(long id, String account, long revision, long actor, String mapping, String intent) {
    }
    private CoupangV47Preparation() {
    }
    static void requireComplete(Connection c) throws Exception {
        for (String table : List.of("coupang_draft_target_edit", "legacy_coupang_target_edit")) {
            try (var q = c.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?")) {
                q.setString(1, table);
                try (var r = q.executeQuery()) {
                    r.next();
                    if (r.getInt(1) == 0)
                        continue;
                }
            }
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT intent_json FROM " + table)) {
                while (r.next())
                    if (MARKER.equals(JSON.readTree(r.getString(1)).path("v47Preparation").asString()))
                        throw new IllegalStateException(
                                "완료되지 않은 V47 전환용 관측 입력이 남아 있습니다. 재기동하지 말고 이전 전체 DB·파일 백업을 복원하세요.");
            }
        }
    }
    static List<Observation> prepare(Connection c, String configured, String publicBase) throws Exception {
        if (!Boolean.getBoolean("molebutter.working-copy.verified"))
            throw new IllegalStateException("V47 관측 입력 준비 전에 전체 백업·격리 복원을 확인하세요.");
        requireComplete(c);
        var prepared = new ArrayList<Observation>();
        try (var s = c.createStatement(); var rows = s.executeQuery("SELECT * FROM coupang_draft ORDER BY id")) {
            while (rows.next()) {
                long id = rows.getLong("id");
                try (var q = c.prepareStatement("SELECT COUNT(*) FROM coupang_draft_target_edit WHERE draft_id=?")) {
                    q.setLong(1, id);
                    try (var r = q.executeQuery()) {
                        r.next();
                        if (r.getInt(1) != 0)
                            continue;
                    }
                }
                var accounts = new HashSet<String>();
                for (String column : List.of("import_account", "registration_account"))
                    if (rows.getString(column) != null)
                        accounts.add(rows.getString(column));
                String product = rows.getString("import_product_id"), mapping = null;
                try (var q = c.prepareStatement(
                        "SELECT account_key,external_product_id,mapping_json FROM coupang_listing_mapping WHERE draft_id=?")) {
                    q.setLong(1, id);
                    try (var r = q.executeQuery()) {
                        if (r.next()) {
                            accounts.add(r.getString(1));
                            product = r.getString(2);
                            mapping = r.getString(3);
                            if (r.next())
                                throw new SQLException("Multiple account mappings for working copy " + id);
                        }
                    }
                }
                if (product == null)
                    continue;
                if (accounts.size() > 1)
                    throw new SQLException("Account mismatch for working copy " + id);
                String account = accounts.isEmpty() ? configured : accounts.iterator().next();
                if (account == null || !account.matches("[a-f0-9]{64}"))
                    throw new SQLException("Unassigned working copy " + id);
                var original = JSON.readTree(rows.getString("document_json"));
                var config = original.has("configuration")
                        ? original.path("configuration")
                        : original.path("markets").path("COUPANG");
                var cp = config.path("coupang");
                if (!cp.hasNonNull("source"))
                    throw new SQLException("Linked working copy " + id + " lacks an authoritative Coupang observation");
                if (mapping == null) {
                    if (!product.equals(cp.path("sellerProductId").asString()))
                        throw new SQLException("Missing option mapping for working copy " + id);
                    var m = JSON.createObjectNode().put("accountKey", account).put("sellerProductId", product);
                    var options = m.putArray("options");
                    for (var option : cp.path("options")) {
                        var link = options.addObject();
                        for (String key : List.of("optionId", "sellerProductItemId", "vendorItemId"))
                            link.set(key, option.path(key));
                    }
                    mapping = JSON.writeValueAsString(m);
                }
                // The unchanged V47 preflight validates the original historical mapping before
                // commit.
                var storedMapping = JSON.readTree(mapping);
                var optionLinks = new ArrayList<CoupangWriteGateway.OptionMapping>();
                for (var link : storedMapping.path("options"))
                    optionLinks.add(new CoupangWriteGateway.OptionMapping(link.path("optionId").asString(null),
                            link.path("sellerProductItemId").asString(null), link.path("vendorItemId").asString(null)));
                var links = new CoupangWriteGateway.Mapping(storedMapping.path("accountKey").asString(null),
                        storedMapping.path("sellerProductId").asString(null), List.copyOf(optionLinks));
                if (!account.equals(links.accountKey()) || !product.equals(links.sellerProductId()))
                    throw new SQLException("Mapping account/product mismatch for working copy " + id);
                var previous = CoupangWorkingCopyDocuments.normalize(JSON.treeToValue(
                        CoupangWorkingCopyDocuments.legacy(JSON, original), CoupangWorkingCopies.Document.class), JSON);
                if (!previous.options().stream().map(CoupangWorkingCopies.Option::id)
                        .collect(java.util.stream.Collectors.toSet())
                        .equals(links.options().stream().map(CoupangWriteGateway.OptionMapping::optionId)
                                .collect(java.util.stream.Collectors.toSet())))
                    throw new SQLException("Incomplete option mapping for working copy " + id);
                var tokens = new HashMap<String, String>();
                var publicUrls = new HashMap<String, String>();
                for (String asset : CoupangWorkingCopyDocuments.assetIds(previous))
                    try (var q = c.prepareStatement("SELECT token FROM coupang_asset_publication WHERE asset_id=?")) {
                        q.setString(1, asset);
                        try (var r = q.executeQuery()) {
                            if (r.next()) {
                                tokens.put(asset, r.getString(1));
                                publicUrls.put(asset,
                                        publicBase.replaceFirst("/+$", "") + "/coupang/images/" + r.getString(1));
                            }
                        }
                    }
                var fresh = CoupangInputs.from(JSON.treeToValue(cp.path("source"), CoupangEditor.EditorDocument.class),
                        links);
                var document = CoupangWorkingCopyDocuments
                        .normalize(CoupangInputs.preserveMedia(fresh, previous, tokens, publicUrls), JSON);
                var intent = JSON.createObjectNode().put("v47Preparation", MARKER);
                intent.set("observed", JSON.valueToTree(document));
                intent.putArray("changes");
                prepared.add(new Observation(id, account, rows.getLong("revision"), rows.getLong("updated_by"),
                        JSON.writeValueAsString(links), JSON.writeValueAsString(intent)));
            }
        }
        return transaction(c, () -> {
            try (var q = c.prepareStatement(
                    "INSERT INTO coupang_draft_target_edit(draft_id,account_key,draft_revision,mapping_json,intent_json,updated_by,updated_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP(6))")) {
                for (var row : prepared) {
                    q.setLong(1, row.id());
                    q.setString(2, row.account());
                    q.setLong(3, row.revision());
                    q.setString(4, row.mapping());
                    q.setString(5, row.intent());
                    q.setLong(6, row.actor());
                    q.addBatch();
                }
                q.executeBatch();
            }
            V47__coupang_working_copies.check(c, configured);
            return List.copyOf(prepared);
        });
    }
    static void finish(Connection c, List<Observation> prepared, Callable<Void> verification) throws Exception {
        transaction(c, () -> {
            try (var q = c.prepareStatement(
                    "DELETE FROM legacy_coupang_target_edit WHERE draft_id=? AND account_key=? AND draft_revision=? AND CAST(mapping_json AS BINARY)=CAST(? AS BINARY) AND CAST(intent_json AS BINARY)=CAST(? AS BINARY) AND updated_by=?")) {
                for (var row : prepared) {
                    q.setLong(1, row.id());
                    q.setString(2, row.account());
                    q.setLong(3, row.revision());
                    q.setString(4, row.mapping());
                    q.setString(5, row.intent());
                    q.setLong(6, row.actor());
                    if (q.executeUpdate() != 1)
                        throw new IllegalStateException("V47 전환용 입력이 변경되었습니다. 재기동하지 말고 전체 백업을 복원하세요.");
                }
                requireComplete(c);
                verification.call();
                return null;
            }
        });
    }
    private static <T> T transaction(Connection c, Callable<T> work) throws Exception {
        if (!c.getAutoCommit())
            throw new IllegalStateException("V47 유지보수에는 독립된 연결이 필요합니다.");
        c.setAutoCommit(false);
        boolean settled = false;
        try {
            T result = work.call();
            c.commit();
            settled = true;
            return result;
        } catch (Exception failure) {
            try {
                c.rollback();
                settled = true;
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            if (settled)
                c.setAutoCommit(true);
        }
    }

}
