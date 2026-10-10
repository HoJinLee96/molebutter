package cc.ataglace.molebutter.marketplace.internal;

import cc.ataglace.molebutter.media.api.ImageAssets;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceDraftFailure.Kind.*;

@Service
public final class DefaultCoupangProductRegistrations implements CoupangProductRegistrations {
    private final BusinessAccess access;private final DraftStore store;private final SubmissionStore submissions;private final ImageAssets assets;private final MarketplaceDrafts drafts;private final MarketplaceEditing editing;private final MarketplaceSubmissions executions;private final CoupangBrands brands;private final CoupangProductDocuments documents;private final ObjectMapper json;private final TransactionTemplate tx;private final String account;
    public DefaultCoupangProductRegistrations(BusinessAccess access,DraftStore store,SubmissionStore submissions,ImageAssets assets,MarketplaceDrafts drafts,MarketplaceEditing editing,MarketplaceSubmissions executions,CoupangBrands brands,CoupangProductDocuments documents,ObjectMapper json,PlatformTransactionManager manager,@Value("${marketplace.coupang.vendor-id:}") String vendor){
        this.access=access;this.store=store;this.submissions=submissions;this.assets=assets;this.drafts=drafts;this.editing=editing;this.executions=executions;this.brands=brands;this.documents=documents;this.json=json;tx=new TransactionTemplate(manager);account=DefaultMarketplaceSubmissions.account(vendor.trim());
    }
    public Draft create(Long actor,Input input){access.productActor(actor,true);var document=bounded(input);return tx.execute(t->{access.productActor(actor,true);var saved=store.insert(actor,BusinessIds.next(),document,null,null);store.markRegistration(Long.parseLong(saved.id()),account);assets.replaceReferences(actor,saved.id(),MarketplaceDocuments.assetIds(saved));return response(saved);});}
    public Draft get(Long actor,String id){access.productActor(actor,true);return tx.execute(t->response(require(actor,id,false)));}
    public Draft save(Long actor,String id,Save input){access.productActor(actor,true);if(input==null||input.revision()==null)throw new InputValidationFailure("초안 버전을 확인해 주세요.");var document=bounded(input.input());return tx.execute(t->{var before=require(actor,id,true);if(before.revision().longValue()!=input.revision().longValue())throw new MarketplaceDraftFailure(CONFLICT);writable(before);var saved=store.update(actor,Long.parseLong(id),input.revision(),document);assets.replaceReferences(actor,id,MarketplaceDocuments.assetIds(saved));return response(saved);});}
    public MarketplaceSubmissions.Preview prepare(Long actor,String id,Prepare input){
        access.productActor(actor,true);if(input==null||input.revision()==null)throw new InputValidationFailure("초안 버전을 확인해 주세요.");
        var document=tx.execute(t->{var d=require(actor,id,true);if(d.revision().longValue()!=input.revision().longValue())throw new MarketplaceDraftFailure(CONFLICT);writable(d);return d;});
        var m=document.markets().get(MarketplaceDrafts.Market.COUPANG);
        brands.requireSelection(actor,m.coupang().settings().stream().filter(f->"brandId".equals(f.name())).map(CoupangCatalog.Field::value).findFirst().orElse(null),document.common().brand());
        var session=editing.start(actor,id);if(session.revision()!=input.revision()||session.targets().stream().anyMatch(t->!"CREATE".equals(t.mode())))throw new MarketplaceDraftFailure(CONFLICT);
        return executions.prepare(actor,new MarketplaceEditing.PrepareRequest(id,input.revision(),session.id(),input.requested(),List.of(new MarketplaceEditing.TargetChanges("COUPANG",List.of()))));
    }
    private MarketplaceDrafts.Document require(Long actor,String id,boolean lock){
        long value=id(id);var document=store.find(value,lock);var meta=store.registration(value);
        if(!"COUPANG_REGISTRATION".equals(meta.kind())||!Objects.equals(actor,meta.actor()))throw new MarketplaceDraftFailure(NOT_FOUND);
        if(!account.equals(meta.account()))throw new MarketplaceDraftFailure(CONFLICT);return document;
    }
    private void writable(MarketplaceDrafts.Document d){if(submissions.mapping(Long.parseLong(d.id()),account)!=null||submissions.active(Long.parseLong(d.id()),account,0))throw new MarketplaceDraftFailure(CONFLICT);}
    private Draft response(MarketplaceDrafts.Document d){var mapping=submissions.mapping(Long.parseLong(d.id()),account);return new Draft(d.id(),d.revision(),documents.registrationInput(d),mapping==null?null:mapping.sellerProductId(),mapping!=null||submissions.active(Long.parseLong(d.id()),account,0));}
    private MarketplaceDrafts.Document bounded(Input input){var d=documents.registration(input);if(json.writeValueAsBytes(d).length>MarketplaceDocuments.MAX_BYTES)throw new InputValidationFailure("초안은 5MiB 이하로 입력해 주세요.");return d;}
    private static long id(String value){try{long id=Long.parseLong(value);if(id<=0||!Long.toString(id).equals(value))throw new NumberFormatException();return id;}catch(RuntimeException e){throw new InputValidationFailure("초안 ID를 확인해 주세요.");}}
}
