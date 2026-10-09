package cc.ataglace.molebutter.marketplacecoupang.internal;


import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.MarketplaceFailure;
import cc.ataglace.molebutter.marketplace.api.MarketplaceOrderGateway;
import cc.ataglace.molebutter.marketplace.api.MarketplaceOrders.*;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceFailure.Kind.*;

/** PII never enters the snapshot or claim records; only live detail projects shipping fields. */
@Component
final class CoupangOrderClient implements MarketplaceOrderGateway {
    static final List<String> ORDER_STATUSES=List.of("ACCEPT","INSTRUCT","DEPARTURE","DELIVERING","FINAL_DELIVERY","NONE_TRACKING");
    private final CoupangProductClient client;
    private final ObjectMapper json;
    private final boolean claimsVerified;
    @Autowired
    CoupangOrderClient(CoupangProductClient client,ObjectMapper json,
            @Value("${marketplace.coupang.orders.claims-verified:false}") boolean claimsVerified,
            @Value("${marketplace.coupang.orders.claims-verification-reference:}") String verificationReference){
        this.client=client;this.json=json;this.claimsVerified=claimsVerified&&verificationReference!=null&&!verificationReference.isBlank();
    }
    CoupangOrderClient(CoupangProductClient client,ObjectMapper json,boolean claimsVerified){this(client,json,claimsVerified,"synthetic-test");}
    record ProbeResult(String stream,int count,boolean complete){}
    /** Explicit runtime diagnostic only; no public HTTP route, no stored raw response or gate modification. */
    List<ProbeResult> probeClaimContracts(LocalDate day){
        var diagnostic=new CoupangOrderClient(client,json,true,"explicit-runtime-probe");
        var results=new ArrayList<ProbeResult>();
        for(String stream:List.of("RETURN_RU","RETURN_UC","RETURN_CC","RETURN_PR","CANCEL","EXCHANGE","WITHDRAWN")){
            String cursor=null;var seen=new HashSet<String>();int count=0;int pages=0;
            do{
                if(++pages>1000)throw new MarketplaceFailure(RESPONSE);
                var page=diagnostic.fetch(stream,day,day,cursor);count+=page.claims().size();cursor=page.nextCursor();
                if(cursor!=null&&!seen.add(cursor))throw new MarketplaceFailure(RESPONSE);
            }while(cursor!=null);
            results.add(new ProbeResult(stream,count,true));
        }
        return List.copyOf(results);
    }
    public String market(){return "COUPANG";}
    public boolean orderStream(String stream){return ORDER_STATUSES.contains(stream);}
    public String accountKey(){return client.accountKey();}
    public boolean configured(){return client.orderConfigured();}
    public boolean claimsVerified(){return claimsVerified;}
    public List<String> streams(){
        var streams=new ArrayList<>(ORDER_STATUSES);
        if(claimsVerified)streams.addAll(List.of("RETURN_RU","RETURN_UC","RETURN_CC","RETURN_PR","CANCEL"));
        else streams.add("CLAIMS_UNVERIFIED");
        streams.addAll(List.of("EXCHANGE","WITHDRAWN"));
        return List.copyOf(streams);
    }
    public Page fetch(String stream,LocalDate from,LocalDate to,String cursor){
        if(Thread.currentThread().isInterrupted())throw new MarketplaceFailure(CANCELLED);
        window(from,to,7);cursor=checkedCursor(cursor);
        if("CLAIMS_UNVERIFIED".equals(stream))return new Page(List.of(),List.of(),null,false,"반품·취소 일단위 조회 계약이 확인되지 않아 전체 상태 수집을 완료할 수 없습니다.");
        if(ORDER_STATUSES.contains(stream)){
            String query="createdAtFrom="+encode(from+"+09:00")+"&createdAtTo="+encode(to+"+09:00")+"&status="+stream+"&maxPerPage=50"+token(cursor);
            return parseOrders(client.orderRead(path(5,"ordersheets"),query),null,false);
        }
        if(stream.startsWith("RETURN_")&&Set.of("RU","UC","CC","PR").contains(stream.substring(7))||"CANCEL".equals(stream)){
            requireClaims();
            // The documented day mode does not name a searchType value. Gate stays false until verified.
            String type="CANCEL".equals(stream)?"CANCEL":"RETURN";
            String query="createdAtFrom="+from+"&createdAtTo="+to+"&cancelType="+type+"&maxPerPage=50"+token(cursor);
            if("RETURN".equals(type))query+="&status="+stream.substring(7);
            return parseClaims(client.orderRead(path(6,"returnRequests"),query),type);
        }
        if("EXCHANGE".equals(stream)){
            String query="createdAtFrom="+encode(from+"T00:00:00")+"&createdAtTo="+encode(to+"T23:59:59")+"&maxPerPage=50"+token(cursor);
            return parseClaims(client.orderRead(path(4,"exchangeRequests"),query),"EXCHANGE");
        }
        if("WITHDRAWN".equals(stream)){
            if(!cursor.isEmpty()&&!cursor.matches("[1-9][0-9]{0,8}"))throw new InputValidationFailure("조회 페이지를 확인해 주세요.");
            String query="dateFrom="+from+"&dateTo="+to+"&sizePerPage=100&pageIndex="+(cursor.isEmpty()?"1":cursor);
            return parseClaims(client.orderRead(path(4,"returnWithdrawRequests"),query),"WITHDRAWN");
        }
        throw new InputValidationFailure("주문 조회 구분을 확인해 주세요.");
    }
    public Snapshot snapshot(String orderId){
        var page=parseOrders(client.orderRead(orderPath(orderId),""),orderId,true);
        if(page.orders().size()!=1)throw new MarketplaceFailure(RESPONSE);
        return page.orders().getFirst();
    }
    public Detail detail(String orderId){return liveDetail(orderId);}
    public Detail liveDetail(String orderId){
        byte[] bytes=client.orderRead(orderPath(orderId),"");
        var page=parseOrders(bytes,orderId,true);var shipments=new ArrayList<ShipmentDetail>();
        for(var row:envelope(bytes)){
            var receiver=row.path("receiver");
            if(!receiver.isObject())throw new MarketplaceFailure(RESPONSE);
            shipments.add(new ShipmentDetail(id(row,"shipmentBoxId",true),text(row,"status"),text(receiver,"name"),text(receiver,"safeNumber"),
                    text(receiver,"postCode"),text(receiver,"addr1"),text(receiver,"addr2"),text(row,"deliveryCompanyName"),text(row,"invoiceNumber"),text(row,"parcelPrintMessage")));
        }
        return new Detail(orderId,shipments,page.orders().getFirst().items());
    }
    public Page returnClaim(String receiptId){
        requireClaims();var page=parseClaims(client.orderRead(path(6,"returnRequests/"+checkedId(receiptId)),""),"RETURN",false);
        if(page.claims().isEmpty()||page.claims().stream().anyMatch(claim->!receiptId.equals(claim.summary().id())))throw new MarketplaceFailure(RESPONSE);
        return page;
    }
    public Page withdrawalClaims(List<String> receiptIds){
        if(receiptIds==null||receiptIds.isEmpty()||receiptIds.size()>50)throw new InputValidationFailure("철회 조회 번호는 1~50개여야 합니다.");
        String values=receiptIds.stream().map(CoupangOrderClient::checkedId).map(value->new java.math.BigInteger(value).toString()).collect(java.util.stream.Collectors.joining(","));
        var result=parseClaims(client.orderWithdrawalRead("{\"cancelIds\":["+values+"]}"),"WITHDRAWN",false);
        if(result.claims().stream().anyMatch(claim->!receiptIds.contains(claim.summary().id())))throw new MarketplaceFailure(RESPONSE);
        return result;
    }
    public Page exchangeForOrder(LocalDate from,LocalDate to,String cursor,String orderId){
        window(from,to,7);
        String query="createdAtFrom="+encode(from+"T00:00:00")+"&createdAtTo="+encode(to+"T23:59:59")+"&orderId="+checkedId(orderId)+"&maxPerPage=50"+token(checkedCursor(cursor));
        return parseClaims(client.orderRead(path(4,"exchangeRequests"),query),"EXCHANGE");
    }
    private void requireClaims(){if(!claimsVerified)throw new MarketplaceFailure(CONFIGURATION);}
    private String path(int version,String suffix){return "/v2/providers/openapi/apis/api/v"+version+"/vendors/"+client.vendorId()+"/"+suffix;}
    private String orderPath(String orderId){return path(5,checkedId(orderId)+"/ordersheets");}
    static void window(LocalDate from,LocalDate to,int days){if(from==null||to==null||to.isBefore(from)||ChronoUnit.DAYS.between(from,to)>=days)throw new InputValidationFailure("주문 조회 기간을 확인해 주세요.");}
    private static String checkedId(String value){if(value==null||!value.matches("[0-9]{1,30}"))throw new InputValidationFailure("주문 식별자를 확인해 주세요.");return value;}
    private static String checkedCursor(String value){if(value==null)return "";if(value.length()>1024||value.codePoints().anyMatch(Character::isISOControl))throw new InputValidationFailure("조회 페이지를 확인해 주세요.");return value;}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static String token(String value){return value.isEmpty()?"":"&nextToken="+encode(value);}
    private JsonNode root(byte[] bytes){
        try{
            var root=json.readTree(bytes);if(root==null||!root.isObject())throw new MarketplaceFailure(RESPONSE);
            var code=root.path("code");
            if(!code.isIntegralNumber()&&!code.isString())throw new MarketplaceFailure(RESPONSE);
            if(!"200".equals(code.asString()))throw new MarketplaceFailure(ORDER_REJECTED);
            if(!root.path("data").isArray())throw new MarketplaceFailure(RESPONSE);
            return root;
        }catch(MarketplaceFailure e){throw e;}catch(RuntimeException e){throw new MarketplaceFailure(RESPONSE);}
    }
    private JsonNode envelope(byte[] bytes){return root(bytes).path("data");}
    Page parseOrders(byte[] bytes,String requestedOrder,boolean full){
        var root=root(bytes);var grouped=new LinkedHashMap<String,List<OrderRow>>();var keys=new HashSet<String>();
        for(var row:root.path("data")){
            if(!row.isObject())throw new MarketplaceFailure(RESPONSE);
            String order=id(row,"orderId",true),box=id(row,"shipmentBoxId",true);
            if(requestedOrder!=null&&!requestedOrder.equals(order))throw new MarketplaceFailure(RESPONSE);
            // Only an actual vendorId is comparable to this account; sellerId has a different meaning.
            if(!row.path("vendorId").isMissingNode()&&!row.path("vendorId").isNull()&&!client.vendorId().equals(text(row,"vendorId")))throw new MarketplaceFailure(RESPONSE);
            String status=text(row,"status");if(status==null||status.isBlank())throw new MarketplaceFailure(RESPONSE);
            var items=row.path("orderItems");if(!items.isArray()||items.isEmpty())throw new MarketplaceFailure(RESPONSE);
            var target=grouped.computeIfAbsent(order,ignored->new ArrayList<>());
            for(var item:items){
                if(!item.isObject())throw new MarketplaceFailure(RESPONSE);
                String vendorItem=id(item,"vendorItemId",true),sequence=text(item,"sequenceNo");
                if(sequence!=null&&!sequence.matches("[0-9]{3}"))throw new MarketplaceFailure(RESPONSE);
                if(!keys.add(order+"/"+box+"/"+sequence+"/"+vendorItem))throw new MarketplaceFailure(RESPONSE);
                target.add(new OrderRow("COUPANG",order,box,sequence,vendorItem,id(item,"sellerProductId",false),
                    text(item,"externalVendorSkuCode"),text(item,"sellerProductName"),text(item,"sellerProductItemName"),status,count(item,"shippingCount",true),
                    count(item,"cancelCount",false),count(item,"holdCountForCancel",false),money(item,"salesPrice"),money(item,"orderPrice"),
                    timestamp(row,"orderedAt"),timestamp(row,"paidAt"),client.now().toString(),null,List.of()));
            }
        }
        if(full&&grouped.isEmpty())throw new MarketplaceFailure(RESPONSE);
        var snapshots=new ArrayList<Snapshot>();grouped.forEach((key,value)->snapshots.add(new Snapshot(key,value,full)));
        String next=full?null:responseToken(root,"nextToken",true);
        return new Page(snapshots,List.of(),next,true,null);
    }
    Page parseClaims(byte[] bytes,String type){return parseClaims(bytes,type,true);}
    Page parseClaims(byte[] bytes,String type,boolean paginated){
        var root=root(bytes);var claims=new ArrayList<ClaimObservation>();
        for(var row:root.path("data")){
            if(!row.isObject())throw new MarketplaceFailure(RESPONSE);
            if(!row.path("vendorId").isMissingNode()&&!row.path("vendorId").isNull()&&!client.vendorId().equals(text(row,"vendorId")))throw new MarketplaceFailure(RESPONSE);
            String order=id(row,"orderId",true),claim=id(row,"EXCHANGE".equals(type)?"exchangeId":"WITHDRAWN".equals(type)?"cancelId":"receiptId",true);
            String receiptType=text(row,"receiptType");
            if(Set.of("RETURN","CANCEL").contains(type)&&receiptType!=null&&!type.equals(receiptType))throw new MarketplaceFailure(RESPONSE);
            String status="WITHDRAWN".equals(type)?"WITHDRAWN":text(row,"EXCHANGE".equals(type)?"exchangeStatus":"receiptStatus");
            if(status==null||status.isBlank())throw new MarketplaceFailure(RESPONSE);
            String updated=timestamp(row,"WITHDRAWN".equals(type)?"createdAt":"modifiedAt");
            String storedType="WITHDRAWN".equals(type)?"RETURN":type;
            var items=row.path("EXCHANGE".equals(type)?"exchangeItemDtoV1s":"WITHDRAWN".equals(type)?"vendorItemIds":"returnItems");
            if(!items.isArray())throw new MarketplaceFailure(RESPONSE);
            if(items.isEmpty())claims.add(new ClaimObservation(order,null,null,new ClaimSummary(storedType,claim,status,null,updated,false),timestamp(row,"createdAt")));
            for(var item:items){
                String box=null,vendorItem;Long quantity=null;
                if("WITHDRAWN".equals(type)){vendorItem=scalarId(item,true);}
                else{
                    if(!item.isObject())throw new MarketplaceFailure(RESPONSE);
                    box=id(item,"EXCHANGE".equals(type)?"originalShipmentBoxId":"shipmentBoxId",false);
                    vendorItem=id(item,"EXCHANGE".equals(type)?"orderItemId":"vendorItemId",true);
                    quantity=count(item,"EXCHANGE".equals(type)?"quantity":"cancelCount",false);
                }
                claims.add(new ClaimObservation(order,box,vendorItem,new ClaimSummary(storedType,claim,status,quantity,updated,false,updated!=null,
                    Set.of("RETURN","CANCEL").contains(type)?text(item,"sellerProductName"):null,
                    Set.of("RETURN","CANCEL").contains(type)?text(item,"vendorItemName"):null,
                    Set.of("RETURN","CANCEL").contains(type)?id(item,"sellerProductId",false):null,
                    vendorItem,box,Set.of("RETURN","CANCEL").contains(type)?count(item,"purchaseCount",false):null,timestamp(row,"createdAt")),timestamp(row,"createdAt")));
            }
        }
        return new Page(List.of(),claims,paginated?responseToken(root,"WITHDRAWN".equals(type)?"nextPageIndex":"nextToken",true):null,true,null);
    }
    private static String responseToken(JsonNode root,String field,boolean required){
        var value=root.path(field);if(value.isNull()||value.isMissingNode()){if(required&&value.isMissingNode())throw new MarketplaceFailure(RESPONSE);return null;}
        if(!value.isString())throw new MarketplaceFailure(RESPONSE);
        String token=value.asString();if(token.length()>1024||token.codePoints().anyMatch(Character::isISOControl))throw new MarketplaceFailure(RESPONSE);
        return token.isEmpty()?null:token;
    }
    private static String timestamp(JsonNode row,String key){
        String value=text(row,key);if(value==null||value.isBlank())return null;
        // Reject malformed metadata before it can be mistaken for an older/newer observation.
        String iso=value.replace(' ','T');
        try{java.time.OffsetDateTime.parse(iso);return value;}catch(java.time.format.DateTimeParseException ignored){}
        try{java.time.LocalDateTime.parse(iso);return value;}catch(java.time.format.DateTimeParseException invalid){throw new MarketplaceFailure(RESPONSE);}
    }
    private static String text(JsonNode row,String key){var value=row.path(key);if(value.isNull()||value.isMissingNode())return null;if(!value.isString())throw new MarketplaceFailure(RESPONSE);String text=value.asString();if(text.length()>4096)throw new MarketplaceFailure(RESPONSE);return text;}
    private static String id(JsonNode row,String key,boolean required){return scalarId(row.path(key),required);}
    private static String scalarId(JsonNode value,boolean required){if(value.isMissingNode()||value.isNull()){if(required)throw new MarketplaceFailure(RESPONSE);return null;}if(!value.isString()&&!value.isIntegralNumber())throw new MarketplaceFailure(RESPONSE);String id=value.asString();if(!id.matches("[0-9]{1,30}"))throw new MarketplaceFailure(RESPONSE);return id;}
    private static Long count(JsonNode row,String key,boolean required){var value=row.path(key);if(value.isMissingNode()||value.isNull()){if(required)throw new MarketplaceFailure(RESPONSE);return null;}if(!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<0)throw new MarketplaceFailure(RESPONSE);return value.asLong();}
    private static Money money(JsonNode row,String key){
        var value=row.path(key);if(value.isMissingNode()||value.isNull())return null;if(!value.isObject())throw new MarketplaceFailure(RESPONSE);
        String currency=text(value,"currencyCode");var units=value.path("units");var nanos=value.path("nanos");
        if(currency==null||!currency.matches("[A-Z]{3}")||!units.isIntegralNumber()||!nanos.isIntegralNumber()||!nanos.canConvertToLong()||nanos.asLong() < -999999999 || nanos.asLong() > 999999999)throw new MarketplaceFailure(RESPONSE);
        try{return new Money(currency,new BigDecimal(units.asString()).add(BigDecimal.valueOf(nanos.asLong(),9)).stripTrailingZeros().toPlainString());}catch(RuntimeException e){throw new MarketplaceFailure(RESPONSE);}
    }
}
