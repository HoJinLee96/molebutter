package cc.ataglace.molebutter.marketplace.api;

import java.net.URI;
import java.util.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;

/** Bounded structure validation for incomplete drafts, distinct from publishing validation. */
public final class MarketplaceDocuments {
    public static final int MAX_BYTES=5*1024*1024;
    private MarketplaceDocuments() {}
    public static String text(String value,int max) {
        String s=value==null?"":value;
        if(s.length()>max||s.indexOf('\0')>=0)throw new InputValidationFailure("입력 내용이 허용 범위를 초과했습니다.");
        return s;
    }
    public static <T> List<T> list(List<T> values,int max) {
        if(values==null)return List.of();
        if(values.size()>max||values.stream().anyMatch(Objects::isNull))throw new InputValidationFailure("입력 항목 수를 확인해 주세요.");
        return List.copyOf(values);
    }
    public static String uuid(String value) {
        if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new InputValidationFailure("항목 식별자를 확인해 주세요.");
        return value.toLowerCase(Locale.ROOT);
    }
    public static boolean https(String value) {
        try {var u=URI.create(value);return "https".equalsIgnoreCase(u.getScheme())&&u.getHost()!=null&&u.getUserInfo()==null&&u.getPort()!=0;}
        catch(RuntimeException e){return false;}
    }
    public static Document normalize(Document d) {
        if(d==null||d.common()==null)throw new InputValidationFailure("상품 입력을 확인해 주세요.");
        var c=d.common();
        var common=new Common(text(c.productCode(),200),text(c.name(),500),text(c.productName(),500),text(c.brand(),200),text(c.manufacturer(),200),text(c.origin(),200),text(c.material(),2000),text(c.model(),200),text(c.afterService(),2000),text(c.taxType(),50),text(c.adultOnly(),50));
        var ids=new HashSet<String>();var options=new ArrayList<Option>();
        for(var o:list(d.options(),200)){
            var id=uuid(o.id());if(!ids.add(id))throw new InputValidationFailure("옵션 식별자가 중복되었습니다.");
            var attrs=new ArrayList<Attribute>();for(var a:list(o.attributes(),100))attrs.add(new Attribute(text(a.name(),200),text(a.value(),2000)));
            options.add(new Option(id,text(o.name(),500),text(o.sku(),200),text(o.price(),100),text(o.quantity(),100),List.copyOf(attrs)));
        }
        var services=new ArrayList<ServiceOption>();var serviceIds=new HashSet<String>();
        for(var s:list(d.services(),20)){
            var id=uuid(s.id());if(!serviceIds.add(id)||ids.contains(id))throw new InputValidationFailure("서비스 식별자가 중복되었습니다.");
            services.add(new ServiceOption(id,text(s.name(),200),list(s.choices(),20).stream().map(v->text(v,200)).toList()));
        }
        var media=d.media()==null?new Media(List.of(),List.of()):d.media();var images=new ArrayList<Image>();var imageIds=new HashSet<String>();
        for(var image:list(media.images(),500)){
            var id=uuid(image.id());if(!imageIds.add(id))throw new InputValidationFailure("이미지 식별자가 중복되었습니다.");
            String option=reference(image.optionId(),ids);String asset=blank(image.assetId())?null:uuid(image.assetId());String url=blank(image.url())?null:text(image.url(),4000);
            if(asset==null&&url!=null&&!https(url))throw new InputValidationFailure("이미지 주소는 HTTPS로 입력해 주세요.");
            if(image.order()<0||image.order()>1000)throw new InputValidationFailure("이미지 순서를 확인해 주세요.");
            if(image.type()!=null&&(!Set.of("REPRESENTATION","DETAIL","USED_PRODUCT").contains(image.type())||image.representative()!=image.type().equals("REPRESENTATION")))throw new InputValidationFailure("이미지 종류를 확인해 주세요.");
            images.add(new Image(id,asset,asset==null?url:"/api/marketplaces/assets/"+asset,image.representative(),image.order(),option,image.type()));
        }
        var contents=new ArrayList<Content>();var contentIds=new HashSet<String>();
        for(var content:list(media.contents(),200)){
            var id=uuid(content.id());if(!contentIds.add(id))throw new InputValidationFailure("설명 식별자가 중복되었습니다.");
            if(!Set.of("HTML","IMAGE").contains(content.type()))throw new InputValidationFailure("설명 종류를 확인해 주세요.");
            var value=text(content.value(),2*1024*1024);if("IMAGE".equals(content.type())&&!value.isBlank()&&!https(value))throw new InputValidationFailure("설명 이미지 주소는 HTTPS로 입력해 주세요.");
            contents.add(new Content(id,content.type(),value,reference(content.optionId(),ids)));
        }
        var delivery=d.delivery();if(delivery==null)delivery=new Delivery("","","","","","","","","","","","","","","","");
        delivery=new Delivery(text(delivery.method(),50),text(delivery.carrier(),100),text(delivery.chargeType(),50),text(delivery.charge(),100),text(delivery.freeOver(),100),text(delivery.returnCharge(),100),text(delivery.initialReturnCharge(),100),text(delivery.remoteArea(),50),text(delivery.bundle(),50),text(delivery.outboundCode(),100),text(delivery.returnCode(),100),text(delivery.returnName(),200),text(delivery.returnContact(),200),text(delivery.returnZip(),50),text(delivery.returnAddress(),1000),text(delivery.returnAddressDetail(),1000));
        var selected=new LinkedHashSet<>(list(d.selectedMarkets(),6));var markets=new EnumMap<Market,MarketConfig>(Market.class);
        if(d.markets()!=null){if(d.markets().size()>6)throw new InputValidationFailure("마켓 설정을 확인해 주세요.");for(var entry:d.markets().entrySet()){
            if(entry.getKey()==null||entry.getValue()==null)throw new InputValidationFailure("마켓 설정을 확인해 주세요.");
            var m=entry.getValue();var override=m.overrides();if(override!=null){
                var overrides=new ArrayList<OptionOverride>();var seen=new HashSet<String>();
                for(var o:list(override.options(),200)){var id=reference(o.optionId(),ids);if(id==null||!seen.add(id))throw new InputValidationFailure("마켓 옵션 연결을 확인해 주세요.");overrides.add(new OptionOverride(id,nullable(o.name(),500),nullable(o.sku(),200),nullable(o.price(),100),nullable(o.quantity(),100)));}
                List<String> selectedImages=override.imageIds()==null?null:list(override.imageIds(),500).stream().map(v->{var id=uuid(v);if(!imageIds.contains(id))throw new InputValidationFailure("마켓 이미지 연결을 확인해 주세요.");return id;}).distinct().toList();
                override=new Overrides(nullable(override.name(),500),nullable(override.productName(),500),nullable(override.brand(),200),List.copyOf(overrides),selectedImages,nullable(override.description(),2*1024*1024));
            }
            var coupang=m.coupang();
            if(coupang!=null){
                var mapping=new HashSet<String>();var rows=new ArrayList<CoupangOption>();
                for(var o:list(coupang.options(),200)){
                    var id=reference(o.optionId(),ids);if(id==null||!mapping.add(id))throw new InputValidationFailure("쿠팡 옵션 연결을 확인해 주세요.");
                    var attrs=new ArrayList<cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Attribute>();for(var a:list(o.attributes(),100))attrs.add(new cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Attribute(text(a.name(),200),text(a.value(),2000),text(a.exposed(),50)));
                    var notices=new ArrayList<cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Notice>();for(var n:list(o.notices(),100))notices.add(new cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Notice(text(n.category(),200),text(n.name(),200),text(n.content(),10000)));
                    var certs=new ArrayList<cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Certification>();for(var cert:list(o.certifications(),100)){
                        var attachments=new ArrayList<cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Image>();for(var i:list(cert.attachments(),100))attachments.add(new cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Image(i.order(),text(i.type(),100),nullable(i.url(),4000)));
                        certs.add(new cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Certification(text(cert.type(),200),text(cert.code(),500),List.copyOf(attachments)));
                    }
                    rows.add(new CoupangOption(id,externalId(o.sellerProductItemId()),externalId(o.vendorItemId()),fields(o.registration()),List.copyOf(attrs),List.copyOf(notices),List.copyOf(certs)));
                }
                var documents=new ArrayList<cc.ataglace.molebutter.marketplace.api.CoupangEditor.Document>();for(var document:list(coupang.documents(),100))documents.add(new cc.ataglace.molebutter.marketplace.api.CoupangEditor.Document(text(document.templateName(),200),nullable(document.path(),4000),nullable(document.vendorPath(),4000)));
                coupang=new Coupang(externalId(coupang.sellerProductId()),fields(coupang.delivery()),fields(coupang.settings()),List.copyOf(rows),List.copyOf(documents),coupang.source());
            }
            var naver=m.naver();if(naver!=null)naver=new Naver(text(naver.channelId(),100),text(naver.status(),100),text(naver.saleType(),100),text(naver.originCode(),100),text(naver.deliveryTemplateId(),100),text(naver.afterServiceTelephone(),200),attributes(naver.attributes()),attributes(naver.notices()),nullable(naver.channelProductName(),500),naver.naverShoppingRegistration(),nullable(naver.channelProductDisplayStatusType(),100),naver.editorInput()==null?null:naver.editorInput());
            var esm=m.esm();if(esm!=null)esm=new Esm(text(esm.siteId(),100),text(esm.goodsStatus(),100),text(esm.shippingPolicyId(),100),text(esm.returnPolicyId(),100),text(esm.itemCode(),200),attributes(esm.attributes()),attributes(esm.notices()));
            markets.put(entry.getKey(),new MarketConfig(text(m.categoryCode(),100),override,coupang,naver,esm));
        }}
        return new Document(d.id(),d.revision(),common,List.copyOf(options),d.stockMode()==null?StockMode.OPTION:d.stockMode(),text(d.productQuantity(),100),List.copyOf(services),new Media(List.copyOf(images),List.copyOf(contents)),delivery,List.copyOf(selected),Map.copyOf(markets));
    }
    public static boolean blank(String s){return s==null||s.isBlank();}
    private static String externalId(String s){if(blank(s))return null;if(!s.matches("[0-9]{1,30}"))throw new InputValidationFailure("외부 상품 식별자를 확인해 주세요.");return s;}
    private static List<Attribute> attributes(List<Attribute> input){var output=new ArrayList<Attribute>();for(var a:list(input,100))output.add(new Attribute(text(a.name(),200),text(a.value(),10000)));return List.copyOf(output);}
    private static List<cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Field> fields(List<cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Field> input){
        var output=new ArrayList<cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Field>();var keys=new HashSet<String>();
        for(var f:list(input,100)){var name=text(f.name(),200);if(name.isBlank()||!keys.add(name))throw new InputValidationFailure("쿠팡 설정 항목 이름을 확인해 주세요.");output.add(new cc.ataglace.molebutter.marketplace.api.CoupangCatalog.Field(name,text(f.value(),10000)));}return List.copyOf(output);
    }
    public static String nullable(String s,int max){return s==null?null:text(s,max);}
    public static String reference(String s,Set<String> ids){if(blank(s))return null;var id=uuid(s);if(!ids.contains(id))throw new InputValidationFailure("옵션 연결을 확인해 주세요.");return id;}
    public static Set<String> assetIds(Document d){var result=new HashSet<String>();for(var i:d.media().images())if(i.assetId()!=null)result.add(i.assetId());return result;}
}
