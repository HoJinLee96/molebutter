package cc.ataglace.molebutter.marketplace.internal;

import cc.ataglace.molebutter.media.api.ImageAssets;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceDraftFailure.Kind.*;

/** Native SmartStore form persistence, with external writes delegated to the submission engine. */
@Service
public final class DefaultNaverProductRegistrations implements NaverProductRegistrations {
    private final BusinessAccess access;
    private final DraftStore store;
    private final SubmissionStore submissions;
    private final ImageAssets assets;
    private final MarketplaceEditing editing;
    private final MarketplaceSubmissions executions;
    private final NaverProductDocuments documents;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public DefaultNaverProductRegistrations(BusinessAccess access,DraftStore store,SubmissionStore submissions,
            ImageAssets assets,MarketplaceEditing editing,MarketplaceSubmissions executions,
            NaverProductDocuments documents,ObjectMapper json,PlatformTransactionManager manager){
        this.access=access;this.store=store;this.submissions=submissions;this.assets=assets;this.editing=editing;
        this.executions=executions;this.documents=documents;this.json=json;tx=new TransactionTemplate(manager);
    }
    @Override public Draft create(Long actor,NaverEditor.Input input){
        access.productActor(actor,true);var document=bounded(input);
        return tx.execute(t->{access.productActor(actor,true);var saved=store.insert(actor,BusinessIds.next(),document,null,null);
            store.markRegistration(Long.parseLong(saved.id()),documents.accountKey(),"NAVER_REGISTRATION");
            assets.replaceReferences(actor,saved.id(),MarketplaceDocuments.assetIds(saved));return response(saved);});
    }
    @Override public Draft get(Long actor,String id){access.productActor(actor,true);return tx.execute(t->response(require(actor,id,false)));}
    @Override public Draft save(Long actor,String id,Save input){
        access.productActor(actor,true);if(input==null||input.revision()==null)throw new InputValidationFailure("초안 버전을 확인해 주세요.");
        var document=bounded(input.input());
        return tx.execute(t->{access.productActor(actor,true);var before=require(actor,id,true);
            if(before.revision().longValue()!=input.revision())throw new MarketplaceDraftFailure(CONFLICT);writable(before);
            var saved=store.update(actor,Long.parseLong(id),input.revision(),document);assets.replaceReferences(actor,id,MarketplaceDocuments.assetIds(saved));return response(saved);});
    }
    @Override public MarketplaceSubmissions.Preview prepare(Long actor,String id,Prepare input){
        access.productActor(actor,true);if(input==null||input.revision()==null)throw new InputValidationFailure("초안 버전을 확인해 주세요.");
        tx.executeWithoutResult(t->{var d=require(actor,id,true);if(d.revision().longValue()!=input.revision())throw new MarketplaceDraftFailure(CONFLICT);writable(d);});
        var session=editing.start(actor,id);
        if(session.revision()!=input.revision()||session.targets().size()!=1||!"NAVER".equals(session.targets().getFirst().market())||!"CREATE".equals(session.targets().getFirst().mode()))throw new MarketplaceDraftFailure(CONFLICT);
        return executions.prepare(actor,new MarketplaceEditing.PrepareRequest(id,input.revision(),session.id(),false,List.of(new MarketplaceEditing.TargetChanges("NAVER",List.of()))));
    }
    private MarketplaceDrafts.Document require(Long actor,String id,boolean lock){
        long value=DefaultMarketplaceSubmissions.id(id);var d=store.find(value,lock);var meta=store.registration(value);
        if(!"NAVER_REGISTRATION".equals(meta.kind())||!Objects.equals(actor,meta.actor()))throw new MarketplaceDraftFailure(NOT_FOUND);
        if(!documents.accountKey().equals(meta.account()))throw new MarketplaceDraftFailure(CONFLICT);return d;
    }
    private void writable(MarketplaceDrafts.Document d){
        if(submissions.mapping(Long.parseLong(d.id()),"NAVER",documents.accountKey(),false)!=null||submissions.active(Long.parseLong(d.id()),documents.accountKey(),0))throw new MarketplaceDraftFailure(CONFLICT);
    }
    private Draft response(MarketplaceDrafts.Document d){
        var mapping=submissions.mapping(Long.parseLong(d.id()),"NAVER",documents.accountKey(),false);
        return new Draft(d.id(),d.revision(),documents.input(d),mapping==null?null:mapping.sellerProductId(),mapping!=null||submissions.active(Long.parseLong(d.id()),documents.accountKey(),0));
    }
    private MarketplaceDrafts.Document bounded(NaverEditor.Input input){
        var document=documents.document(null,null,documents.normalize(input));
        if(json.writeValueAsBytes(document).length>MarketplaceDocuments.MAX_BYTES)throw new InputValidationFailure("초안은 5MiB 이하로 입력해 주세요.");return document;
    }
}
