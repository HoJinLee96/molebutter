package cc.ataglace.molebutter.product;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.infra.product.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;

class MallOptionParserTest {
    final ObjectMapper json=new ObjectMapper();final MallOptionParser parser=new MallOptionParser(json);
    String fixture(String name)throws Exception{try(var in=getClass().getResourceAsStream("/product/"+name)){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    @Test void verifiedPublicLotteResponseBindsOnlyItsSpecificSku()throws Exception {
        var options=parser.parse(Mall.LOTTE_ON,fixture("lotte-single.json"),"LE1204272674");
        assertThat(options).hasSize(1);var o=options.getFirst();assertThat(o.id()).isEqualTo("LE1204272674_1222907552");assertThat(o.stock()).isEqualTo(994);

        assertThat(parser.parse(Mall.LOTTE_ON,fixture("lotte-single.json"),"other-product")).isEmpty();
        assertThat(parser.details(Mall.LOTTE_ON,fixture("lotte-single.json").replace("\"optionList\": []","\"optionList\": [{}]"),"LE1204272674").optionsComplete()).isFalse();
    }
    @Test void verifiedHazzysButtonsAreSizeSpecific()throws Exception {
        var options=parser.parse(Mall.HAZZYS,fixture("hazzys-options.html"),"HIBA6F957BK");assertThat(options).hasSize(1);
        assertThat(options.getFirst().id()).isEqualTo("XXX");assertThat(options.getFirst().label()).isEqualTo("블랙 / FREE");assertThat(options.getFirst().stock()).isEqualTo(371);
        assertThat(parser.parse(Mall.HAZZYS,fixture("hazzys-options.html"),"other")).isEmpty();
    }
    @Test void hyundaiAndImallNeverReplaceOptionStockWithProductTotals()throws Exception {
        var hi=parser.parse(Mall.HI_THEHYUNDAI,fixture("hi-options.html"),"2248595115");assertThat(hi).hasSize(1);assertThat(hi.getFirst().stock()).isZero();assertThat(hi.getFirst().id()).isEqualTo("00001");
        var hmall=parser.parse(Mall.HMALL,fixture("hmall-single.json"),"2218395189");assertThat(hmall).hasSize(1);assertThat(hmall.getFirst().stock()).isZero();
        var imall=parser.parse(Mall.LOTTE_IMALL,fixture("imall-options.html"),"3144343598");assertThat(imall).hasSize(1);assertThat(imall.getFirst().stock()).isZero();
        assertThat(parser.parse(Mall.LOTTE_IMALL,"<script>var inv_qty=999;</script>","3144343598")).isEmpty();
    }
    @Test void lfAndNaverMissingFieldsCannotBecomeAvailable() {
        String lf="""
            {"body":{"productOptionDTO":{"productCode":"P","currentStockQuantity":999,"productOptionSizeDTOList":[
              {"sizeCode":"M","sizeName":"M","currentStockQuantity":0},
              {"sizeCode":"L","sizeName":"L","currentStockQuantity":4}]}}}
            """;
        var options=parser.parse(Mall.LFMALL,lf,"P");assertThat(options).hasSize(2);assertThat(options.get(0).stock()).isZero();assertThat(options.get(1).state()).isEqualTo("AVAILABLE");
        String naver="""
            {"id":"42","productStatusType":"SALE","salePrice":10000,"stockQuantity":999,"optionInfo":{"optionCombinations":[
              {"id":"1","optionName1":"검정","optionName2":"L","stockQuantity":0,"price":2000},
              {"id":"2","optionName1":"검정","optionName2":"M","stockQuantity":3,"price":0},
              {"id":"3","optionName1":"검정","optionName2":"S","price":0}]}}
            """;
        var n=parser.parse(Mall.NAVER_SMART_STORE,naver,"42");assertThat(n).hasSize(3);assertThat(n.getFirst().stock()).isZero();
        assertThat(parser.parse(Mall.NAVER_SMART_STORE,"<title>에러 페이지</title>","42")).isEmpty();
    }
    @Test void oldSnapshotsReadButNewSnapshotsOnlyStoreStockAndAvailability() {
        var old=json.readValue("""
            {"id":"S","label":"블랙 / FREE","stock":3,"state":"AVAILABLE","itemPrice":102000,"additionalPrice":1000,"deliveryFee":3000,"total":106000}
            """,SourceOption.class);
        assertThat(old.stock()).isEqualTo(3);assertThat(old.label()).isEqualTo("블랙 / FREE");
        var stored=json.readTree(json.writeValueAsString(old));
        assertThat(stored.size()).isEqualTo(4);assertThat(stored.has("itemPrice")).isFalse();assertThat(stored.has("total")).isFalse();
    }
    @Test void disabledOptionsDoNotInventZeroStock()throws Exception {
        var h=fixture("hazzys-options.html").replace("data-size=\"XXX\"","disabled data-size=\"XXX\"");
        var o=parser.parse(Mall.HAZZYS,h,"HIBA6F957BK").getFirst();assertThat(o.stock()).isEqualTo(371);assertThat(o.state()).isEqualTo("UNAVAILABLE");
        var n=parser.parse(Mall.NAVER_SMART_STORE,"""
            {"id":"42","productStatusType":"SALE","salePrice":10000,"optionInfo":{"optionCombinations":[
             {"id":"1","optionName1":"FREE","usable":false,"price":0}]}}
            ""","42").getFirst();assertThat(n.stock()).isNull();assertThat(n.state()).isEqualTo("UNAVAILABLE");
    }
    @Test void verifiedLfResponseExposesSizeAndStock()throws Exception {
        var lf=parser.parse(Mall.LFMALL,fixture("lf-options.json"),"DCWA6F525BK");assertThat(lf).hasSize(1);assertThat(lf.getFirst().label()).isEqualTo("FREE");assertThat(lf.getFirst().stock()).isEqualTo(89);
    }
    @Test void priceFieldsNoLongerAffectLfStock()throws Exception {
        var normal=parser.parse(Mall.LFMALL,fixture("lf-single-full.json"),"DCWA6F525BK");
        var payload=json.readTree(fixture("lf-single-full.json"));
        ((tools.jackson.databind.node.ObjectNode)payload.path("body")).remove("productPriceDTO");
        assertThat(parser.parse(Mall.LFMALL,json.writeValueAsString(payload),"DCWA6F525BK")).isEqualTo(normal);
    }
    @Test void branchMetadataComesOnlyFromTheRequestedProduct()throws Exception {
        var lf=parser.details(Mall.LFMALL,fixture("lf-single-full.json"),"DCWA6F525BK");assertThat(lf.modelCode()).isEqualTo("DCWA6F525BK");
        var lotte=json.readTree(fixture("lotte-single.json"));
        ((tools.jackson.databind.node.ObjectNode)lotte.path("data").path("basicInfo")).put("lrtrNm","현대백화점 목동점");
        assertThat(parser.details(Mall.LOTTE_ON,json.writeValueAsString(lotte),"LE1204272674").storeName()).isEqualTo("현대백화점 목동점");
        assertThat(parser.details(Mall.LOTTE_ON,json.writeValueAsString(lotte),"other").storeName()).isEmpty();
        var html=fixture("imall-options.html")+"<aside>추천상품 [천호점]</aside>";
        assertThat(parser.details(Mall.LOTTE_IMALL,html,"3144343598").title()).isEmpty();
        assertThat(parser.details(Mall.LOTTE_IMALL,"<meta property='og:title' content='[목동점] 가방'>"+html,"3144343598").title()).isEqualTo("[목동점] 가방");
    }


    @Test void conflictingHazzysButtonsNeverDependOnTheirOrder() {
        String identity="<input id='CARTITEMCD' value='P'>";
        String available="<button name='radioChkSizeP' data-size='M' value='3'>M</button>";
        String soldOut="<button name='radioChkSizeP' data-size='M' value='0'>M</button>";
        for(String buttons:List.of(available+soldOut,soldOut+available,available+available.replace("data-size", "disabled data-size"))) {
            var details=parser.details(Mall.HAZZYS,identity+buttons,"P");
            assertThat(details.options()).isEmpty();
            assertThat(details.optionsComplete()).isFalse();
        }
        var identical=parser.details(Mall.HAZZYS,identity+available+available,"P");
        assertThat(identical.options()).hasSize(1);
        assertThat(identical.options().getFirst().stock()).isEqualTo(3);
        assertThat(identical.optionsComplete()).isTrue();
    }
    @Test void discardedLfOptionsMakeTheRemainingResultPartial() {
        String rows="""
            {"sizeCode":"M","sizeName":"M","currentStockQuantity":1},
            {"sizeCode":"M","sizeName":"M","currentStockQuantity":2},
            {"sizeCode":"L","sizeName":"L","currentStockQuantity":0}
            """;
        var details=parser.details(Mall.LFMALL,lfRows(rows),"P");
        assertThat(details.options()).extracting(SourceOption::id).containsExactly("L");
        assertThat(details.options().getFirst().state()).isEqualTo("SOLD_OUT");
        assertThat(details.optionsComplete()).isFalse();
        var missingLabel=parser.details(Mall.LFMALL,lfRows("{\"sizeCode\":\"M\"},{\"sizeCode\":\"L\",\"sizeName\":\"L\"}"),"P");
        assertThat(missingLabel.options()).extracting(SourceOption::id).containsExactly("L");
        assertThat(missingLabel.optionsComplete()).isFalse();
    }
    @Test void validOptionSetsRemainCompleteEvenWhenSoldOut()throws Exception {
        var cases=List.of(
            List.of(Mall.LOTTE_ON,"lotte-single.json","LE1204272674"),
            List.of(Mall.HAZZYS,"hazzys-options.html","HIBA6F957BK"),
            List.of(Mall.HI_THEHYUNDAI,"hi-options.html","2248595115"),
            List.of(Mall.HMALL,"hmall-single.json","2218395189"),
            List.of(Mall.LOTTE_IMALL,"imall-options.html","3144343598"),
            List.of(Mall.LFMALL,"lf-options.json","DCWA6F525BK"));
        for(var c:cases) {
            var details=parser.details((Mall)c.get(0),fixture((String)c.get(1)),(String)c.get(2));
            assertThat(details.optionsComplete()).as("%s",c.get(0)).isTrue();
            assertThat(details.options()).isNotEmpty();
            assertThat(parser.details((Mall)c.get(0),fixture((String)c.get(1)),"wrong-product").optionsComplete()).isFalse();
        }
    }
    @Test void failedOrMissingOptionContainersAreNeverComplete() {
        for(var mall:Mall.values()) {
            assertThat(parser.details(mall,"<title>error</title>","P").optionsComplete()).as("%s",mall).isFalse();
            assertThat(parser.details(mall,"{}","P").optionsComplete()).as("%s",mall).isFalse();
        }
        for(String rows:List.of("null","{}","[]")) {
            assertThat(parser.details(Mall.LFMALL,"{\"body\":{\"productOptionDTO\":{\"productCode\":\"P\",\"productOptionSizeDTOList\":"+rows+"}}}","P").optionsComplete()).isFalse();
            assertThat(parser.details(Mall.HI_THEHYUNDAI,"{\"slitmCd\":\"P\",\"sellUitmList\":"+rows+"}","P").optionsComplete()).isFalse();
        }
    }
    @Test void incompleteImallListsAndMissingHazzysIdsArePartial()throws Exception {
        String imall=fixture("imall-options.html").replace("item_count:1","item_count:2");
        assertThat(parser.details(Mall.LOTTE_IMALL,imall,"3144343598").optionsComplete()).isFalse();
        String hazzys="<input id='CARTITEMCD' value='P'><button name='radioChkSizeP' value='3'>M</button>";
        assertThat(parser.details(Mall.HAZZYS,hazzys,"P").optionsComplete()).isFalse();
    }
    @Test void unknownStockDoesNotMeanAnIncompleteOptionSet() {
        var details=parser.details(Mall.LFMALL,lfRows("{\"sizeCode\":\"M\",\"sizeName\":\"M\"}"),"P");
        assertThat(details.optionsComplete()).isTrue();
        assertThat(details.options().getFirst().stock()).isNull();
        assertThat(details.options().getFirst().state()).isEqualTo("STOCK_UNKNOWN");
    }
    private String lfRows(String rows) {
        return "{\"body\":{\"productOptionDTO\":{\"productCode\":\"P\",\"productOptionSizeDTOList\":["+rows+"]}}}";
    }

    @Test void partialContainersAndConflictsPropagateAcrossJsonSources() {
        String valid=lfRows("{\"sizeCode\":\"M\",\"sizeName\":\"M\",\"currentStockQuantity\":1}");
        String broken="{\"body\":{\"productOptionDTO\":{\"productCode\":\"P\"}}}";
        for(String payload:List.of(valid,broken+"</script><script type='application/json'>"+valid)) {
            String scripts="<script type='application/json'>"+payload+"</script>";
            assertThat(parser.details(Mall.LFMALL,scripts,"P").optionsComplete()).isEqualTo(payload.equals(valid));
        }
        var hi=parser.details(Mall.HI_THEHYUNDAI,"""
            {"slitmCd":"P","sellUitmList":[
              {"uitmCd":"M","uitmTotNm":"M","sellPossQty":1},
              {"uitmCd":"M","uitmTotNm":"M","sellPossQty":2},
              {"uitmCd":"L","uitmTotNm":"L","sellPossQty":0}]}
            ""","P");
        assertThat(hi.options()).extracting(SourceOption::id).containsExactly("L");
        assertThat(hi.optionsComplete()).isFalse();
        for(String invalid:List.of("{\"slitmCd\":\"P\",\"uitmCd\":\"M\"}","{\"uitmCd\":\"M\",\"uitmTotNm\":\"M\"}")) {
            String payload="{\"successYn\":\"Y\",\"respData\":{\"attrs\":["+invalid+",{\"slitmCd\":\"P\",\"uitmCd\":\"L\",\"uitmTotNm\":\"L\",\"stck\":0}]}}";
            var details=parser.details(Mall.HMALL,payload,"P");
            assertThat(details.options()).extracting(SourceOption::id).containsExactly("L");
            assertThat(details.optionsComplete()).isFalse();
        }
    }

    @Test void selectedLotteSkuCannotRestoreAConflictingMapping() {
        String payload="""
            {"returnCode":"200","data":{"basicInfo":{"pdNo":"P","sitmNo":"P_1","sitmNm":"selected","sitmSlStatCd":"SALE","sitmNoList":["P_1","P_2"]},
              "stckInfo":{"stkQty":3},"optionInfo":{"optionList":[{"options":[{"value":"M","label":"M"},{"value":"L","label":"L"},{"value":"S","label":"S"}]}],
              "optionMappingInfo":{"M":{"sitmNo":"P_1","spdNo":"P","stkQty":1,"sitmNoSlStatCd":"SALE"},
                "L":{"sitmNo":"P_1","spdNo":"P","stkQty":2,"sitmNoSlStatCd":"SALE"},
                "S":{"sitmNo":"P_2","spdNo":"P","stkQty":0,"sitmNoSlStatCd":"SALE"}}}}}
            """;
        for(String order:List.of(payload,payload.replace("{\"value\":\"M\",\"label\":\"M\"},{\"value\":\"L\",\"label\":\"L\"}","{\"value\":\"L\",\"label\":\"L\"},{\"value\":\"M\",\"label\":\"M\"}"))) {
            assertThat(parser.parse(Mall.LOTTE_ON,order,"P")).containsExactly(new SourceOption("P_2","S",0L,"SOLD_OUT"));
            assertThat(parser.details(Mall.LOTTE_ON,order,"P").optionsComplete()).isFalse();
        }
    }
}
