package cc.ataglace.molebutter.coupang.internal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.coupang.api.CoupangWorkingCopies.*;
@Repository
class WorkingCopyStore {
    record Copy(String id, long revision, String account, String product, Document document,
            List<CoupangEditing.Change> changes, CoupangWriteGateway.Mapping mapping,
            CoupangEditor.EditorDocument observation, Long appliedRevision) {
    }
    private final JdbcTemplate db;
    private final ObjectMapper json;
    WorkingCopyStore(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }
    Copy find(long id, boolean lock) {
        var rows = db.query(
                "SELECT id,revision,account_key,seller_product_id,document_json,changes_json,mapping_json,editor_source_json,applied_revision FROM coupang_product_working_copy WHERE id=?"
                        + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new Copy(r.getString(1), r.getLong(2), r.getString(3), r.getString(4),
                        json.readValue(r.getString(5), Document.class), json.readValue(r.getString(6),
                                new tools.jackson.core.type.TypeReference<List<CoupangEditing.Change>>() {
                                }),
                        r.getString(7) == null
                                ? null
                                : json.readValue(r.getString(7), CoupangWriteGateway.Mapping.class),
                        r.getString(8) == null
                                ? null
                                : json.readValue(r.getString(8), CoupangEditor.EditorDocument.class),
                        r.getObject(9, Long.class)),
                id);
        if (rows.isEmpty())
            throw new CoupangEditingFailure(CoupangEditingFailure.Kind.NOT_FOUND);
        return rows.getFirst();
    }
    Copy product(String account, String product) {
        var ids = db.queryForList(
                "SELECT id FROM coupang_product_working_copy WHERE account_key=? AND seller_product_id=?", Long.class,
                account, product);
        return ids.isEmpty() ? null : find(ids.getFirst(), false);
    }
    Copy insert(Long actor, String account, String product, Document document, List<CoupangEditing.Change> changes,
            CoupangWriteGateway.Mapping mapping, CoupangEditor.EditorDocument observation) {
        long id = BusinessIds.next();
        db.update(
                "INSERT INTO coupang_product_working_copy(id,revision,product_name,document_json,account_key,seller_product_id,changes_json,mapping_json,editor_source_json,created_by,updated_by,created_at,updated_at) VALUES(?,0,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                id, CoupangWorkingCopyDocuments.name(document), json.writeValueAsString(document), account, product,
                json.writeValueAsString(changes), mapping == null ? null : json.writeValueAsString(mapping),
                observation == null ? null : json.writeValueAsString(observation), actor, actor);
        return find(id, false);
    }
    Copy update(Long actor, Copy previous, Document document, List<CoupangEditing.Change> changes,
            CoupangEditor.EditorDocument observation) {
        if (db.update(
                "UPDATE coupang_product_working_copy SET revision=revision+1,product_name=?,document_json=?,changes_json=?,editor_source_json=COALESCE(?,editor_source_json),updated_by=?,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND revision=?",
                CoupangWorkingCopyDocuments.name(document), json.writeValueAsString(document),
                json.writeValueAsString(changes), observation == null ? null : json.writeValueAsString(observation),
                actor, Long.parseLong(previous.id()), previous.revision()) != 1)
            throw new CoupangEditingFailure(CoupangEditingFailure.Kind.CONFLICT);
        return find(Long.parseLong(previous.id()), false);
    }
    Map<String, String> publications(Set<String> ids) {
        var out = new HashMap<String, String>();
        for (String id : ids) {
            var tokens = db.queryForList("SELECT token FROM coupang_asset_publication WHERE asset_id=?", String.class,
                    id);
            if (!tokens.isEmpty())
                out.put(id, tokens.getFirst());
        }
        return out;
    }
    PageResponse<Summary> list(String account, String query, String mode, int page, int size) {
        String pattern = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        String filter=mode==null?"":mode.equals("CREATE")?" AND seller_product_id IS NULL":" AND seller_product_id IS NOT NULL";
        long count = db.queryForObject(
                "SELECT COUNT(*) FROM coupang_product_working_copy WHERE account_key=? AND product_name LIKE ? ESCAPE '!'"+filter,
                Long.class, account, pattern);
        var rows = db.query(
                "SELECT w.id,w.revision,w.product_name,w.seller_product_id,w.updated_at,EXISTS(SELECT 1 FROM coupang_execution e JOIN coupang_execution_step s ON s.execution_id=e.id WHERE e.working_copy_id=w.id AND s.status IN ('QUEUED','RUNNING','UNKNOWN','ACCEPTED')) FROM coupang_product_working_copy w WHERE w.account_key=? AND w.product_name LIKE ? ESCAPE '!'"+filter+" ORDER BY w.updated_at DESC,w.id DESC LIMIT ? OFFSET ?",
                (r, n) -> new Summary(r.getString(1), r.getLong(2), r.getString(3), r.getString(4),
                        r.getString(4) == null ? "CREATE" : "UPDATE", r.getTimestamp(5).toInstant().toString(),
                        r.getBoolean(6)),
                account, pattern, size, (long) page * size);
        return new PageResponse<>(rows, page, (int) Math.ceil((double) count / size), count);
    }
}
