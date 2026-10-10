package cc.ataglace.molebutter.marketplace.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceEditingFailure.Kind.*;

@Repository
class EditingStore {
    record Stored(Long actor, String account, MarketplaceWriteGateway.Mapping mapping,
                  MarketplaceEditing.Session session, boolean revoked) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    EditingStore(JdbcTemplate db,ObjectMapper json) { this.db=db; this.json=json; }
    Stored find(long id,boolean lock) {
        var rows=db.query("SELECT created_by,account_key,mapping_json,session_json,revoked FROM marketplace_edit_session WHERE id=?"+(lock?" FOR UPDATE":""),
            (r,n)->new Stored(r.getLong(1),r.getString(2),r.getString(3)==null?null:json.readValue(r.getString(3),MarketplaceWriteGateway.Mapping.class),json.readValue(r.getString(4),MarketplaceEditing.Session.class),r.getBoolean(5)),id);
        if(rows.isEmpty())throw new MarketplaceEditingFailure(NOT_FOUND);
        return rows.getFirst();
    }
    void insert(Long actor,String account,MarketplaceWriteGateway.Mapping mapping,MarketplaceEditing.Session session) {
        db.update("INSERT INTO marketplace_edit_session(id,draft_id,draft_revision,created_by,account_key,mapping_json,session_json,created_at,expires_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),?)",
            Long.parseLong(session.id()),Long.parseLong(session.draftId()),session.revision(),actor,account,mapping==null?null:json.writeValueAsString(mapping),json.writeValueAsString(session),Timestamp.from(Instant.parse(session.expiresAt())));
    }
    void update(MarketplaceEditing.Session session) {
        db.update("UPDATE marketplace_edit_session SET draft_revision=?,session_json=? WHERE id=? AND revoked=FALSE",session.revision(),json.writeValueAsString(session),Long.parseLong(session.id()));
    }
    void revoke(String id) { db.update("UPDATE marketplace_edit_session SET revoked=TRUE WHERE id=?",Long.parseLong(id)); }
    void cleanup() {
        // Executed previews retain their immutable intent separately; no observation is needed after expiry.
        db.update("DELETE FROM marketplace_edit_session WHERE expires_at<CURRENT_TIMESTAMP(6)-INTERVAL 24 HOUR");
    }
}
