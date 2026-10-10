package cc.ataglace.molebutter.marketplacecoupang.internal;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.CoupangCatalog.*;
import cc.ataglace.molebutter.marketplace.api.CoupangEditor.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceFailure;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceFailure.Kind.*;
@Component
final class CoupangProductClient {
    static final String PATH="/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";
    private final String vendor,access,secret;
    private final ObjectMapper json;
    private final CoupangHttpTransport transport;
    private final Clock clock;
    private boolean running,started;
    private long lastStart, cooldownUntil;
    private final ThreadLocal<ReadOperation> activeOperation=new ThreadLocal<>();
    private final Map<String,ReadOperation> operations=new HashMap<>();
    private static final class ReadOperation {
        final Long actor;final long created=System.nanoTime();volatile boolean cancelled,active;
        ReadOperation(Long actor){this.actor=actor;}
    }
    <T> T operation(Long actor,String requestId,java.util.function.Supplier<T> work){
        if(requestId==null)return work.get();
        checkRequestId(requestId);ReadOperation operation;
        synchronized(operations){
            pruneOperations();operation=operations.get(requestId);
            if(operation==null){if(operations.size()>=256)throw new MarketplaceFailure(BUSY);operation=new ReadOperation(actor);operations.put(requestId,operation);}
            if(!Objects.equals(operation.actor,actor))throw new InputValidationFailure("조회 요청을 확인해 주세요.");
            if(operation.active)throw new MarketplaceFailure(BUSY);operation.active=true;
        }
        activeOperation.set(operation);
        try{checkCancelled();return work.get();}
        finally{activeOperation.remove();synchronized(operations){operation.active=false;operations.remove(requestId,operation);}}
    }
    void cancel(Long actor,String requestId){
        checkRequestId(requestId);
        synchronized(operations){
            pruneOperations();var operation=operations.get(requestId);
            if(operation==null){if(operations.size()>=256)return;operation=new ReadOperation(actor);operations.put(requestId,operation);}
            if(Objects.equals(operation.actor,actor))operation.cancelled=true;
        }
    }
    private void pruneOperations(){operations.values().removeIf(o->!o.active&&System.nanoTime()-o.created>java.util.concurrent.TimeUnit.MINUTES.toNanos(10));}
    private static void checkRequestId(String value){if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new InputValidationFailure("조회 요청을 확인해 주세요.");}
    private boolean cancelled(){var operation=activeOperation.get();return Thread.currentThread().isInterrupted()||operation!=null&&operation.cancelled;}
    private void checkCancelled(){if(cancelled())throw new MarketplaceFailure(CANCELLED);}
    private void waitUntil(long deadline){
        while(true){checkCancelled();long left=deadline-System.nanoTime();if(left<=0)return;
            try{java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(left,100_000_000L));}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new MarketplaceFailure(CANCELLED);}
        }
    }
    @Autowired
    public CoupangProductClient(@Value("${marketplace.coupang.vendor-id:}") String vendor,
            @Value("${marketplace.coupang.access-key:}") String access,@Value("${marketplace.coupang.secret-key:}") String secret,ObjectMapper json){
        this(vendor,access,secret,json,new CoupangHttpTransport(),Clock.systemUTC());
    }
    CoupangProductClient(String vendor,String access,String secret,ObjectMapper json,CoupangHttpTransport transport,Clock clock){
        this.vendor=vendor.trim();this.access=access.trim();this.secret=secret.trim();this.json=json;this.transport=transport;this.clock=clock;
    }
    /** Orders share the same account guard, pacing, fresh signatures and GET retry policy. */
    byte[] orderRead(String path,String query){return writeSession(()->request(path,query));}
    byte[] orderWithdrawalRead(String payload){
        String path="/v2/providers/openapi/apis/api/v4/vendors/"+vendor+"/returnWithdrawList";
        return writeSession(()->request("POST",path,"",payload.getBytes(StandardCharsets.UTF_8)));
    }
    boolean orderConfigured(){return !vendor.isBlank()&&!access.isBlank()&&!secret.isBlank();}
    String vendorId(){return vendor;}
    String accountKey(){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(vendor.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException("SHA-256 unavailable");}}
    java.time.Instant now(){return clock.instant();}
    <T> T writeSession(java.util.function.Supplier<T> work){
        if(vendor.isBlank()||access.isBlank()||secret.isBlank())throw new MarketplaceFailure(CONFIGURATION);
        enter();try{return work.get();}finally{synchronized(this){running=false;}}
    }
    JsonNode rawProduct(String id){
        if(id==null||!id.matches("[0-9]{1,30}"))throw new InputValidationFailure("잘못된 등록상품 ID입니다.");
        var bytes=request(PATH+"/"+id,"");parseDetail(bytes,id);return json.readTree(bytes).path("data").deepCopy();
    }
    JsonNode rawInventory(String id){
        if(id==null||!id.matches("[0-9]{1,30}"))throw new InputValidationFailure("잘못된 옵션 ID입니다.");
        var bytes=request("/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/"+id+"/inventories","");parseInventory(bytes);return json.readTree(bytes).path("data").deepCopy();
    }
    /** A selected edit can use one confirmed field even if another current field is unavailable. */
    JsonNode rawInventory(String id,boolean requirePrice,boolean requireStock){
        if(id==null||!id.matches("[0-9]{1,30}"))throw new InputValidationFailure("잘못된 옵션 ID입니다.");
        var bytes=request("/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/"+id+"/inventories","");
        return selectedInventory(bytes,requirePrice,requireStock);
    }
    JsonNode selectedInventory(byte[] bytes,boolean requirePrice,boolean requireStock){
        try{
            var root=json.readTree(bytes);if(root==null||!root.isObject()||!root.path("code").isString())throw new MarketplaceFailure(RESPONSE);
            if(!"SUCCESS".equals(root.path("code").asString()))throw new MarketplaceFailure(REJECTED);
            var data=root.path("data");if(!data.isObject()||!data.path("onSale").isBoolean())throw new MarketplaceFailure(RESPONSE);id(data,"sellerItemId",true);
            if(requirePrice||!data.path("salePrice").isMissingNode()&&!data.path("salePrice").isNull())nonnegativeLong(data,"salePrice");
            if(requireStock||!data.path("amountInStock").isMissingNode()&&!data.path("amountInStock").isNull())nonnegativeLong(data,"amountInStock");
            return data.deepCopy();
        }catch(MarketplaceFailure e){throw e;}catch(RuntimeException malformed){throw new MarketplaceFailure(RESPONSE);}
    }
    JsonNode rawSummary(String sku){
        var root=json.readTree(request(PATH+"/external-vendor-sku-codes/"+encode(sku).replace("+","%20"),""));
        if(root==null||!root.isObject()||!"SUCCESS".equals(root.path("code").asString())||!root.path("data").isArray())throw new MarketplaceFailure(RESPONSE);
        for(var item:root.path("data"))if(!vendor.equals(requiredText(item,"vendorId"))||id(item,"sellerProductId",true)==null)throw new MarketplaceFailure(RESPONSE);
        return root.path("data");
    }
    /** Only an explicit HTTP 429 can be repeated; all ambiguous write failures remain ambiguous. */
    CoupangHttpTransport.Response write(String method,String path,String query,String payload){
        if(!Set.of("POST","PUT").contains(method)||!path.startsWith(PATH)&&!path.startsWith("/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/"))throw new InputValidationFailure("쿠팡 전송 경로를 확인해 주세요.");
        byte[] body=payload==null?null:payload.getBytes(StandardCharsets.UTF_8);
        if(body!=null&&body.length>5*1024*1024)throw new InputValidationFailure("쿠팡 전송 내용은 5MiB 이하로 줄여 주세요.");
        for(int attempt=0;attempt<2;attempt++){
            paceNextRequest();
            var response=transport.send(method,path,query,CoupangSignature.authorization(access,secret,clock.instant(),method,path,query),body,()->Thread.currentThread().isInterrupted());
            if(response.status()==429){
                var delay=retryDelay(response.retryAfter(),clock.instant());synchronized(this){cooldownUntil=Math.max(cooldownUntil,System.nanoTime()+delay.toNanos());}
                if(attempt==0&&delay.compareTo(java.time.Duration.ofSeconds(30))<=0)continue;
            }
            return response;
        }
        throw new MarketplaceFailure(RATE_LIMIT);
    }
    public ProductPage products(int size,String token){return products(size,token,ProductSearch.empty());}
    public ProductPage products(int size,String token,ProductSearch search){
        String filters=searchQuery(search);
        if(!Set.of(10,50,100).contains(size))throw new InputValidationFailure("페이지 크기는 10·50·100 중 선택해 주세요.");
        if(token==null)token="";
        if(token.length()>512||token.codePoints().anyMatch(Character::isISOControl))throw new InputValidationFailure("잘못된 다음 페이지 토큰입니다.");
        if(vendor.isBlank()||access.isBlank()||secret.isBlank())throw new MarketplaceFailure(CONFIGURATION);
        enter();
        try {
            String query="vendorId="+encode(vendor)+"&maxPerPage="+size+(token.isEmpty()?"":"&nextToken="+encode(token))+filters;
            var body=request(PATH,query);
            ProductPage page=parse(body);
            LoggerFactory.getLogger(CoupangProductClient.class).info("[COUPANG_PRODUCTS] http=200 code=SUCCESS count={} hasNext={}",page.items().size(),page.hasNext());
            return page;
        }finally{ synchronized(this){running=false;} }
    }
    private record LoadedProduct(ProductDetail detail, JsonNode source) {}
    public ProductDetail product(String productId){return loadProduct(productId).detail();}
    private LoadedProduct loadProduct(String productId){
        if(productId==null||!productId.matches("[0-9]{1,30}"))throw new InputValidationFailure("잘못된 등록상품 ID입니다.");
        if(vendor.isBlank()||access.isBlank()||secret.isBlank())throw new MarketplaceFailure(CONFIGURATION);
        enter();
        try{
            var bytes=request(PATH+"/"+productId,"");
            var detail=parseDetail(bytes,productId);
            var source=json.readTree(bytes).path("data");
            var items=new ArrayList<ProductOption>();
            var results=new HashMap<String,ProductOption>();
            String stoppedError=null;
            for(var item:detail.items()){
                if(item.vendorItemId()==null){items.add(item.withCurrent(null,"옵션 ID가 없어 현재 값을 조회할 수 없습니다."));continue;}
                if(results.containsKey(item.vendorItemId())){
                    var previous=results.get(item.vendorItemId());items.add(item.withCurrent(previous.current(),previous.currentError()));continue;
                }
                if(stoppedError!=null){items.add(item.withCurrent(null,stoppedError));continue;}
                ProductOption updated;
                try{
                    var current=parseInventory(request("/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/"+item.vendorItemId()+"/inventories",""));
                    updated=item.withCurrent(current,null);
                    LoggerFactory.getLogger(CoupangProductClient.class).info("[COUPANG_INVENTORY] http=200 code=SUCCESS");
                }catch(MarketplaceFailure failure){
                    if(Thread.currentThread().isInterrupted()||failure.kind()==CANCELLED)throw failure;
                    if(Set.of(AUTHENTICATION,PERMISSION,RATE_LIMIT).contains(failure.kind()))stoppedError=failure.getMessage();
                    updated=item.withCurrent(null,failure.getMessage());
                }
                results.put(item.vendorItemId(),updated);items.add(updated);
            }
            return new LoadedProduct(detail.withItems(items),source);
        }
        finally{synchronized(this){running=false;}}
    }
    public EditorDocument editor(String productId){
        var loaded=loadProduct(productId);
        return editorDocument(loaded.detail(),loaded.source());
    }
    /** Projects an already validated server response without making another external request. */
    EditorDocument editorDocument(JsonNode source,Map<String,CurrentInventory> current,Map<String,String> errors){
        var envelope=json.createObjectNode();envelope.put("code","SUCCESS");envelope.set("data",source);
        var detail=parseDetail(json.writeValueAsBytes(envelope),id(source,"sellerProductId",true));
        var items=new ArrayList<ProductOption>();
        for(var item:detail.items())items.add(item.withCurrent(item.vendorItemId()==null?null:current.get(item.vendorItemId()),item.vendorItemId()==null?"옵션 ID가 없어 현재 값을 조회할 수 없습니다.":errors.get(item.vendorItemId())));
        return editorDocument(detail.withItems(items),source);
    }
    private EditorDocument editorDocument(ProductDetail d,JsonNode source){
        var p=d.product();
        var options=new ArrayList<EditOption>();
        for(int n=0;n<d.items().size();n++){
            var i=d.items().get(n);var raw=source.path("items").get(n);
            var registration=new ArrayList<Field>(i.settings());
            registration.addAll(fields(raw,new String[]{"autoPricingInfo.minSalePrice","autoPricingInfo.active"}));
            options.add(new EditOption(i.sellerProductItemId(),i.vendorItemId(),i.itemName(),i.current(),i.currentError(),i.vendorItemId()!=null,i.attributes(),i.images(),i.contents(),i.notices(),List.copyOf(registration),i.certifications()));
        }
        var documents=new ArrayList<Document>();
        for(var row:array(source,"requiredDocuments"))documents.add(new Document(text(row,"templateName"),text(row,"documentPath"),text(row,"vendorDocumentPath")));
        return new EditorDocument(new Basic(p.sellerProductId(),p.productId(),p.sellerProductName(),d.displayProductName(),d.generalProductName(),p.brand(),d.productGroup(),d.displayCategoryCode(),p.statusName()),new Limits(true,true,true),List.copyOf(options),d.delivery(),d.settings(),List.copyOf(documents));
    }
    public cc.ataglace.molebutter.marketplace.api.CoupangBrands.Page brands(String name,int page){
        if(name==null||name.isBlank()||name.length()>200||page<1||page>1_000_000)throw new InputValidationFailure("브랜드 검색어와 페이지를 확인해 주세요.");
        var payload=json.createObjectNode().put("brandName",name.trim()).put("countPerPage",10).put("page",page);
        return writeSession(()->parseBrands(request("POST","/v2/providers/seller_api/apis/api/v1/marketplace/brands/search","",json.writeValueAsBytes(payload))));
    }
    cc.ataglace.molebutter.marketplace.api.CoupangBrands.Page parseBrands(byte[] bytes){
        try{
            var root=json.readTree(bytes);if(root==null||!root.isObject()||!"SUCCESS".equals(root.path("code").asString()))throw new MarketplaceFailure(REJECTED);
            var data=root.path("data");if(!data.isObject()||!data.path("items").isArray())throw new MarketplaceFailure(RESPONSE);
            long page=nonnegativeLong(data,"page"),size=nonnegativeLong(data,"countPerPage"),total=nonnegativeLong(data,"totalCount");
            if(page<1||page>Integer.MAX_VALUE||size<1||size>10||data.path("items").size()>size)throw new MarketplaceFailure(RESPONSE);
            var items=new ArrayList<cc.ataglace.molebutter.marketplace.api.CoupangBrands.Brand>();
            for(var b:data.path("items")){if(!b.isObject()||!b.path("isUIDRequired").isBoolean()||!b.path("allowedUIDTypes").isArray())throw new MarketplaceFailure(RESPONSE);var types=new ArrayList<String>();for(var t:b.path("allowedUIDTypes")){if(!t.isString())throw new MarketplaceFailure(RESPONSE);types.add(t.asString());}items.add(new cc.ataglace.molebutter.marketplace.api.CoupangBrands.Brand(requiredText(b,"brandId"),requiredText(b,"brandName"),b.path("isUIDRequired").asBoolean(),List.copyOf(types)));}
            return new cc.ataglace.molebutter.marketplace.api.CoupangBrands.Page(List.copyOf(items),(int)page,total,page*size<total);
        }catch(MarketplaceFailure e){throw e;}catch(RuntimeException e){throw new MarketplaceFailure(RESPONSE);}
    }
    public cc.ataglace.molebutter.marketplace.api.CoupangShippingPlaces.Page outboundPlace(String code){
        if(code==null||!code.matches("[0-9]{1,30}"))throw new InputValidationFailure("출고지 코드를 확인해 주세요.");
        return writeSession(()->parseShippingPlaces(request("/v2/providers/marketplace_openapi/apis/api/v2/vendor/shipping-place/outbound","placeCodes="+code),false));
    }
    public cc.ataglace.molebutter.marketplace.api.CoupangShippingPlaces.Page shippingPlaces(boolean returns,int page){
        if(page<1||page>1_000_000)throw new InputValidationFailure("주소록 페이지를 확인해 주세요.");
        String path=returns?"/v2/providers/openapi/apis/api/v5/vendors/"+encode(vendor)+"/returnShippingCenters":"/v2/providers/marketplace_openapi/apis/api/v2/vendor/shipping-place/outbound";
        return writeSession(()->parseShippingPlaces(request(path,"pageNum="+page+"&pageSize=50"),returns));
    }
    cc.ataglace.molebutter.marketplace.api.CoupangShippingPlaces.Page parseShippingPlaces(byte[] bytes,boolean returns){
        try{
            var root=json.readTree(bytes);
            if(root==null||!root.isObject())throw new MarketplaceFailure(RESPONSE);
            if(returns&&!"200".equals(root.path("code").asString()))throw new MarketplaceFailure(REJECTED);
            var data=returns?root.path("data"):root;
            if(!data.path("content").isArray()||!data.path("pagination").isObject())throw new MarketplaceFailure(RESPONSE);
            var pagination=data.path("pagination");long page=nonnegativeLong(pagination,"currentPage"),pages=nonnegativeLong(pagination,"totalPages"),total=nonnegativeLong(pagination,"totalElements");
            if(page<1||page>Integer.MAX_VALUE||data.path("content").size()>50)throw new MarketplaceFailure(RESPONSE);
            var items=new ArrayList<cc.ataglace.molebutter.marketplace.api.CoupangShippingPlaces.Place>();
            for(var place:data.path("content")){
                if(!place.isObject()||!place.path("usable").isBoolean()||!place.path("placeAddresses").isArray()||(returns&&!vendor.equals(requiredText(place,"vendorId"))))throw new MarketplaceFailure(RESPONSE);
                String code=id(place,returns?"returnCenterCode":"outboundShippingPlaceCode",true);
                if(code==null)throw new MarketplaceFailure(RESPONSE);
                var addresses=new ArrayList<cc.ataglace.molebutter.marketplace.api.CoupangShippingPlaces.Address>();
                for(var a:place.path("placeAddresses"))addresses.add(new cc.ataglace.molebutter.marketplace.api.CoupangShippingPlaces.Address(requiredText(a,"addressType"),requiredText(a,"returnZipCode"),requiredText(a,"returnAddress"),a.path("returnAddressDetail").asString(""),a.path("companyContactNumber").asString("")));
                items.add(new cc.ataglace.molebutter.marketplace.api.CoupangShippingPlaces.Place(code,requiredText(place,"shippingPlaceName"),place.path("usable").asBoolean(),List.copyOf(addresses)));
            }
            return new cc.ataglace.molebutter.marketplace.api.CoupangShippingPlaces.Page(List.copyOf(items),(int)page,total,page<pages);
        }catch(MarketplaceFailure e){throw e;}catch(RuntimeException e){throw new MarketplaceFailure(RESPONSE);}
    }
    public CategoryRules category(String code){
        if(code==null||!code.matches("[0-9]{1,15}"))throw new InputValidationFailure("유효한 카테고리 코드를 입력해 주세요.");
        if(vendor.isBlank()||access.isBlank()||secret.isBlank())throw new MarketplaceFailure(CONFIGURATION);
        enter();
        try{return parseCategory(request("/v2/providers/seller_api/apis/api/v1/marketplace/meta/category-related-metas/display-category-codes/"+code,""),code);}
        finally{synchronized(this){running=false;}}
    }
    CategoryRules parseCategory(byte[] bytes,String code){
        try{
            var root=json.readTree(bytes);
            if(root==null||!root.isObject()||!root.path("code").isString())throw new MarketplaceFailure(RESPONSE);
            if(!"SUCCESS".equals(root.path("code").asString()))throw new MarketplaceFailure(REJECTED);
            var d=root.path("data");
            if(!d.isObject()||!d.path("attributes").isArray()||!d.path("noticeCategories").isArray()||!d.path("isAllowSingleItem").isBoolean())throw new MarketplaceFailure(RESPONSE);
            var attrs=new ArrayList<AttributeRule>();
            for(var a:array(d,"attributes"))attrs.add(new AttributeRule(requiredText(a,"attributeTypeName"),requiredText(a,"exposed"),requiredText(a,"required"),text(a,"groupNumber"),requiredText(a,"dataType"),text(a,"basicUnit"),strings(a,"usableUnits")));
            var notices=new ArrayList<NoticeCategory>();
            for(var c:array(d,"noticeCategories")){
                var names=new ArrayList<NoticeRule>();for(var f:array(c,"noticeCategoryDetailNames"))names.add(new NoticeRule(requiredText(f,"noticeCategoryDetailName"),requiredText(f,"required")));
                notices.add(new NoticeCategory(requiredText(c,"noticeCategoryName"),List.copyOf(names)));
            }
            var certs=new ArrayList<CertificateRule>();for(var c:array(d,"certifications"))certs.add(new CertificateRule(text(c,"certificationType"),requiredText(c,"name"),requiredText(c,"required"),text(c,"dataType")));
            var docs=new ArrayList<DocumentRule>();for(var f:array(d,"requiredDocumentNames"))docs.add(new DocumentRule(requiredText(f,"templateName"),requiredText(f,"required")));
            return new CategoryRules(code,d.path("isAllowSingleItem").asBoolean(),List.copyOf(attrs),List.copyOf(notices),List.copyOf(certs),List.copyOf(docs),strings(d,"allowedOfferConditions"));
        }catch(MarketplaceFailure e){throw e;}catch(RuntimeException e){throw new MarketplaceFailure(RESPONSE);}
    }
    private static List<String> strings(JsonNode row,String key){
        var a=row.path(key);if(a.isMissingNode()||a.isNull())return List.of();if(!a.isArray())throw new MarketplaceFailure(RESPONSE);
        var result=new ArrayList<String>();for(var v:a){if(!v.isString())throw new MarketplaceFailure(RESPONSE);result.add(v.asString());}return List.copyOf(result);
    }
    private void paceNextRequest(){
        long deadline;
        synchronized(this){deadline=Math.max(started?lastStart+1_000_000_000L:System.nanoTime(),cooldownUntil);}
        if(deadline-System.nanoTime()>java.util.concurrent.TimeUnit.SECONDS.toNanos(30))throw new MarketplaceFailure(RATE_LIMIT);
        waitUntil(deadline);
        synchronized(this){lastStart=System.nanoTime();started=true;}
    }
    CurrentInventory parseInventory(byte[] body){
        try{
            var root=json.readTree(body);
            if(root==null||!root.isObject()||!root.path("code").isString())throw new MarketplaceFailure(RESPONSE);
            if(!"SUCCESS".equals(root.path("code").asString()))throw new MarketplaceFailure(REJECTED);
            var data=root.path("data");
            if(!data.isObject()||!data.path("onSale").isBoolean())throw new MarketplaceFailure(RESPONSE);
            return new CurrentInventory(id(data,"sellerItemId",true),nonnegativeLong(data,"salePrice"),nonnegativeLong(data,"amountInStock"),data.path("onSale").asBoolean());
        }catch(MarketplaceFailure e){throw e;}catch(RuntimeException e){throw new MarketplaceFailure(RESPONSE);}
    }
    private static Long nonnegativeLong(JsonNode row,String key){
        var value=row.path(key);
        if(!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<0)throw new MarketplaceFailure(RESPONSE);
        return value.asLong();
    }
    private byte[] request(String path,String query){return request("GET",path,query,null);}
    private byte[] request(String method,String path,String query,byte[] payload){
        for(int attempt=0;attempt<2;attempt++){
            paceNextRequest();checkCancelled();
            // Each attempt gets a fresh UTC timestamp and signature for the identical wire query.
            var response=transport.send(method,path,query,CoupangSignature.authorization(access,secret,clock.instant(),method,path,query),payload,this::cancelled);
            int status=response.status();
            if(status==200){checkCancelled();return response.body();}
            boolean orderRequest=path.contains("/vendors/")&&(path.endsWith("/ordersheets")||path.contains("/returnRequests")||path.contains("/exchangeRequests")||path.contains("/returnWithdraw"));
            var reason=orderRequest&&status==400?orderRejection(response.body()):null;
            LoggerFactory.getLogger(CoupangProductClient.class).warn("[COUPANG_READ] operation={} http={} attempt={} code={}",orderRequest?"ORDER":"PRODUCT",status,attempt+1,reason==null?"UNAVAILABLE":reason.name());
            if(reason!=null)throw new MarketplaceFailure(reason);
            if(status==429){
                var delay=retryDelay(response.retryAfter(),clock.instant());
                synchronized(this){cooldownUntil=Math.max(cooldownUntil,System.nanoTime()+delay.toNanos());}
                if(attempt==0&&delay.compareTo(java.time.Duration.ofSeconds(30))<=0)continue;
            }
            throw new MarketplaceFailure(switch(status){case 401->AUTHENTICATION;case 403->PERMISSION;case 429->RATE_LIMIT;default->status>=500?UPSTREAM:orderRequest?ORDER_REJECTED:REJECTED;});
        }
        throw new MarketplaceFailure(RATE_LIMIT);
    }
    // Parse only a documented fixed message. Never retain or expose the external body.
    static MarketplaceFailure.Kind orderRejection(byte[] body){
        try{
            var root=new ObjectMapper().readTree(body);
            var message=root.path("message");
            if(message.isString()){
                return switch(message.asString().trim()){
                    case "해당 주문이 취소 또는 반품 되었습니다." -> ORDER_UNAVAILABLE;
                    case "유효하지 않은 주문번호 입니다." -> ORDER_INVALID;
                    case "다른 판매자의 주문을 조회할 수 없습니다." -> ORDER_ACCOUNT_MISMATCH;
                    default -> ORDER_REJECTED;
                };
            }
        }catch(RuntimeException ignored){}
        return ORDER_REJECTED;
    }
    static java.time.Duration retryDelay(String header,java.time.Instant now){
        if(header!=null)try{
            String value=header.trim();long seconds;
            if(value.matches("[0-9]+"))seconds=Long.parseLong(value);
            else seconds=Math.max(0,java.time.Duration.between(now,java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds());
            // A long server cooldown is respected by failing promptly rather than waiting in the UI.
            return java.time.Duration.ofSeconds(Math.min(seconds,86400));
        }catch(RuntimeException ignored){}
        return java.time.Duration.ofSeconds(5);
    }
    ProductDetail parseDetail(byte[] body,String requestedId){
        try {
            var root=json.readTree(body);
            if(root==null||!root.isObject()||!root.path("code").isString())throw new MarketplaceFailure(RESPONSE);
            if(!"SUCCESS".equals(root.path("code").asString()))throw new MarketplaceFailure(REJECTED);
            var row=root.path("data");
            if(!row.isObject()||!vendor.equals(requiredText(row,"vendorId"))||!requestedId.equals(id(row,"sellerProductId",true))||!row.path("items").isArray())throw new MarketplaceFailure(RESPONSE);
            var items=new ArrayList<ProductOption>();
            for(var item:row.path("items")){
                if(!item.isObject())throw new MarketplaceFailure(RESPONSE);
                items.add(parseOption(item));
            }
            var product=new Product(id(row,"sellerProductId",true),requiredText(row,"sellerProductName"),text(row,"brand"),text(row,"statusName"),id(row,"productId",false),text(row,"createdAt"));
            return new ProductDetail(product,text(row,"displayProductName"),text(row,"generalProductName"),text(row,"productGroup"),id(row,"displayCategoryCode",false),id(row,"categoryId",false),text(row,"saleStartedAt"),text(row,"saleEndedAt"),items,fields(row,DELIVERY_FIELDS),fields(row,PRODUCT_FIELDS));
        }catch(MarketplaceFailure e){throw e;}catch(RuntimeException e){throw new MarketplaceFailure(RESPONSE);}
    }
    private static final String[] DELIVERY_FIELDS={"deliveryMethod","deliveryCompanyCode","deliveryChargeType","deliveryCharge","freeShipOverAmount","deliveryChargeOnReturn","remoteAreaDeliverable","unionDeliveryType","outboundShippingPlaceCode","returnCenterCode","returnChargeName","companyContactNumber","returnZipCode","returnAddress","returnAddressDetail","returnCharge"};
    private static final String[] PRODUCT_FIELDS={"saleStartedAt","saleEndedAt","manufacture","brandId","productGroup","vendorUserId","requested","extraInfoMessage","bundleInfo.bundleType"};
    private static final String[] OPTION_FIELDS={"externalVendorSku","originalPrice","salePrice","maximumBuyCount","maximumBuyForPerson","maximumBuyForPersonPeriod","outboundShippingTimeDay","unitCount","adultOnly","taxType","parallelImported","overseasPurchased","pccNeeded","bestPriceGuaranteed3P","barcode","emptyBarcode","emptyBarcodeReason","modelNo","autoPricingInfoView.minSalePrice","autoPricingInfoView.active","offerCondition","offerDescription","searchTags"};
    // Only requested scalar fields and option search tags cross the module contract.
    private static List<Field> fields(JsonNode row,String[] keys){
        var result=new ArrayList<Field>();
        for(String key:keys){
            var v=row;for(String segment:key.split("\\."))v=v.path(segment);
            if("searchTags".equals(key)){
                if(v.isArray()){
                    var values=new ArrayList<String>();for(var tag:v)if(tag.isString())values.add(tag.asString());
                    if(!values.isEmpty())result.add(new Field(key,String.join(", ",values)));
                }
            }else if(v.isString()||v.isNumber()||v.isBoolean())result.add(new Field(key,v.isString()?v.asString():v.toString()));
        }
        return List.copyOf(result);
    }
    private static List<JsonNode> array(JsonNode row,String key){
        var v=row.path(key);if(v.isMissingNode()||v.isNull())return List.of();
        if(!v.isArray())throw new MarketplaceFailure(RESPONSE);
        var values=new ArrayList<JsonNode>();for(var item:v){if(!item.isObject())throw new MarketplaceFailure(RESPONSE);values.add(item);}return values;
    }
    private static List<Image> images(JsonNode row,String key){
        var result=new ArrayList<Image>();
        for(var image:array(row,key)){
            var order=image.path("imageOrder");Integer n=order.isIntegralNumber()&&order.canConvertToInt()?order.asInt():null;
            result.add(new Image(n,text(image,"imageType"),imageUrl(text(image,"cdnPath"),text(image,"vendorPath"))));
        }
        result.sort(Comparator.comparing((Image image)->!"REPRESENTATION".equals(image.type())).thenComparing(Image::order,Comparator.nullsLast(Integer::compareTo)));
        return List.copyOf(result);
    }
    static String imageUrl(String cdn,String vendor){
        if(cdn!=null&&!cdn.isBlank()){
            if(cdn.startsWith("https://")){String valid=https(cdn);if(valid!=null&&java.net.URI.create(valid).getHost().endsWith(".coupangcdn.com"))return valid;}
            else if(cdn.matches("[a-zA-Z0-9_/-]+\\.[a-zA-Z0-9]+")&&!cdn.contains(".."))return "https://img1a.coupangcdn.com/image/"+cdn.replaceFirst("^/+","");
        }
        return https(vendor);
    }
    private static String https(String value){
        if(value==null)return null;
        try{var uri=java.net.URI.create(value);return "https".equals(uri.getScheme())&&uri.getHost()!=null&&uri.getUserInfo()==null?uri.toASCIIString():null;}catch(IllegalArgumentException e){return null;}
    }
    private static ProductOption parseOption(JsonNode item){
        var attrs=new ArrayList<Attribute>();for(var a:array(item,"attributes"))attrs.add(new Attribute(text(a,"attributeTypeName"),text(a,"attributeValueName"),text(a,"exposed")));
        var contents=new ArrayList<Content>();for(var c:array(item,"contents"))for(var d:array(c,"contentDetails"))contents.add(new Content(text(c,"contentsType"),text(d,"detailType"),text(d,"content")));
        var notices=new ArrayList<Notice>();for(var n:array(item,"notices"))notices.add(new Notice(text(n,"noticeCategoryName"),text(n,"noticeCategoryDetailName"),text(n,"content")));
        var certs=new ArrayList<Certification>();for(var c:array(item,"certifications"))certs.add(new Certification(text(c,"certificationType"),text(c,"certificationCode"),images(c,"certificationAttachments")));
        return new ProductOption(id(item,"sellerProductItemId",true),id(item,"vendorItemId",false),text(item,"itemName"),null,null,List.copyOf(attrs),images(item,"images"),List.copyOf(contents),List.copyOf(notices),fields(item,OPTION_FIELDS),List.copyOf(certs));
    }
    static String searchQuery(ProductSearch search){
        if(search==null)search=ProductSearch.empty();
        var values=new LinkedHashMap<String,String>();
        values.put("sellerProductId",clean(search.sellerProductId()));values.put("sellerProductName",clean(search.sellerProductName()));
        values.put("status",clean(search.status()));values.put("createdAt",clean(search.createdAt()));
        String id=values.get("sellerProductId"),name=values.get("sellerProductName"),status=values.get("status"),date=values.get("createdAt");
        if(!id.isEmpty()&&!id.matches("[0-9]{1,30}"))throw new InputValidationFailure("잘못된 등록상품 ID입니다.");
        if(name.codePointCount(0,name.length())>20)throw new InputValidationFailure("등록상품명은 20자 이하로 입력해 주세요.");
        if(!status.isEmpty()&&!Set.of("IN_REVIEW","SAVED","APPROVING","APPROVED","PARTIAL_APPROVED","DENIED","DELETED").contains(status))throw new InputValidationFailure("잘못된 등록상태입니다.");
        if(!date.isEmpty())try{if(!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException();java.time.LocalDate.parse(date);}catch(RuntimeException e){throw new InputValidationFailure("등록일을 yyyy-MM-dd 형식의 유효한 날짜로 입력해 주세요.");}
        var query=new StringBuilder();values.forEach((key,value)->{if(!value.isEmpty())query.append('&').append(key).append('=').append(encode(value));});
        return query.toString();
    }
    private static String clean(String value){
        if(value==null)return "";value=value.trim();
        if(value.length()>512||value.codePoints().anyMatch(Character::isISOControl))throw new InputValidationFailure("검색 조건을 확인해 주세요.");return value;
    }
    private synchronized void enter(){
        checkCancelled();
        if(running)throw new MarketplaceFailure(BUSY);
        running=true;
    }
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    ProductPage parse(byte[] body){
        try {
            var root=json.readTree(body);
            if(root==null||!root.isObject()||!root.path("code").isString())throw new MarketplaceFailure(RESPONSE);
            if(!"SUCCESS".equals(root.path("code").asString()))throw new MarketplaceFailure(REJECTED);
            if(!root.path("data").isArray()||!root.path("nextToken").isString())throw new MarketplaceFailure(RESPONSE);
            var products=new ArrayList<Product>();
            for(var row:root.path("data")){
                if(!row.isObject()||!vendor.equals(requiredText(row,"vendorId")))throw new MarketplaceFailure(RESPONSE);
                products.add(new Product(id(row,"sellerProductId",true),requiredText(row,"sellerProductName"),text(row,"brand"),text(row,"statusName"),id(row,"productId",false),text(row,"createdAt")));
            }
            String token=root.path("nextToken").asString();
            if(token.length()>512||token.codePoints().anyMatch(Character::isISOControl))throw new MarketplaceFailure(RESPONSE);
            return new ProductPage(products,token,!token.isEmpty());
        }catch(MarketplaceFailure e){throw e;}catch(RuntimeException e){throw new MarketplaceFailure(RESPONSE);}
    }
    private static String requiredText(JsonNode row,String key){String value=text(row,key);if(value==null||value.isBlank())throw new MarketplaceFailure(RESPONSE);return value;}
    private static String text(JsonNode row,String key){var v=row.path(key);if(v.isMissingNode()||v.isNull())return null;if(!v.isString())throw new MarketplaceFailure(RESPONSE);return v.asString();}
    private static String id(JsonNode row,String key,boolean required){
        var v=row.path(key);if(!required&&(v.isMissingNode()||v.isNull()))return null;
        String value=v.isIntegralNumber()?v.asString():v.isString()?v.asString():"";
        if(!value.matches("[0-9]+"))throw new MarketplaceFailure(RESPONSE);
        return value;
    }
}
