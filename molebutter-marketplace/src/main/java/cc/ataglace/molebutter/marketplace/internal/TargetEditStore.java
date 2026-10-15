package cc.ataglace.molebutter.marketplace.internal;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;

/** Trusted observations and pending edits live as long as their business draft. No remote I/O. */
@Repository
class TargetEditStore {
    record Saved(long revision,int version,MarketplaceWriteGateway.Mapping mapping,MarketplaceWriteGateway.EditIntent intent) {}
    private final JdbcTemplate db;private final ObjectMapper json;
    TargetEditStore(JdbcTemplate db,ObjectMapper json){this.db=db;this.json=json;}
    Saved find(long draft,String market,String account,boolean lock){
        var rows=db.query("SELECT draft_revision,snapshot_version,mapping_json,intent_json FROM marketplace_draft_target_edit WHERE draft_id=? AND market=? AND account_key=?"+(lock?" FOR UPDATE":""),
            (r,n)->new Saved(r.getLong(1),r.getInt(2),json.readValue(r.getString(3),MarketplaceWriteGateway.Mapping.class),json.readValue(r.getString(4),MarketplaceWriteGateway.EditIntent.class)),draft,market,account);
        return rows.isEmpty()?null:rows.getFirst();
    }
    void save(Long actor,long draft,long revision,String market,EditingStore.Binding binding,MarketplaceWriteGateway.EditIntent intent){
        if(intent.changes().isEmpty()){delete(draft,market,binding.account());return;}
        if(json.writeValueAsBytes(intent).length>15*1024*1024)throw new InputValidationFailure("임시 변경 정보가 너무 큽니다. 이미지·설명과 옵션을 확인해 주세요.");
        db.update("INSERT INTO marketplace_draft_target_edit(draft_id,market,account_key,draft_revision,snapshot_version,mapping_json,intent_json,updated_by,updated_at) VALUES(?,?,?,?,1,?,?,?,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE draft_revision=VALUES(draft_revision),snapshot_version=1,mapping_json=VALUES(mapping_json),intent_json=VALUES(intent_json),updated_by=VALUES(updated_by),updated_at=CURRENT_TIMESTAMP(6)",
            draft,market,binding.account(),revision,json.writeValueAsString(binding.mapping()),json.writeValueAsString(intent),actor);
    }
    void delete(long draft,String market,String account){db.update("DELETE FROM marketplace_draft_target_edit WHERE draft_id=? AND market=? AND account_key=?",draft,market,account);}
    Set<String> assetIds(long draft,List<MarketplaceProductAdapter> adapters){
        var ids=new HashSet<String>();
        db.query("SELECT market,snapshot_version,intent_json FROM marketplace_draft_target_edit WHERE draft_id=?",r->{
            if(r.getInt(2)!=1)throw new InputValidationFailure("보관된 변경의 버전을 확인해 주세요.");
            String market=r.getString(1);var adapter=adapters.stream().filter(a->a.market().equals(market)).findFirst().orElseThrow(()->new InputValidationFailure("보관된 변경의 마켓 설정을 확인해 주세요."));
            var intent=json.readValue(r.getString(3),MarketplaceWriteGateway.EditIntent.class);
            ids.addAll(MarketplaceDocuments.assetIds(adapter.apply(intent.observed(),intent.changes())));
        },draft);
        return ids;
    }
    record SavedTarget(MarketplaceWriteGateway.Mapping mapping,MarketplaceDrafts.Document document,List<MarketplaceEditing.ConflictKey> known,List<Proof> resolutions,String updatedAt) {}
    record Proof(String key,String action,String observedHash,String savedHash) {}
    record ResolutionRequest(long draft,String market,long generation,long revision,String hash) {}
    SavedTarget saved(long draft,String market,EditingStore.Binding binding,boolean lock){
        var rows=db.query("SELECT mapping_json,document_json,known_fields_json,resolution_json,updated_at FROM marketplace_target_saved WHERE draft_id=? AND market=? AND account_key=? AND listing_version=?"+(lock?" FOR UPDATE":""),(r,n)->new SavedTarget(r.getString(1)==null?null:json.readValue(r.getString(1),MarketplaceWriteGateway.Mapping.class),json.readValue(r.getString(2),MarketplaceDrafts.Document.class),json.readValue(r.getString(3),new tools.jackson.core.type.TypeReference<List<MarketplaceEditing.ConflictKey>>(){}),json.readValue(r.getString(4),new tools.jackson.core.type.TypeReference<List<Proof>>(){}),r.getTimestamp(5).toInstant().toString()),draft,market,binding.account(),binding.listingVersion());
        return rows.isEmpty()?null:rows.getFirst();
    }
    void saveTarget(Long actor,long draft,String market,EditingStore.Binding binding,SavedTarget value){
        if(json.writeValueAsBytes(value).length>20*1024*1024)throw new InputValidationFailure("앱 저장 이미지·설명 정보가 너무 큽니다.");
        db.update("INSERT INTO marketplace_target_saved(draft_id,market,account_key,listing_version,mapping_json,document_json,known_fields_json,resolution_json,updated_by,updated_at) VALUES(?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE mapping_json=VALUES(mapping_json),document_json=VALUES(document_json),known_fields_json=VALUES(known_fields_json),resolution_json=VALUES(resolution_json),updated_by=VALUES(updated_by),updated_at=CURRENT_TIMESTAMP(6)",draft,market,binding.account(),binding.listingVersion(),json.writeValueAsString(binding.mapping()),json.writeValueAsString(value.document()),json.writeValueAsString(value.known()),json.writeValueAsString(value.resolutions()),actor);
    }
    ResolutionRequest resolution(Long actor,String request){return resolution(actor,request,false);}
    ResolutionRequest resolution(Long actor,String request,boolean lock){var rows=db.query("SELECT draft_id,market,listing_version,revision,request_hash FROM marketplace_comparison_resolution WHERE created_by=? AND request_id=?"+(lock?" FOR UPDATE":""),(r,n)->new ResolutionRequest(r.getLong(1),r.getString(2),r.getLong(3),r.getLong(4),r.getString(5)),actor,request);return rows.isEmpty()?null:rows.getFirst();}
    void resolution(Long actor,String request,String hash,long draft,String market,long generation,long revision){db.update("INSERT INTO marketplace_comparison_resolution(created_by,request_id,request_hash,draft_id,market,listing_version,revision,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6))",actor,request,hash,draft,market,generation,revision);}
    Set<String> savedAssetIds(long draft){var ids=new HashSet<String>();for(String value:db.queryForList("SELECT document_json FROM marketplace_target_saved WHERE draft_id=?",String.class,draft))ids.addAll(MarketplaceDocuments.assetIds(json.readValue(value,MarketplaceDrafts.Document.class)));return ids;}
    void revision(long draft,long revision){db.update("UPDATE marketplace_draft_target_edit SET draft_revision=? WHERE draft_id=?",revision,draft);}
}
