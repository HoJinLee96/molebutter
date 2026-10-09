package cc.ataglace.molebutter.marketplace.internal;

import cc.ataglace.molebutter.media.api.ImageAssets;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceDraftFailure.Kind.*;

@Service
public final class DefaultMarketplaceDrafts implements MarketplaceDrafts {
    private final BusinessAccess access;
    private final DraftStore store;
    private final DraftValidation validation;
    private final ImageAssets assets;
    private final CoupangEditor coupang;
    private final CoupangProductDocuments documents;
    private final List<MarketplaceProductAdapter> adapters;
    private final SubmissionStore submissions;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final String importAccount;
    public DefaultMarketplaceDrafts(BusinessAccess access,DraftStore store,DraftValidation validation,
            ImageAssets assets,CoupangEditor coupang,CoupangProductDocuments documents,List<MarketplaceProductAdapter> adapters,SubmissionStore submissions,ObjectMapper json,PlatformTransactionManager manager){
        this.access=access;this.store=store;this.validation=validation;this.assets=assets;this.coupang=coupang;this.documents=documents;this.adapters=List.copyOf(adapters);this.submissions=submissions;this.json=json;this.transactions=new TransactionTemplate(manager);this.importAccount=documents.accountKey();
    }
    public Document create(Long actor,Document input){
        access.productActor(actor,true);var d=bounded(input);protectNew(d);requireBrand(actor,null,d);
        return transactions.execute(tx->{access.productActor(actor,true);var created=store.insert(actor,BusinessIds.next(),d,null,null);assets.replaceReferences(actor,created.id(),MarketplaceDocuments.assetIds(created));return created;});
    }
    public Document save(Long actor,String value,Long expectedRevision,Document input){
        access.productActor(actor,true);long id=id(value);var d=bounded(input);
        if(d.id()!=null&&!d.id().equals(value))throw new InputValidationFailure("초안 식별자를 확인해 주세요.");
        return transactions.execute(tx->{
            access.productActor(actor,true);var before=store.find(id,true);
            if("COUPANG_REGISTRATION".equals(store.registration(id).kind()))throw new InputValidationFailure("쿠팡 전용 등록 화면에서 초안을 저장해 주세요.");
            if("NAVER_REGISTRATION".equals(store.registration(id).kind()))throw new InputValidationFailure("스마트스토어 전용 등록 화면에서 초안을 저장해 주세요.");
            if(expectedRevision==null)throw new InputValidationFailure("수정 버전을 확인해 주세요.");
            if(before.revision().longValue()!=expectedRevision.longValue())throw new MarketplaceDraftFailure(CONFLICT);
            protectImport(before,d);requireBrand(actor,before,d);var saved=store.update(actor,id,expectedRevision,d);assets.replaceReferences(actor,saved.id(),MarketplaceDocuments.assetIds(saved));return saved;
        });
    }
    public Document get(Long actor,String value){access.productActor(actor,true);return store.find(id(value),false);}
    public PageResponse<Summary> list(Long actor,String query,int page,int size){
        access.productActor(actor,true);if(page<0||page>1_000_000||!Set.of(10,20,50,100).contains(size))throw new InputValidationFailure("페이지 크기와 위치를 확인해 주세요.");
        return store.list(MarketplaceDocuments.text(query,200).trim(),page,size);
    }
    public Validation validate(Long actor,Document input){return validate(actor,input,null);}
    public Validation validate(Long actor,Document input,String requestId){
        access.productActor(actor,true);var d=bounded(input);
        if(d.id()==null)protectNew(d);else{var saved=store.find(id(d.id()),false);protectImport(saved,d);}
        return validation.validate(actor,d,requestId);
    }
    public Document importCoupang(Long actor,String productId,String requestId){
        access.productActor(actor,true);if(productId==null||!productId.matches("[0-9]{1,30}"))throw new InputValidationFailure("등록상품 ID를 확인해 주세요.");
        Long mapped=submissions.mappedDraft(importAccount,productId);if(mapped!=null)return store.find(mapped,false);
        var existing=store.imported(importAccount,productId);if(existing!=null)return transactions.execute(tx->link(existing));
        var source=coupang.edit(actor,productId,requestId);if(!Objects.equals(source.basic().sellerProductId(),productId))throw new MarketplaceFailure(MarketplaceFailure.Kind.RESPONSE);
        return importObserved(actor,source);
    }
    /** Import the trusted server observation only; never replace an existing reference. */
    Document importObserved(Long actor,CoupangEditor.EditorDocument source){
        access.productActor(actor,true);String productId=source.basic().sellerProductId();
        Long mapped=submissions.mappedDraft(importAccount,productId);if(mapped!=null)return store.find(mapped,false);
        var existing=store.imported(importAccount,productId);if(existing!=null)return transactions.execute(tx->link(existing));
        var document=bounded(documents.importObserved(source));
        try{return transactions.execute(tx->{access.productActor(actor,true);var found=store.imported(importAccount,productId);if(found!=null)return link(found);return link(store.insert(actor,BusinessIds.next(),document,importAccount,productId));});}
        catch(DuplicateKeyException duplicate){var found=store.imported(importAccount,productId);if(found!=null)return transactions.execute(tx->link(found));throw duplicate;}
    }
    /** Read-only lookup for reopening the standalone execution history. */
    String existingCoupangDraft(Long actor,String productId){
        access.productActor(actor,true);
        Long mapped=submissions.mappedDraft(importAccount,productId);if(mapped!=null)return Long.toString(mapped);
        var imported=store.imported(importAccount,productId);return imported==null?null:imported.id();
    }
    private Document link(Document d){
        var mapping=documents.legacyMapping(d,importAccount);
        if(mapping==null)throw new MarketplaceFailure(MarketplaceFailure.Kind.RESPONSE);
        submissions.saveMapping(id(d.id()),d.revision(),mapping,false);return d;
    }
    private Document bounded(Document input){
        Document value=input;for(var adapter:adapters)value=adapter.normalize(value);var normalized=MarketplaceDocuments.normalize(value);
        if(json.writeValueAsBytes(normalized).length>MarketplaceDocuments.MAX_BYTES)throw new InputValidationFailure("초안은 5MiB 이하로 입력해 주세요.");
        return normalized;
    }
    private void requireBrand(Long actor,Document before,Document after){documents.requireBrand(actor,before,after);}
    private void protectNew(Document document){documents.protectNew(document);}
    private void protectImport(Document before,Document after){documents.protectImport(before,after);}
    private static long id(String value){try{long id=Long.parseLong(value);if(id<=0||!value.equals(Long.toString(id)))throw new NumberFormatException();return id;}catch(RuntimeException e){throw new InputValidationFailure("초안 ID를 확인해 주세요.");}}
    private static String account(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable");}}
}
