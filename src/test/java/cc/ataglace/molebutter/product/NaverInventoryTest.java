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

}
