package cc.ataglace.molebutter.marketplace.internal;

import java.time.Instant;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceEditingFailure.Kind.*;

@Service
public final class DefaultCoupangProductSaving implements CoupangProductSaving {
    private final BusinessAccess access;
    private final CoupangEditor editor;
    private final CoupangProductDocuments documents;
    private final DefaultMarketplaceDrafts drafts;
    private final DefaultMarketplaceEditing editing;
    private final MarketplaceSubmissions submissions;
    private final ObjectMapper json;
    private final String account;
    private final Clock clock;
    private final Map<String,Observed> observations=new LinkedHashMap<>();
    private record Observed(Long actor,String account,String productId,Instant expires,CoupangEditor.EditorDocument document) {}
    @org.springframework.beans.factory.annotation.Autowired
    public DefaultCoupangProductSaving(BusinessAccess access,CoupangEditor editor,CoupangProductDocuments documents,DefaultMarketplaceDrafts drafts,
            DefaultMarketplaceEditing editing,MarketplaceSubmissions submissions,ObjectMapper json,
            @Value("${marketplace.coupang.vendor-id:}") String vendor){
        this(access,editor,documents,drafts,editing,submissions,json,vendor,Clock.systemUTC());
    }
    DefaultCoupangProductSaving(BusinessAccess access,CoupangEditor editor,CoupangProductDocuments documents,DefaultMarketplaceDrafts drafts,
            DefaultMarketplaceEditing editing,MarketplaceSubmissions submissions,ObjectMapper json,String vendor,Clock clock){
        this.clock=clock;
        this.access=access;this.editor=editor;this.documents=documents;this.drafts=drafts;this.editing=editing;this.submissions=submissions;this.json=json;
        account=DefaultMarketplaceSubmissions.account(vendor.trim());
    }
    @Override public Observation observe(Long actor,String productId,String requestId){
        access.productActor(actor,true);product(productId);
        var source=editor.edit(actor,productId,requestId);
        if(source==null||source.basic()==null||!productId.equals(source.basic().sellerProductId()))throw new MarketplaceFailure(MarketplaceFailure.Kind.RESPONSE);
        var expires=clock.instant().plusSeconds(600);String token=UUID.randomUUID().toString()+UUID.randomUUID();
        synchronized(observations){observations.entrySet().removeIf(e->!clock.instant().isBefore(e.getValue().expires()));
            // Never evict another active form silently: bounded observations fail before issuing a token.
            if(observations.size()>=2000)throw new InputValidationFailure("열린 상품 편집 화면이 너무 많습니다. 잠시 후 다시 조회해 주세요.");
            observations.put(token,new Observed(actor,account,productId,expires,source));}
        return new Observation(token,expires.toString(),source,drafts.existingCoupangDraft(actor,productId));
    }
    @Override public MarketplaceSubmissions.Preview prepare(Long actor,String productId,Prepare input){
        access.productActor(actor,true);product(productId);
        if(input==null||input.token()==null)throw new MarketplaceEditingFailure(NOT_FOUND);
        Observed original;
        synchronized(observations){original=observations.get(input.token());}
        if(original==null||!Objects.equals(actor,original.actor())||!productId.equals(original.productId())||!account.equals(original.account()))throw new MarketplaceEditingFailure(NOT_FOUND);
        if(!clock.instant().isBefore(original.expires()))throw new MarketplaceEditingFailure(EXPIRED);
        // Validate the browser's scope before creating an internal draft or edit session.
        validateInput(input.changes(),original.document());
        translate(input.changes(),documents.importObserved(original.document()),json,documents);
        var reference=drafts.importObserved(actor,original.document());
        var session=editing.startObserved(actor,reference.id(),original.document(),original.expires().toString());
        var observed=session.targets().getFirst().document();
        var changes=translate(input.changes(),observed,json,documents);
        return submissions.prepare(actor,new MarketplaceEditing.PrepareRequest(reference.id(),reference.revision(),session.id(),true,
            List.of(new MarketplaceEditing.TargetChanges("COUPANG",changes))));
    }
    private static void product(String value){if(value==null||!value.matches("[0-9]{1,30}"))throw new InputValidationFailure("등록상품 ID를 확인해 주세요.");}
    static void validateInput(List<Change> changes,CoupangEditor.EditorDocument source){
        if(changes==null||changes.isEmpty()||changes.size()>200)throw new InputValidationFailure("변경 항목은 1개 이상 200개 이하로 선택해 주세요.");
        var ids=new HashSet<String>();for(var option:source.options())if(option.sellerProductItemId()==null||!ids.add(option.sellerProductItemId()))throw new MarketplaceFailure(MarketplaceFailure.Kind.RESPONSE);
        for(var c:changes){
            if(c==null||c.path()==null||c.value()==null)throw new InputValidationFailure("변경할 항목과 값을 확인해 주세요.");
            boolean option=c.path().startsWith("options.")||c.path().startsWith("markets.COUPANG.coupang.options.")||c.path().startsWith("media.");
            if(option&&!ids.contains(c.sellerProductItemId())||!option&&c.sellerProductItemId()!=null)throw new InputValidationFailure("수정할 옵션 연결을 확인해 주세요.");
            if(c.path().equals("common.brand")||c.path().equals("markets.COUPANG.overrides.brand")||c.path().endsWith(".brandId")||c.path().endsWith(".bundleInfo.bundleType")||c.path().contains(".autoPricingInfo."))throw new InputValidationFailure("기존 상품에서 수정할 수 없는 항목입니다.");
        }
    }
    static List<MarketplaceEditing.Change> translate(List<Change> input,MarketplaceDrafts.Document observed,ObjectMapper json,CoupangProductDocuments documents){
        var mapping=observed.markets().get(MarketplaceDrafts.Market.COUPANG).coupang().options();
        var changes=new ArrayList<MarketplaceEditing.Change>();
        for(var c:input){
            String option=c.sellerProductItemId()==null?null:mapping.stream().filter(o->c.sellerProductItemId().equals(o.sellerProductItemId())).map(MarketplaceDrafts.CoupangOption::optionId).findFirst().orElseThrow(()->new InputValidationFailure("상품 옵션 연결이 변경되었습니다."));
            Object value=c.value();
            if(c.path().endsWith(".notices")){
                var rows=json.valueToTree(value);var categories=new HashSet<String>();
                if(!rows.isArray())throw new InputValidationFailure("고시 유형을 확인해 주세요.");
                for(var row:rows)categories.add(row.path("category").asString());
                if(categories.size()!=1)throw new InputValidationFailure("전송할 상품고시는 한 유형만 선택해 주세요.");
            }
            if(c.path().startsWith("media.")){
                var rows=json.valueToTree(value);if(!rows.isArray())throw new InputValidationFailure("이미지·설명 형식을 확인해 주세요.");
                for(var row:rows){if(!row.isObject()||row.has("optionId"))throw new InputValidationFailure("이미지·설명의 옵션 연결은 서버에서 결정합니다.");row.asObject().put("optionId",option);if(!row.has("id"))row.asObject().put("id",UUID.randomUUID().toString());}
                value=rows;
            }
            changes.add(new MarketplaceEditing.Change(c.path(),option,value));
        }
        // Run the existing whitelist, canonicalization and collection/value validation before delegation.
        var expanded=documents.expand(observed,changes);documents.apply(observed,expanded);
        return expanded;
    }
}
