package cc.ataglace.molebutter.marketplace.internal;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceEditingFailure.Kind.*;

@Repository
class EditingStore {
    record Binding(String account,MarketplaceWriteGateway.Mapping mapping,Long listingVersion) {
        Binding { listingVersion=listingVersion==null?1L:listingVersion; }
        Binding(String account,MarketplaceWriteGateway.Mapping mapping){this(account,mapping,1L);}
    }
    record Bindings(Map<String,Binding> targets) {}
    record Stored(Long actor, String account, MarketplaceWriteGateway.Mapping mapping,
                  MarketplaceEditing.Session session, boolean revoked,Map<String,Binding> bindings) {
        Stored(Long actor,String account,MarketplaceWriteGateway.Mapping mapping,MarketplaceEditing.Session session,boolean revoked) { this(actor,account,mapping,session,revoked,null); }
        Binding binding(String market) {
            if(bindings!=null)return bindings.get(market);
            // Only legacy single-provider observations may use the historical columns.
            var ready=session.targets().stream().filter(t->"READY".equals(t.status())).toList();
            String legacy=ready.size()==1?ready.getFirst().market():"COUPANG";
            return Objects.equals(legacy,market)&&account!=null?new Binding(account,mapping):null;
        }
    }
    private final JdbcTemplate db;
    private final ObjectMapper json;
    EditingStore(JdbcTemplate db,ObjectMapper json) { this.db=db; this.json=json; }
    Stored find(long id,boolean lock) {
        var rows=db.query("SELECT created_by,account_key,mapping_json,session_json,revoked,target_bindings_json FROM marketplace_edit_session WHERE id=?"+(lock?" FOR UPDATE":""),
            (r,n)->new Stored(r.getLong(1),r.getString(2),r.getString(3)==null?null:json.readValue(r.getString(3),MarketplaceWriteGateway.Mapping.class),json.readValue(r.getString(4),MarketplaceEditing.Session.class),r.getBoolean(5),r.getString(6)==null?null:json.readValue(r.getString(6),Bindings.class).targets()),id);
        if(rows.isEmpty())throw new MarketplaceEditingFailure(NOT_FOUND);
        return rows.getFirst();
    }
    void insert(Long actor,String account,MarketplaceWriteGateway.Mapping mapping,MarketplaceEditing.Session session) {
        db.update("INSERT INTO marketplace_edit_session(id,draft_id,draft_revision,created_by,account_key,mapping_json,session_json,created_at,expires_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),?)",
            Long.parseLong(session.id()),Long.parseLong(session.draftId()),session.revision(),actor,account,mapping==null?null:json.writeValueAsString(mapping),json.writeValueAsString(session),Timestamp.from(Instant.parse(session.expiresAt())));
    }
    void insert(Long actor,Map<String,Binding> bindings,MarketplaceEditing.Session session) {
        db.update("INSERT INTO marketplace_edit_session(id,draft_id,draft_revision,created_by,account_key,mapping_json,session_json,target_bindings_json,created_at,expires_at) VALUES(?,?,?,?,NULL,NULL,?,?,CURRENT_TIMESTAMP(6),?)",
            Long.parseLong(session.id()),Long.parseLong(session.draftId()),session.revision(),actor,json.writeValueAsString(session),json.writeValueAsString(new Bindings(Map.copyOf(bindings))),Timestamp.from(Instant.parse(session.expiresAt())));
    }
    void update(MarketplaceEditing.Session session) {
        db.update("UPDATE marketplace_edit_session SET draft_revision=?,session_json=? WHERE id=? AND revoked=FALSE",session.revision(),json.writeValueAsString(session),Long.parseLong(session.id()));
    }
    void update(MarketplaceEditing.Session session,Map<String,Binding> bindings) {
        db.update("UPDATE marketplace_edit_session SET draft_revision=?,session_json=?,target_bindings_json=? WHERE id=? AND revoked=FALSE",session.revision(),json.writeValueAsString(session),json.writeValueAsString(new Bindings(bindings)),Long.parseLong(session.id()));
    }
    void revoke(String id) { db.update("UPDATE marketplace_edit_session SET revoked=TRUE WHERE id=?",Long.parseLong(id)); }
    void revokeDraft(long id){db.update("UPDATE marketplace_edit_session SET revoked=TRUE WHERE draft_id=?",id);}
    void cleanup() {
        // Executed previews retain their immutable intent separately; no observation is needed after expiry.
        db.update("DELETE FROM marketplace_edit_session WHERE expires_at<CURRENT_TIMESTAMP(6)-INTERVAL 24 HOUR");
    }
}
