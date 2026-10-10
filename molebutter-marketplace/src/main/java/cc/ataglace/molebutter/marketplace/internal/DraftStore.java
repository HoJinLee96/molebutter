package cc.ataglace.molebutter.marketplace.internal;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.PageResponse;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDraftFailure;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceDraftFailure.Kind.*;

@Repository
class DraftStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    DraftStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    Document find(long id, boolean lock) {
        var rows=jdbc.query("SELECT document_json,revision FROM marketplace_draft WHERE id=?"+(lock?" FOR UPDATE":""),
                (rs,n)->version(json.readValue(rs.getString(1),Document.class),Long.toString(id),rs.getLong(2)),id);
        if(rows.isEmpty())throw new MarketplaceDraftFailure(NOT_FOUND);
        return rows.getFirst();
    }
    Document imported(String account,String productId) {
        return imported("COUPANG",account,productId);
    }
    Document imported(String market,String account,String productId) {
        var rows=jdbc.query("SELECT id,revision,document_json FROM marketplace_draft WHERE import_market=? AND import_account=? AND import_product_id=?",
                (rs,n)->version(json.readValue(rs.getString(3),Document.class),rs.getString(1),rs.getLong(2)),market,account,productId);
        return rows.isEmpty()?null:rows.getFirst();
    }
    Document insert(Long actor,long id,Document input,String account,String productId) {
        return insert(actor,id,input,account==null?null:"COUPANG",account,productId);
    }
    Document insert(Long actor,long id,Document input,String market,String account,String productId) {
        var doc=version(input,Long.toString(id),0L);var at=LocalDateTime.now();
        jdbc.update("INSERT INTO marketplace_draft(id,revision,product_code,product_name,document_json,import_market,import_account,import_product_id,created_by,updated_by,created_at,updated_at) VALUES(?,0,?,?,?,?,?,?,?,?,?,?)",
                id,doc.common().productCode(),doc.common().name(),json.writeValueAsString(doc),market,account,productId,actor,actor,Timestamp.valueOf(at),Timestamp.valueOf(at));
        return doc;
    }
    Document update(Long actor,long id,long revision,Document input) {
        var doc=version(input,Long.toString(id),revision+1);
        if(jdbc.update("UPDATE marketplace_draft SET revision=?,product_code=?,product_name=?,document_json=?,updated_by=?,updated_at=? WHERE id=? AND revision=?",
                revision+1,doc.common().productCode(),doc.common().name(),json.writeValueAsString(doc),actor,Timestamp.valueOf(LocalDateTime.now()),id,revision)!=1)throw new MarketplaceDraftFailure(CONFLICT);
        return doc;
    }
    PageResponse<Summary> list(String search,int page,int size) {
        String escaped=search.replace("!","!!").replace("%","!%").replace("_","!_");
        String where=search.isEmpty()?"":" WHERE product_code LIKE ? ESCAPE '!' OR product_name LIKE ? ESCAPE '!'";
        Object[] params=search.isEmpty()?new Object[0]:new Object[]{"%"+escaped+"%","%"+escaped+"%"};
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM marketplace_draft"+where,Long.class,params);
        var args=new java.util.ArrayList<Object>(List.of(params));args.add(size);args.add((long)page*size);
        var rows=jdbc.query("SELECT id,revision,product_code,product_name,document_json,import_market,updated_at,editor_kind,import_product_id FROM marketplace_draft"+where+" ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs,n)->new Summary(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getString(4),json.readValue(rs.getString(5),Document.class).selectedMarkets(),rs.getString(6)!=null,rs.getTimestamp(7).toLocalDateTime().toString(),rs.getString(8),rs.getString(6),rs.getString(9)),args.toArray());
        return new PageResponse<>(rows,page,(int)Math.ceil((double)count/size),count);
    }
    record Registration(Long actor,String account,String kind) {}
    Registration registration(long id){
        var rows=jdbc.query("SELECT created_by,registration_account,editor_kind FROM marketplace_draft WHERE id=?",(rs,n)->new Registration(rs.getLong(1),rs.getString(2),rs.getString(3)),id);
        if(rows.isEmpty())throw new MarketplaceDraftFailure(NOT_FOUND);return rows.getFirst();
    }
    void markRegistration(long id,String account){markRegistration(id,account,"COUPANG_REGISTRATION");}
    void markRegistration(long id,String account,String kind){jdbc.update("UPDATE marketplace_draft SET editor_kind=?,registration_account=? WHERE id=?",kind,account,id);}
    static Document version(Document d,String id,Long revision) {
        return new Document(id,revision,d.common(),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),d.markets());
    }
}
