package cc.ataglace.molebutter.marketplacenaver.internal;
import cc.ataglace.molebutter.marketplacenaver.api.NaverGateway;

import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.*;

@Service
final class DefaultNaverCatalog implements NaverCatalog {
    private final BusinessAccess access;
    private final NaverGateway gateway;
    private final ObjectMapper json;
    DefaultNaverCatalog(BusinessAccess access,NaverGateway gateway,ObjectMapper json){this.access=access;this.gateway=gateway;this.json=json;}
    public ProductPage products(Long actor,Search input){
        access.productActor(actor,true);Search search=input==null?new Search(1,50,null,null):input;
        int page=search.page()==null?1:search.page(),size=search.size()==null?50:search.size();
        if(page<1||page>1_000_000||size<1||size>500)throw new InputValidationFailure("페이지 번호와 크기를 확인해 주세요.");
        var body=json.createObjectNode().put("page",page).put("size",size).put("orderType","NO");
        if(search.keyword()!=null&&!search.keyword().isBlank()){
            DefaultNaverGateway.checkId(search.keyword().trim());body.put("searchKeywordType","PRODUCT_NO");body.putArray("originProductNos").add(Long.parseLong(search.keyword().trim()));
        }
        if(search.sellerManagementCode()!=null&&!search.sellerManagementCode().isBlank()){
            if(body.has("searchKeywordType"))throw new InputValidationFailure("원상품 번호 또는 판매자 관리 코드 중 하나로 검색해 주세요.");
            String code=search.sellerManagementCode().trim();if(code.length()>100||code.codePoints().anyMatch(Character::isISOControl))throw new InputValidationFailure("판매자 관리 코드를 확인해 주세요.");
            body.put("searchKeywordType","SELLER_CODE").put("sellerManagementCode",code);
        }
        return gateway.session(()->page(gateway.search(body),page));
    }
    ProductPage page(JsonNode root,int requestedPage){
        if(!root.path("contents").isArray()||root.path("contents").size()>500)throw response();
        int page=root.path("page").asInt(requestedPage),pages=root.path("totalPages").asInt(0);
        if(page!=requestedPage||pages<0)throw response();var products=new ArrayList<Product>();
        for(var origin:root.path("contents")){
            String originId=DefaultNaverGateway.scalar(origin.path("originProductNo"));if(originId==null)throw response();DefaultNaverGateway.checkId(originId);
            JsonNode channel=null;for(var item:origin.path("channelProducts"))if("STOREFARM".equals(item.path("channelServiceType").asString())||"SMARTSTORE".equals(item.path("channelServiceType").asString())){channel=item;break;}
            if(channel==null)continue;
            products.add(new Product(originId,DefaultNaverGateway.scalar(channel.path("channelProductNo")),DefaultNaverGateway.scalar(origin.path("groupProductNo")),
                channel.path("name").asString(origin.path("name").asString("")),channel.path("statusType").asString(origin.path("statusType").asString("")),
                channel.path("channelProductDisplayStatusType").asString(""),number(channel.path("salePrice")),number(channel.path("stockQuantity"))));
        }
        boolean next=root.path("last").isBoolean()?!root.path("last").asBoolean():page<pages;
        return new ProductPage(List.copyOf(products),page,pages,next);
    }
    public NaverEditor.EditorDocument editor(Long actor,String id){access.productActor(actor,true);return gateway.session(()->NaverEditPatch.editor(gateway.product(id)));}
    public Object metadata(Long actor,String kind,Map<String,String> query){access.productActor(actor,true);return gateway.session(()->gateway.metadata(kind,query));}
    private static Long number(JsonNode value){return value.isIntegralNumber()?value.asLong():null;}
    private static MarketplaceFailure response(){return new MarketplaceFailure(MarketplaceFailure.Kind.RESPONSE,"NAVER");}
}
