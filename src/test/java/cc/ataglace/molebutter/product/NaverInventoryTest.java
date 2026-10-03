package cc.ataglace.molebutter.product;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;
import cc.ataglace.molebutter.infra.product.*;
import cc.ataglace.molebutter.service.product.*;

class NaverInventoryTest {
    MallOptionParser parser=new MallOptionParser(new ObjectMapper());
    String fixture(String id)throws Exception{try(var in=getClass().getResourceAsStream("/product/naver-inventory-"+id+".json")){return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}}
    SourceDetails parse(String body){return parser.details(Mall.NAVER_SMART_STORE,"{\"_id\":\"42\",\"contents\":{\"id\":42,"+body+"}}","42");}
    @Test void simpleGiftChoicesShareOneProductStockAndPreserveStore()throws Exception{
        var data=parser.details(Mall.NAVER_SMART_STORE,fixture("10481417934"),"10481417934");
        assertThat(data.storeEvidence().retailer()).isEqualTo("롯데백화점");assertThat(data.storeEvidence().name()).isEqualTo("잠실점");
        assertThat(data.options()).containsExactly(new SourceOption("10481417934","상품 전체",50L,"AVAILABLE","PRODUCT"));
        assertThat(data.optionsComplete()).isTrue();
        var supplier=new SupplierResult(new Offer("nv","","", "10481417934","https://shopping.naver.com/window-products/department/10481417934",1000L,0L,Mall.NAVER_SMART_STORE,null),new CodeMatch("SEARCH_RESULT",null,null,null,null,null),"CONFIRMED",data.options(),null);
        assertThat(SupplierRecommendationPolicy.soldOut(supplier)).isFalse();
        assertThat(ProductLookupService.summarize(List.of(supplier),true,java.time.LocalDateTime.now()).status()).isEqualTo("SUCCESS");
    }
    @Test void simpleStockKeepsZeroUnknownAndUnavailableDistinct()throws Exception{
        var json=new ObjectMapper();
        for(String stock:List.of("0","null","-1","1.5","true","\"숨김\"")){
            var root=json.readTree(fixture("10481417934"));((tools.jackson.databind.node.ObjectNode)root.path("contents")).set("stockQuantity",json.readTree(stock));
            var d=parser.details(Mall.NAVER_SMART_STORE,json.writeValueAsString(root),"10481417934");
            assertThat(d.options()).hasSize(1);assertThat(d.options().getFirst().stockScope()).isEqualTo("PRODUCT");
            assertThat(d.options().getFirst().stock()).isEqualTo(stock.equals("0")?0L:null);
            assertThat(d.options().getFirst().state()).isEqualTo(stock.equals("0")?"SOLD_OUT":"STOCK_UNKNOWN");
            var s=new SupplierResult(null,new CodeMatch("SEARCH_RESULT",null,null,null,null,null),"CONFIRMED",d.options(),null);
            assertThat(SupplierRecommendationPolicy.soldOut(s)).isEqualTo(stock.equals("0"));
        }
        String sample=fixture("10481417934");
        assertThat(parser.details(Mall.NAVER_SMART_STORE,sample.replace("\"stockQuantity\": 50","\"stockQuantity\": 50, \"useExternalStock\": true"),"10481417934").options().getFirst().stock()).isNull();
        assertThat(parser.details(Mall.NAVER_SMART_STORE,sample.replace("\"SALE\"","\"SUSPENSION\""),"10481417934").options().getFirst().state()).isEqualTo("UNAVAILABLE");
        assertThat(parser.details(Mall.NAVER_SMART_STORE,sample,"wrong").options()).isEmpty();
    }
    @Test void ambiguousSimpleDefinitionsNeverFallBackToTotal()throws Exception{
        String sample=fixture("10481417934");
        for(String changed:List.of(sample.replace("\"SIMPLE\"","\"COMBINATION\""),sample.replace("\"optionStandards\": []","\"optionStandards\": [{}]"),sample.replace("\"optionCombinations\": []","\"optionCombinations\": {}"),sample.replace("\"optionUsable\": true","\"optionUsable\": false"),sample.replace("\"optionUsable\": true","\"optionUsable\": true, \"optionInfo\": {\"options\":[]}"),sample.replace("10862805347","10862805346"))){
            var d=parser.details(Mall.NAVER_SMART_STORE,changed,"10481417934");assertThat(d.options()).isEmpty();assertThat(d.optionsComplete()).isFalse();
        }
    }
    @Test void stockScopeIsOptionalForHistoricalJson(){
        var json=new ObjectMapper();
        for(String suffix:List.of("",",\"stockScope\":null")){
            var old=json.readValue("{\"id\":\"1\",\"label\":\"FREE\",\"stock\":3,\"state\":\"AVAILABLE\""+suffix+"}",SourceOption.class);
            assertThat(old).isEqualTo(new SourceOption("1","FREE",3L,"AVAILABLE"));
        }
        var product=new SourceOption("42","상품 전체",50L,"AVAILABLE","PRODUCT");
        assertThat(json.readValue(json.writeValueAsString(product),SourceOption.class)).isEqualTo(product);
    }
    @Test void researchSamplesExposeOnlyTheirOwnOptionQuantities()throws Exception{
        var single=parser.details(Mall.NAVER_SMART_STORE,fixture("10513327648"),"10513327648");assertThat(single.options()).containsExactly(new SourceOption("10513327648","단일상품",41L,"AVAILABLE"));
        var hazzys=parser.details(Mall.NAVER_SMART_STORE,fixture("12610379894"),"12610379894");assertThat(hazzys.options()).containsExactly(new SourceOption("53129032179","FREE",3L,"AVAILABLE"));assertThat(hazzys.modelCode()).isEqualTo("HIWA6E450BK");
        var daks=parser.details(Mall.NAVER_SMART_STORE,fixture("13197489089"),"13197489089");assertThat(daks.options()).containsExactly(new SourceOption("56549039311","FREE",2L,"AVAILABLE"));assertThat(daks.storeEvidence().name()).isEqualTo("닥스 DAKS");assertThat(daks.optionsComplete()).isTrue();
        assertThat(parser.details(Mall.NAVER_SMART_STORE,fixture("13197489089"),"other").options()).isEmpty();
        assertThat(parser.details(Mall.NAVER_SMART_STORE,fixture("13197489089").replace("\"id\": 13197489089","\"id\": 999"),"13197489089").options()).isEmpty();
    }
    @Test void absentInvalidOrExternalStockIsUnknownAndUnavailableDoesNotInventZero(){
        for(String quantity:List.of("null","-1","1.5","true","\"숨김\"","9223372036854775808")){
            var d=parse("\"productStatusType\":\"SALE\",\"optionUsable\":false,\"stockQuantity\":"+quantity);
            assertThat(d.options().getFirst().stock()).isNull();assertThat(d.options().getFirst().state()).isEqualTo("STOCK_UNKNOWN");
        }
        assertThat(parse("\"productStatusType\":\"SALE\",\"optionUsable\":false,\"stockQuantity\":0").options().getFirst().state()).isEqualTo("SOLD_OUT");
        assertThat(parse("\"productStatusType\":\"SUSPENSION\",\"optionUsable\":false,\"stockQuantity\":4").options().getFirst()).isEqualTo(new SourceOption("42","단일상품",4L,"UNAVAILABLE"));
        assertThat(parse("\"productStatusType\":\"SALE\",\"useExternalStock\":true,\"optionUsable\":false,\"stockQuantity\":99").options().getFirst().stock()).isNull();
        assertThat(parse("\"optionUsable\":false,\"stockQuantity\":4").options().getFirst().state()).isEqualTo("STOCK_UNKNOWN");
    }
    @Test void explicitCombinationsNeverUseTotalsAndAmbiguityIsPartial(){
        var d=parse("""
            "productStatusType":"SALE","optionUsable":true,"stockQuantity":999,"optionCombinations":[
            {"id":1,"optionName1":"블랙","optionName2":"M","stockQuantity":3},
            {"id":2,"optionName1":"블랙","optionName2":"L"},
            {"id":3,"optionName1":"화이트","usable":false,"stockQuantity":4},
            {"id":4,"optionName1":"충돌","stockQuantity":1},
            {"id":4,"optionName1":"충돌","stockQuantity":2},
            {"id":5,"stockQuantity":9}]
            """);
        assertThat(d.options()).hasSize(3);assertThat(d.optionsComplete()).isFalse();assertThat(d.options().get(0)).isEqualTo(new SourceOption("1","블랙 / M",3L,"AVAILABLE"));assertThat(d.options().get(1).stock()).isNull();assertThat(d.options().get(2).state()).isEqualTo("UNAVAILABLE");
        assertThat(parse("\"stockQuantity\":999").options()).isEmpty();
        assertThat(parse("\"optionUsable\":true,\"stockQuantity\":999,\"optionCombinations\":[]").optionsComplete()).isFalse();
        assertThat(parse("\"optionUsable\":false,\"optionCombinations\":[{\"id\":1,\"optionName1\":\"FREE\"}]").options()).isEmpty();
    }
    @Test void preferredOfficialLookupUsesInventoryAndSharedResponse()throws Exception{
        var calls=new ArrayList<String>();String payload=fixture("12610379894");
        ProductSourceGateway gateway=new ProductSourceGateway(){public String validateUrl(Mall m,String u){return u;}public List<SourceOption> options(Mall m,String id,String u){throw new AssertionError();}public SourceDetails inspect(Mall m,String id,String u){calls.add(id);return parser.details(m,payload,id);}};
        var info=parser.details(Mall.NAVER_SMART_STORE,payload,"12610379894").storeEvidence();
        var store=new Store("1",Mall.NAVER_SMART_STORE,"BRAND_STORE","헤지스 공식몰","channel",List.of("헤지스 공식몰"),0,null,List.of(new ExternalIdentity(info.namespace(),info.externalId())));
        var prefs=new Preferences(1,List.of(store),List.of(new Rule("1",Mall.NAVER_SMART_STORE,"1",0)));
        var work=new ProductRefreshService.Work(1,2,0,"HIWA450","HIWA6E450BK","LF_ACCESSORY","HAZZYS",prefs,Map.of());
        var offer=new Offer("nv","HIWA6E450BK","헤지스","12610379894","https://shopping.naver.com/window-products/brandfashion/12610379894",94000L,0L,Mall.NAVER_SMART_STORE,null);
        var service=new ProductLookupService(gateway,new ProductTime());var search=new SearchResult(List.of(offer),true,null);
        var result=service.lookup(work,search,()->true);service.lookup(work,search,()->true);
        assertThat(calls).containsExactly("12610379894");assertThat(result.status()).isEqualTo("SUCCESS");assertThat(result.suppliers().getFirst().state()).isEqualTo("CONFIRMED");assertThat(result.suppliers().getFirst().options().getFirst().stock()).isEqualTo(3);
    }

    @Test void declaredCombinationAxesRequireEveryNameButKeepValidRows() {
        String axes="\"options\":[{\"optionType\":\"COMBINATION\",\"groupName\":\"색상\"},{\"optionType\":\"COMBINATION\",\"groupName\":\"사이즈\"}]";
        for(String missing:List.of("\"optionName1\":\"블랙\"","\"optionName2\":\"M\"")) {
            var d=parse("\"productStatusType\":\"SALE\",\"optionUsable\":true,"+axes+",\"optionCombinations\":[{\"id\":1,"+missing+",\"stockQuantity\":5},{\"id\":2,\"optionName1\":\"블랙\",\"optionName2\":\"M\",\"stockQuantity\":0}]");
            assertThat(d.options()).containsExactly(new SourceOption("2","블랙 / M",0L,"SOLD_OUT"));
            assertThat(d.optionsComplete()).isFalse();
        }
        String threeAxes=axes.replace("]",",{\"optionType\":\"COMBINATION\",\"groupName\":\"포장\"}]");
        assertThat(parse(threeAxes+",\"optionCombinations\":[{\"id\":1,\"optionName1\":\"블랙\",\"optionName2\":\"M\",\"stockQuantity\":5}]").optionsComplete()).isFalse();
        var complete=parse("\"productStatusType\":\"SALE\",\"optionUsable\":true,"+axes+",\"optionCombinations\":[{\"id\":1,\"optionName1\":\"블랙\",\"optionName2\":\"M\",\"stockQuantity\":5}]");
        assertThat(complete.optionsComplete()).isTrue();
        assertThat(complete.options().getFirst().label()).isEqualTo("블랙 / M");
    }
    @Test void combinationAxisChecksSupportNestedAndHistoricalDefinitions() {
        String row="{\"id\":1,\"optionName1\":\"FREE\",\"stockQuantity\":5}";
        var nested=parse("\"productStatusType\":\"SALE\",\"optionUsable\":true,\"optionInfo\":{\"options\":[{\"optionType\":\"COMBINATION\",\"groupName\":\"사이즈\"}],\"optionCombinations\":["+row+"]}");
        assertThat(nested.optionsComplete()).isTrue();assertThat(nested.options().getFirst().label()).isEqualTo("FREE");
        var historical=parse("\"productStatusType\":\"SALE\",\"optionCombinations\":["+row+"]");
        assertThat(historical.optionsComplete()).isTrue();
        String contradictory="\"options\":[{\"optionType\":\"COMBINATION\",\"groupName\":\"색상\"}],\"optionInfo\":{\"options\":[{\"optionType\":\"COMBINATION\",\"groupName\":\"색상\"},{\"optionType\":\"COMBINATION\",\"groupName\":\"사이즈\"}]}";
        assertThat(parse(contradictory+",\"optionCombinations\":["+row+"]").optionsComplete()).isFalse();
    }
}
