package cc.ataglace.molebutter.marketplace.internal;

import java.util.*;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplacenaver.api.NaverGateway;

/** In-memory Naver transport. It has no URL, credentials or network client. */
public final class NaverProductTestGateway implements NaverGateway {
    private final ObjectMapper json=new ObjectMapper();
    private JsonNode source;
    private final List<String> writes=new ArrayList<>();
    private boolean loseResponse;
    private Long stockBeforePut;
    private Long optionBeforePutId,optionBeforePutStock;
    private boolean shoppingAdvertiser=true;
    public String accountKey(){return DefaultMarketplaceSubmissions.account("NAVER:synthetic-product-flow");}
    public <T>T session(Supplier<T> work){return work.get();}
    public JsonNode product(String id){if(source==null)throw new MarketplaceFailure(MarketplaceFailure.Kind.REJECTED,"NAVER");return source.deepCopy();}
    public JsonNode channel(String id){return product(id);}
    public JsonNode search(JsonNode request){var page=json.createObjectNode().put("page",1).put("totalPages",1).put("last",true);var rows=page.putArray("contents");if(source!=null)rows.addObject().put("originProductNo",101);return page;}
    public JsonNode metadata(String kind,Map<String,String> query){return kind.equals("category")?json.createObjectNode().put("id","50000001").put("last",true):json.createArrayNode();}
    public NaverGateway.Response write(String method,String path,JsonNode body){
        writes.add(method+" "+path);
        if(path.endsWith("/option-stock")){
            if(optionBeforePutId!=null){optionStock(optionBeforePutId,optionBeforePutStock);optionBeforePutId=null;optionBeforePutStock=null;}
            for(var changed:body.path("optionInfo").path("optionCombinations"))for(var row:source.path("originProduct").path("detailAttribute").path("optionInfo").path("optionCombinations"))if(row.path("id").asLong()==changed.path("id").asLong())row.asObject().put("stockQuantity",changed.path("stockQuantity").asLong(0)).put("price",changed.path("price").asLong(0)).put("usable",changed.path("usable").asBoolean(true));
            return writeResponse();
        }
        var next=body.deepCopy().asObject();
        if(method.equals("PUT")&&stockBeforePut!=null){source.path("originProduct").asObject().put("stockQuantity",stockBeforePut);stockBeforePut=null;}
        if(source!=null&&!next.path("originProduct").has("stockQuantity"))next.path("originProduct").asObject().set("stockQuantity",source.path("originProduct").path("stockQuantity").deepCopy());
        if(source!=null&&!next.path("originProduct").has("detailContent"))next.path("originProduct").asObject().set("detailContent",source.path("originProduct").path("detailContent").deepCopy());
        next.path("originProduct").asObject().put("originProductNo",101);next.path("smartstoreChannelProduct").asObject().put("channelProductNo",202);
        if(next.path("originProduct").path("stockQuantity").isIntegralNumber()&&next.path("originProduct").path("stockQuantity").asLong()==0)next.path("originProduct").asObject().put("statusType","OUTOFSTOCK");
        long id=701;for(var option:next.path("originProduct").path("detailAttribute").path("optionInfo").path("optionCombinations"))if(!option.has("id"))option.asObject().put("id",id++);
        if(!shoppingAdvertiser)next.path("smartstoreChannelProduct").asObject().put("naverShoppingRegistration",false);
        source=next;
        return writeResponse();
    }
    private NaverGateway.Response writeResponse(){
        if(loseResponse){loseResponse=false;throw new MarketplaceFailure(MarketplaceFailure.Kind.NETWORK,"NAVER");}
        return new NaverGateway.Response(200,"{\"originProductNo\":101,\"smartstoreChannelProductNo\":202}".getBytes(StandardCharsets.UTF_8));
    }
    public String uploadImage(Long actor,NaverEditor.Image image){return "https://shop-phinf.pstatic.net/"+image.id()+".jpg";}
    public JsonNode parse(NaverGateway.Response response){return json.readTree(response.body());}
    public void reset(){source=null;writes.clear();loseResponse=false;stockBeforePut=null;optionBeforePutId=null;optionBeforePutStock=null;shoppingAdvertiser=true;}
    public void seed(NaverEditor.Input input){source=json.createObjectNode();var origin=json.createObjectNode().put("originProductNo",101).put("detailContent",input.description());var channel=json.createObjectNode().put("channelProductNo",202);source.asObject().set("originProduct",origin);source.asObject().set("smartstoreChannelProduct",channel);
        for(var e:input.fields().entrySet())put(source.asObject(),e.getKey(),json.valueToTree(e.getValue()));var images=origin.putObject("images");images.putObject("representativeImage").put("url","https://shop-phinf.pstatic.net/existing.jpg");images.putArray("optionalImages");
        origin.putObject("preservedRemote").put("value","keep-this-remote-field");
        if(input.optionMode().equals("COMBINATION")){
            var detail=origin.path("detailAttribute").asObject();var info=detail.putObject("optionInfo").put("useStockManagement",true);var names=info.putObject("optionCombinationGroupNames");for(int i=0;i<input.optionNames().size();i++)names.put("optionGroupName"+(i+1),input.optionNames().get(i));
            var rows=info.putArray("optionCombinations");long id=701;for(var option:input.options()){var row=rows.addObject().put("id",id++).put("price",option.price()).put("stockQuantity",option.stockQuantity()).put("sellerManagerCode",option.sellerManagerCode()).put("usable",option.usable());for(int i=0;i<option.values().size();i++)row.put("optionName"+(i+1),option.values().get(i));}
        }
    }
    private static void put(tools.jackson.databind.node.ObjectNode root,String path,JsonNode value){
        var parts=path.split("\\.");var parent=root;for(int i=0;i<parts.length-1;i++){var next=parent.path(parts[i]);if(!next.isObject())next=parent.putObject(parts[i]);parent=next.asObject();}parent.set(parts[parts.length-1],value);
    }
    public JsonNode source(){return source.deepCopy();}
    public void omitChannelNumber(){source.asObject().put("originProductNo",101);source.path("smartstoreChannelProduct").asObject().remove("channelProductNo");}
    public void shoppingAdvertiser(boolean enabled){shoppingAdvertiser=enabled;}
    public void channelNumber(long value){source.path("smartstoreChannelProduct").asObject().put("channelProductNo",value);}
    public void salePrice(long value){source.path("originProduct").asObject().put("salePrice",value);}
    public void stockBeforeNextPut(long quantity){stockBeforePut=quantity;}
    public void optionStock(long id,long quantity){for(var row:source.path("originProduct").path("detailAttribute").path("optionInfo").path("optionCombinations"))if(row.path("id").asLong()==id)row.asObject().put("stockQuantity",quantity);}
    public void optionStockBeforeNextPut(long id,long quantity){optionBeforePutId=id;optionBeforePutStock=quantity;}
    public void loseNextResponse(){loseResponse=true;}
    public List<String> writes(){return List.copyOf(writes);}
    @TestConfiguration(proxyBeanMethods=false)
    public static class Configuration {@Bean @Primary public NaverProductTestGateway naverProductTestGateway(){return new NaverProductTestGateway();}}
}
