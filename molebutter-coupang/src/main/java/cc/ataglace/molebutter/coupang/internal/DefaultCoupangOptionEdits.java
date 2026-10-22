package cc.ataglace.molebutter.coupang.internal;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
@Service
public class DefaultCoupangOptionEdits implements CoupangOptionEdits {
    private final DefaultCoupangWorkingCopies copies;private final DefaultCoupangSubmissions sending;
    private final CoupangRegisteredProducts registry;private final CoupangQuickEditSources sources;private final CoupangPricing pricing;
    private final ObjectMapper json;private final QuickEditGuard guard;
    public DefaultCoupangOptionEdits(DefaultCoupangWorkingCopies copies,DefaultCoupangSubmissions sending,CoupangRegisteredProducts registry,CoupangQuickEditSources sources,CoupangPricing pricing,ObjectMapper json,QuickEditGuard guard){this.copies=copies;this.sending=sending;this.registry=registry;this.sources=sources;this.pricing=pricing;this.json=json;this.guard=guard;}
    private static String path(String kind){return switch(Objects.toString(kind,"")){case "PRICE"->"options.registration.salePrice";case "STOCK"->"options.registration.maximumBuyCount";default->throw invalid("수정 종류를 확인해 주세요.");};}
    private static InputValidationFailure invalid(String text){return new InputValidationFailure(text);}
    private static void value(String kind,Long value){path(kind);if(value==null||value<0||value>9007199254740991L||kind.equals("PRICE")&&(value==0||value%10!=0)||kind.equals("STOCK")&&value>99999)throw invalid("가격은 0보다 큰 10원 단위, 재고는 0~99999의 정수로 입력해 주세요.");}
    private CoupangWriteGateway.OptionMapping option(EditingStore.Stored stored,String product,String vendor){
        var mapping=stored.binding().mapping();
        if(mapping==null||!product.equals(mapping.sellerProductId()))throw invalid("상품 식별자가 변경되었습니다.");
        return mapping.options().stream().filter(o->vendor.equals(o.vendorItemId())).findFirst().orElseThrow(()->invalid("수정 가능한 옵션 ID가 없습니다."));
    }
    private void matching(CoupangQuickEditSources.Source source,CoupangEditor.EditorDocument observation,String sellerItem){
        var option=observation.options().stream().filter(o->sellerItem.equals(o.sellerProductItemId())).findFirst().orElseThrow();
        var sku=option.registration().stream().filter(f->f.name().equals("externalVendorSku")).map(CoupangCatalog.Field::value).findFirst().orElse("");
        if(!Objects.toString(sku,"").trim().toUpperCase(Locale.ROOT).equals(Objects.toString(source.productCode(),"").trim().toUpperCase(Locale.ROOT)))throw invalid("판매자상품코드와 내부 상품코드가 일치하지 않습니다.");
    }
    public Context open(Long actor,String product,String vendor,String kind,String internalProduct){
        path(kind);DefaultCoupangRegisteredProducts.productId(product);DefaultCoupangRegisteredProducts.productId(vendor);
        var entry=copies.open(actor,product,null);if(entry.observation()==null)throw invalid("현재 쿠팡값을 조회하지 못했습니다. 다시 조회해 주세요.");
        return context(actor,product,vendor,kind,internalProduct,entry,copies.require(actor,entry.sessionId(),false),registry.get(actor,product),pricing.get(actor),new HashMap<>(),true);
    }
    @org.springframework.transaction.annotation.Transactional
    public List<Context> stored(Long actor,String product,BulkOpen input) {
        if(input==null||input.options()==null||input.options().isEmpty()||input.options().size()>100)throw invalid("옵션은 1~100개로 선택해 주세요.");
        path(input.kind());DefaultCoupangRegisteredProducts.productId(product);var seen=new HashSet<String>();
        for(var option:input.options()){if(option==null)throw invalid("옵션이 필요합니다.");DefaultCoupangRegisteredProducts.productId(option.vendorItemId());if(!seen.add(option.vendorItemId()))throw invalid("중복 옵션입니다.");}
        var entry=input.sessionId()==null?copies.openStored(actor,product):copies.openStored(actor,product,input.sessionId());if(entry.observation()==null)throw invalid("저장된 쿠팡 상세를 확인할 수 없습니다.");
        var state=registry.get(actor,product);var settings=pricing.get(actor);var evidence=new HashMap<String,CoupangQuickEditSources.Source>();
        var stored=copies.require(actor,entry.sessionId(),false);
        return input.options().stream().map(o->context(actor,product,o.vendorItemId(),input.kind(),o.internalProductId(),entry,stored,state,settings,evidence,false)).toList();
    }
    private Context context(Long actor,String product,String vendor,String kind,String internalProduct,CoupangWorkingCopies.Entry entry,EditingStore.Stored stored,
        CoupangRegisteredProducts.Detail state,CoupangPricing.Settings settings,Map<String,CoupangQuickEditSources.Source> evidence,boolean live) {
        var option=option(stored,product,vendor);
        if(state.state().deleted())throw invalid("삭제된 상품입니다.");
        var observed=entry.observation().options().stream().filter(o->vendor.equals(o.vendorItemId())).findFirst().orElseThrow();
        if(live&&(observed.current()==null||observed.currentError()!=null))throw invalid("현재 옵션 판매값을 조회하지 못했습니다.");
        Long current=observed.current()==null?null:kind.equals("PRICE")?observed.current().salePrice():observed.current().amountInStock();
        if(live&&current==null)throw invalid("현재 판매값이 미확인입니다.");
        var source=evidence.computeIfAbsent(internalProduct,id->sources.get(actor,id));matching(source,entry.observation(),option.sellerProductItemId());
        CoupangPricing.Calculation calculation=null;
        if(kind.equals("PRICE")&&source.purchaseCost()!=null)try{calculation=pricing.calculate(settings,source.purchaseCost(),null);}catch(InputValidationFailure ignored){}
        Long pending=entry.changes().stream().filter(c->path(kind).equals(c.path())&&option.optionId().equals(c.optionId())).map(c->Long.valueOf(c.value().toString())).findFirst().orElse(null);
        return new Context(entry,option.optionId(),kind,current,pending,state,source,settings,calculation,observed.current());
    }
    public Result prepare(Long actor,String product,String vendor,Prepare input){return prepare(actor,product,vendor,input,false);}
    public Result prepareStored(Long actor,String product,String vendor,Prepare input){return prepare(actor,product,vendor,input,true);}
    private Result prepare(Long actor,String product,String vendor,Prepare input,boolean storedOnly){
        if(input==null)throw invalid("수정값이 필요합니다.");value(input.kind(),input.value());if(input.kind().equals("PRICE")&&input.pricingRevision()==null)throw invalid("계산 설정을 다시 확인해 주세요.");
        var stored=copies.require(actor,input.sessionId(),false);var option=option(stored,product,vendor);
        if(!Objects.equals(stored.session().workingCopyId()==null?null:stored.session().revision(),input.revision()))throw invalid("작업본이 변경되었습니다. 다시 확인해 주세요.");
        var state=registry.get(actor,product);if(state.blocked()||state.state().deleted()||state.state().version()!=input.observedVersion())throw invalid("상품값이 변경되었거나 전송 중입니다. 다시 확인해 주세요.");
        var source=sources.get(actor,input.productId());matching(source,stored.session().observation(),option.sellerProductItemId());
        var basis=new Basis(input.productId(),input.fingerprint(),input.kind().equals("PRICE")?input.pricingRevision():null);guard.check(actor,basis);
        CoupangPricing.Calculation calculation=null;
        if(input.kind().equals("PRICE")&&input.purchaseCost()!=null)calculation=pricing.calculate(pricing.get(actor),input.purchaseCost(),input.value());
        var change=new CoupangEditing.Change(path(input.kind()),option.optionId(),input.value().toString());
        var changes=new ArrayList<>(stored.session().pendingChanges());changes.removeIf(c->c.path().equals(change.path())&&Objects.equals(c.optionId(),change.optionId()));changes.add(change);
        var document=CoupangInputs.apply(json,stored.session().document(),changes);
        var saved=copies.save(actor,stored.session().workingCopyId(),new CoupangWorkingCopies.Save(input.sessionId(),input.revision(),document,changes));
        var request=new CoupangEditing.PrepareRequest(saved.workingCopyId(),saved.revision(),saved.sessionId(),List.of(change));
        var preview=storedOnly?sending.prepareSelectedStored(actor,request,basis):sending.prepareSelected(actor,request,basis);
        return new Result(saved,preview,registry.get(actor,product),calculation);
    }
}
