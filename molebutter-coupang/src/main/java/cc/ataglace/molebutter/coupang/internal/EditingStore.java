package cc.ataglace.molebutter.coupang.internal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.coupang.api.*;
import static cc.ataglace.molebutter.coupang.api.CoupangEditingFailure.Kind.*;
@Repository
class EditingStore {
    record Binding(String account, CoupangWriteGateway.Mapping mapping, Long listingVersion) {
        Binding {
            listingVersion = listingVersion == null ? 1L : listingVersion;
        }
    }
    record Stored(Long actor, Binding binding, CoupangEditing.Session session, boolean revoked) {
    }
    private final JdbcTemplate db;
    private final ObjectMapper json;
    EditingStore(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }
    Stored find(long id, boolean lock) {
        var rows = db.query(
                "SELECT created_by,binding_json,session_json,revoked FROM coupang_edit_session WHERE revoked=FALSE AND id=?"
                        + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new Stored(r.getLong(1),
                        r.getString(2) == null ? null : json.readValue(r.getString(2), Binding.class),
                        json.readValue(r.getString(3), CoupangEditing.Session.class), r.getBoolean(4)),
                id);
        if (rows.isEmpty())
            throw new CoupangEditingFailure(NOT_FOUND);
        return rows.getFirst();
    }
    void insert(Long actor, Binding binding, CoupangEditing.Session session) {
        db.update(
                "INSERT INTO coupang_edit_session(id,working_copy_id,working_revision,created_by,binding_json,session_json,created_at,expires_at) VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP(6),?)",
                Long.parseLong(session.id()), Long.parseLong(session.workingCopyId()), session.revision(), actor,
                json.writeValueAsString(binding), json.writeValueAsString(session),
                Timestamp.from(Instant.parse(session.expiresAt())));
    }
    void update(CoupangEditing.Session session) {
        db.update("UPDATE coupang_edit_session SET working_revision=?,session_json=? WHERE id=? AND revoked=FALSE",
                session.revision(), json.writeValueAsString(session), Long.parseLong(session.id()));
    }
    void cleanup() {
        db.update("DELETE FROM coupang_edit_session WHERE expires_at<CURRENT_TIMESTAMP(6)-INTERVAL 24 HOUR");
    }
}
