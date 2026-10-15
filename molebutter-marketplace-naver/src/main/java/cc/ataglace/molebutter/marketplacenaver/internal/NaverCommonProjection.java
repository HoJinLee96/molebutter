package cc.ataglace.molebutter.marketplacenaver.internal;

import java.util.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;

/** Common defaults become a provider input, while the durable common document stays intact. */
final class NaverCommonProjection {
    private static final String P="originProduct.",D=P+"detailAttribute.",S="smartstoreChannelProduct.";
    private NaverCommonProjection() {}

    static EditorState state(EditorState s){
        if(s==null)return new EditorState(1,List.of(),"COMMON","COMMON",null,null,null,null,List.of());
        if(s.version()!=null&&s.version()!=1)throw invalid("스마트스토어 편집 상태 버전을 확인해 주세요.");
        var overrides=MarketplaceDocuments.list(s.overrides(),2000).stream().map(v->MarketplaceDocuments.text(v,400)).distinct().toList();
        for(String path:overrides){
            boolean scalar=path.startsWith("fields.")&&(NaverEditPatch.SCALARS.contains(path.substring(7))||NaverEditPatch.COLLECTIONS.contains(path.substring(7)));
            boolean option=path.matches("options\\.[0-9a-fA-F-]{36}\\.(values|sellerManagerCode|stockQuantity|usable)");
            if(!scalar&&!option&&!Set.of("images","description","optionNames","optionMode","options","pricing","optionStructure").contains(path))throw invalid("스마트스토어 개별 입력 경로를 확인해 주세요.");
        }
        return new EditorState(1,overrides,choice(s.pricing(),"COMMON",Set.of("COMMON","CUSTOM")),choice(s.optionStructure(),"COMMON",Set.of("COMMON","CUSTOM")),source(s.imageSource()),optionId(s.imageSource(),s.imageOptionId()),source(s.descriptionSource()),optionId(s.descriptionSource(),s.descriptionOptionId()),MarketplaceDocuments.list(s.unmapped(),100).stream().map(v->MarketplaceDocuments.text(v,200)).distinct().toList());
    }
    static Document project(Document reference){
        var config=reference.markets().get(Market.NAVER);
        if(config==null)throw invalid("스마트스토어 입력을 확인해 주세요.");
        var n=config.naver();
        if(n!=null&&n.editorInput()!=null&&n.editorState()==null)return NaverDraftAdapter.withInput(reference,n.editorInput(),null);
        var state=state(n==null?null:n.editorState());
        var old=n==null?null:n.editorInput();
        var fields=defaults();if(old!=null)fields.putAll(old.fields());
        var overrides=new HashSet<>(state.overrides());var unmapped=new LinkedHashSet<>(state.unmapped());
        var c=reference.common();var over=config.overrides();
        inherit(fields,overrides,P+"name",over!=null&&over.name()!=null?over.name():c.name());
        inherit(fields,overrides,S+"channelProductName",n!=null&&!blank(n.channelProductName())?n.channelProductName():c.name());
        inherit(fields,overrides,D+"sellerCodeInfo.sellerManagementCode",c.productCode());
        inherit(fields,overrides,D+"naverShoppingSearchInfo.brandName",over!=null&&over.brand()!=null?over.brand():c.brand());
        inherit(fields,overrides,D+"naverShoppingSearchInfo.brandId",n==null?null:n.brandId());
        inherit(fields,overrides,D+"naverShoppingSearchInfo.manufacturerName",c.manufacturer());
        inherit(fields,overrides,D+"naverShoppingSearchInfo.modelName",c.model());
        inherit(fields,overrides,D+"afterServiceInfo.afterServiceGuideContent",MarketplaceCommonSettings.service(c));
        inherit(fields,overrides,D+"taxType","FREE".equals(c.taxType())?"DUTYFREE":blank(c.taxType())?"TAX":c.taxType());
        inherit(fields,overrides,D+"minorPurchasable",!"ADULT_ONLY".equals(c.adultOnly()));
        inherit(fields,overrides,P+"leafCategoryId",config.categoryCode());
        if(old==null){
            if(n!=null){
                put(fields,P+"statusType",n.status());put(fields,P+"saleType",n.saleType());put(fields,D+"originAreaInfo.originAreaCode",n.originCode());put(fields,D+"afterServiceInfo.afterServiceTelephoneNumber",n.afterServiceTelephone());
                put(fields,S+"naverShoppingRegistration",n.naverShoppingRegistration());put(fields,S+"channelProductDisplayStatusType",n.channelProductDisplayStatusType());
                if(!blank(n.channelId()))unmapped.add("channelId");if(!blank(n.deliveryTemplateId()))unmapped.add("deliveryTemplateId");
                if(n.attributes()!=null&&!n.attributes().isEmpty())unmapped.add("attributes");if(n.notices()!=null&&!n.notices().isEmpty())unmapped.add("notices");
            }
        }
        if(!blank(c.afterServiceTelephone()))inherit(fields,overrides,D+"afterServiceInfo.afterServiceTelephoneNumber",c.afterServiceTelephone());
        if(!blank(c.saleStartedAt()))inherit(fields,overrides,P+"saleStartDate",c.saleStartedAt()+"+09:00");
        if(!blank(c.saleEndedAt()))inherit(fields,overrides,P+"saleEndDate",c.saleEndedAt()+"+09:00");
        unmapped.removeAll(List.of("delivery.method","delivery.carrier","delivery.chargeType","delivery.outboundCode","delivery.returnCode","delivery.initialReturnCharge","delivery.remoteArea","delivery.shippingDays","delivery.bundle"));
        mapDelivery(reference.delivery(),fields,overrides,unmapped);
        if(config.addressBook()!=null){
            if(config.addressBook().outbound()!=null)inherit(fields,overrides,P+"deliveryInfo.claimDeliveryInfo.shippingAddressId",number(config.addressBook().outbound().code()));
            if(config.addressBook().returns()!=null)inherit(fields,overrides,P+"deliveryInfo.claimDeliveryInfo.returnAddressId",number(config.addressBook().returns().code()));
        }
        var source=reference.options();
        var existing=new HashMap<String,NaverEditor.Option>();if(old!=null)for(var o:old.options())existing.put(o.id(),o);
        var prices=new HashMap<String,Long>();for(var o:source){var own=override(config,o.id());prices.put(o.id(),number(own!=null&&own.price()!=null?own.price():o.price()));}
        Long base=prices.values().stream().filter(Objects::nonNull).min(Long::compareTo).orElse(null);
        boolean commonPricing="COMMON".equals(state.pricing()),commonStructure="COMMON".equals(state.optionStructure());
        if(commonPricing){
            Long normal=source.stream().map(o->number(o.originalPrice())).filter(Objects::nonNull).max(Long::compareTo).orElse(null);
            if(normal!=null){Long current=number(Objects.toString(fields.get(P+"salePrice"),""));base=current==null?normal:Math.max(normal,current);}
            fields.put(P+"salePrice",base);
        }
        Long discounted=commonPricing?discountedBase(base,fields):null;
        var names=commonStructure?List.of("옵션"):old==null?List.of("옵션"):old.optionNames();
        var options=new ArrayList<NaverEditor.Option>();long total=0;boolean completeStock=true;
        for(var o:source){
            var prior=existing.get(o.id());var own=override(config,o.id());
            String name=own!=null&&own.name()!=null?own.name():o.name();String sku=own!=null&&own.sku()!=null?own.sku():o.sku();
            Long quantity=reference.stockMode()==StockMode.PRODUCT?(source.size()==1?number(reference.productQuantity()):null):number(own!=null&&own.quantity()!=null?own.quantity():o.quantity());
            List<String> values=commonStructure?List.of(Objects.toString(name,"")):prior==null?new ArrayList<>(Collections.nCopies(names.size(),"")):prior.values();
            if(prior!=null){if(overrides.contains("options."+o.id()+".values"))values=prior.values();if(overrides.contains("options."+o.id()+".sellerManagerCode"))sku=prior.sellerManagerCode();if(overrides.contains("options."+o.id()+".stockQuantity"))quantity=prior.stockQuantity();}
            Long price=commonPricing?(prices.get(o.id())==null||discounted==null?null:prices.get(o.id())-discounted):prior==null?null:prior.price();
            Boolean usable=prior==null?true:prior.usable();
            options.add(new NaverEditor.Option(o.id(),values,price,quantity,sku,usable));
            if(!Boolean.FALSE.equals(usable)){if(quantity==null)completeStock=false;else try{total=Math.addExact(total,quantity);}catch(ArithmeticException invalid){throw invalid("옵션 재고 합계의 범위를 확인해 주세요.");}}
        }
        if(commonStructure||old==null){fields.put(P+"stockQuantity",completeStock?total:null);}
        String imageSource=state.imageSource(),imageOption=state.imageOptionId(),descriptionSource=state.descriptionSource(),descriptionOption=state.descriptionOptionId();
        if(imageSource==null&&!images(reference,null).isEmpty())imageSource="COMMON";
        if(descriptionSource==null&&!contents(reference,null).isEmpty())descriptionSource="COMMON";
        var images=old==null?List.<NaverEditor.Image>of():old.images();String description=old==null?"":old.description();
        if(old==null&&over!=null&&over.imageIds()!=null){var selected=over.imageIds();images=reference.media().images().stream().filter(i->selected.contains(i.id())).sorted(Comparator.comparingInt(i->selected.indexOf(i.id()))).map(i->new NaverEditor.Image(i.id(),i.assetId(),i.url(),i.representative(),selected.indexOf(i.id()))).toList();imageSource="CUSTOM";imageOption=null;}
        if(old==null&&over!=null&&over.description()!=null){description=over.description();descriptionSource="CUSTOM";descriptionOption=null;}
        if(!"CUSTOM".equals(imageSource)&&validSource(reference,imageSource,imageOption))images=images(reference,"OPTION".equals(imageSource)?imageOption:null).stream().map(i->new NaverEditor.Image(i.id(),i.assetId(),i.url(),i.representative(),i.order())).toList();
        if(!"CUSTOM".equals(descriptionSource)&&validSource(reference,descriptionSource,descriptionOption))description=description(contents(reference,"OPTION".equals(descriptionSource)?descriptionOption:null));
        var nextState=new EditorState(1,state.overrides(),state.pricing(),state.optionStructure(),imageSource,imageOption,descriptionSource,descriptionOption,List.copyOf(unmapped));
        String mode=commonStructure?(options.isEmpty()?"NONE":"COMBINATION"):old==null?"COMBINATION":old.optionMode();
        var input=new NaverEditor.Input(fields,mode,"NONE".equals(mode)?List.of():names,options,images,description);
        return NaverDraftAdapter.withInput(reference,input,nextState);
    }
    static void requireMediaSources(Document document){
        var config=document.markets().get(Market.NAVER);var s=config==null||config.naver()==null?null:config.naver().editorState();if(s==null)return;
        if(!validSource(document,s.imageSource(),s.imageOptionId())&&!"CUSTOM".equals(s.imageSource()))throw invalid("상품 이미지의 공통 또는 옵션 원본을 선택하거나 현재 값을 개별값으로 유지해 주세요.");
        if(!validSource(document,s.descriptionSource(),s.descriptionOptionId())&&!"CUSTOM".equals(s.descriptionSource()))throw invalid("상세설명의 공통 또는 옵션 원본을 선택하거나 현재 값을 개별값으로 유지해 주세요.");
    }
    private static LinkedHashMap<String,Object> defaults(){
        var f=new LinkedHashMap<String,Object>();f.put(P+"statusType","SALE");f.put(P+"saleType","NEW");f.put(D+"taxType","TAX");f.put(D+"minorPurchasable",true);f.put(S+"naverShoppingRegistration",true);f.put(S+"channelProductDisplayStatusType","ON");
        f.put(P+"deliveryInfo.deliveryType","DELIVERY");f.put(P+"deliveryInfo.deliveryAttributeType","NORMAL");f.put(P+"deliveryInfo.deliveryCompany","CJGLS");f.put(P+"deliveryInfo.deliveryFee.deliveryFeeType","FREE");f.put(P+"deliveryInfo.deliveryFee.baseFee",0L);f.put(P+"deliveryInfo.claimDeliveryInfo.returnDeliveryFee",0L);f.put(P+"deliveryInfo.claimDeliveryInfo.exchangeDeliveryFee",0L);return f;
    }
    private static void mapDelivery(Delivery d,Map<String,Object> f,Set<String> overrides,Set<String> unmapped){
        if(d==null)return;
        if("SEQUENCIAL".equals(d.method())){inherit(f,overrides,P+"deliveryInfo.deliveryType","DELIVERY");inherit(f,overrides,P+"deliveryInfo.deliveryAttributeType","NORMAL");}
        else if("VENDOR_DIRECT".equals(d.method()))inherit(f,overrides,P+"deliveryInfo.deliveryType","DIRECT");else if(!blank(d.method()))unmapped.add("delivery.method");
        var carriers=Map.of("CJGLS","CJGLS","HYUNDAI","HYUNDAI","HANJIN","HANJIN","KGB","KGB","EPOST","EPOST","KDEXP","KDEXP");if(carriers.containsKey(Objects.toString(d.carrier(),"")))inherit(f,overrides,P+"deliveryInfo.deliveryCompany",carriers.get(d.carrier()));else if(!blank(d.carrier()))unmapped.add("delivery.carrier");
        String fee=switch(Objects.toString(d.chargeType(),"")){case "FREE"->"FREE";case "NOT_FREE","CHARGE_RECEIVED"->"PAID";case "CONDITIONAL_FREE"->"CONDITIONAL_FREE";default->null;};
        if(fee!=null){inherit(f,overrides,P+"deliveryInfo.deliveryFee.deliveryFeeType",fee);inherit(f,overrides,P+"deliveryInfo.deliveryFee.baseFee","FREE".equals(fee)?0L:number(d.charge()));inherit(f,overrides,P+"deliveryInfo.deliveryFee.deliveryFeePayType","CHARGE_RECEIVED".equals(d.chargeType())?"COLLECT":"PREPAID");inherit(f,overrides,P+"deliveryInfo.deliveryFee.freeConditionalAmount","CONDITIONAL_FREE".equals(fee)?number(d.freeOver()):0L);}
        else if(!blank(d.chargeType()))unmapped.add("delivery.chargeType");
        if(!blank(d.returnCharge()))inherit(f,overrides,P+"deliveryInfo.claimDeliveryInfo.returnDeliveryFee",number(d.returnCharge()));
        if(!blank(d.bundle()))inherit(f,overrides,P+"deliveryInfo.deliveryBundleGroupUsable",d.bundle().equals("UNION_DELIVERY"));
        if(!blank(d.jejuCharge())||!blank(d.remoteCharge())){inherit(f,overrides,P+"deliveryInfo.deliveryFee.deliveryFeeByArea.deliveryAreaType","AREA_3");if(!blank(d.jejuCharge()))inherit(f,overrides,P+"deliveryInfo.deliveryFee.deliveryFeeByArea.area2extraFee",number(d.jejuCharge()));if(!blank(d.remoteCharge()))inherit(f,overrides,P+"deliveryInfo.deliveryFee.deliveryFeeByArea.area3extraFee",number(d.remoteCharge()));}
        for(var entry:Map.of("outboundCode",Objects.toString(d.outboundCode(),""),"returnCode",Objects.toString(d.returnCode(),""),"initialReturnCharge",Objects.toString(d.initialReturnCharge(),""),"remoteArea",Objects.toString(d.remoteArea(),""),"shippingDays",Objects.toString(d.shippingDays(),"")).entrySet())if(!entry.getValue().isBlank())unmapped.add("delivery."+entry.getKey());
    }
    private static OptionOverride override(MarketConfig config,String id){return config.overrides()==null||config.overrides().options()==null?null:config.overrides().options().stream().filter(o->id.equals(o.optionId())).findFirst().orElse(null);}
    private static List<Image> images(Document d,String option){return d.media()==null?List.of():d.media().images().stream().filter(i->Objects.equals(i.optionId(),option)).sorted(Comparator.comparingInt(Image::order)).toList();}
    private static List<Content> contents(Document d,String option){return d.media()==null?List.of():d.media().contents().stream().filter(i->Objects.equals(i.optionId(),option)).toList();}
    private static String description(List<Content> contents){
        var result=new ArrayList<String>();
        for(var c:contents){
            if("HTML".equals(c.type()))result.add(Objects.toString(c.value(),""));
            else if("TEXT".equals(c.type()))result.add("<p>"+escape(Objects.toString(c.value(),"")).replace("\n","<br>")+"</p>");
            else if("IMAGE".equals(c.type())&&!blank(c.value())){
                if(c.assetId()==null&&!MarketplaceDocuments.https(c.value()))throw invalid("상세설명 이미지 주소를 확인해 주세요.");
                result.add("<img src=\""+escape(c.value())+"\" alt=\"\">");
            }
        }
        return String.join("\n",result);
    }
    private static String escape(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
    private static boolean validSource(Document d,String kind,String option){return "COMMON".equals(kind)||"OPTION".equals(kind)&&option!=null&&d.options().stream().anyMatch(o->option.equals(o.id()));}
    private static void inherit(Map<String,Object> f,Set<String> overrides,String path,Object value){if(!overrides.contains("fields."+path))f.put(path,value);}
    private static void put(Map<String,Object> f,String path,Object value){if(value!=null&&(!(value instanceof String s)||!s.isBlank()))f.put(path,value);}
    private static Long discountedBase(Long base,Map<String,Object> fields){
        if(base==null)return null;String prefix=P+"customerBenefit.immediateDiscountPolicy.discountMethod.";Object raw=fields.get(prefix+"value");if(raw==null||raw.toString().isEmpty())return base;
        try{String text=raw.toString();if(!text.matches("\\d+(?:\\.\\d+)?"))throw new ArithmeticException();var value=new java.math.BigDecimal(text);String unit=Objects.toString(fields.get(prefix+"unitType"),"");if(value.signum()<=0||!Set.of("WON","PERCENT").contains(unit))throw new ArithmeticException();
            var amount="PERCENT".equals(unit)?java.math.BigDecimal.valueOf(base).multiply(value).movePointLeft(2):value;long discount=amount.longValueExact();if(discount>base)throw new ArithmeticException();return base-discount;
        }catch(ArithmeticException e){throw invalid("즉시할인 금액·비율을 확인해 주세요. 원 미만 금액이 발생하거나 판매가를 초과하면 추가금을 자동 계산할 수 없습니다.");}
    }
    private static Long number(String value){if(blank(value))return null;try{if(!value.matches("\\d+"))throw new NumberFormatException();return Long.parseLong(value);}catch(NumberFormatException e){throw invalid("공통 가격·재고는 0 이상의 정수로 입력해 주세요.");}}
    private static String source(String value){return choice(value,null,Set.of("COMMON","OPTION","CUSTOM"));}
    private static String choice(String value,String fallback,Set<String> values){if(value==null)return fallback;if(!values.contains(value))throw invalid("스마트스토어 상속 설정을 확인해 주세요.");return value;}
    private static String optionId(String source,String id){return "OPTION".equals(source)&&id!=null?MarketplaceDocuments.uuid(id):null;}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static InputValidationFailure invalid(String value){return new InputValidationFailure(value);}
}
