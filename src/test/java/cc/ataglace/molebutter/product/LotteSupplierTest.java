package cc.ataglace.molebutter.product;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import cc.ataglace.molebutter.infra.product.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;
import cc.ataglace.molebutter.service.product.*;

class LotteSupplierTest {
    final ObjectMapper json=new ObjectMapper();final MallOptionParser parser=new MallOptionParser(json);
    final String sku="LO2630710241_2630710242";
    ObjectNode fixture()throws Exception {try(var in=getClass().getResourceAsStream("/product/lotte-lf.json")){return (ObjectNode)json.readTree(in);}}
    SourceDetails details(JsonNode root){return parser.details(Mall.LOTTE_ON,json.writeValueAsString(root),sku);}
    ObjectNode node(JsonNode r,String pointer){return (ObjectNode)r.at(pointer);}
    Offer offer(String nv,String label,long price){return new Offer(nv,"WCBA5E052BK",label,sku,"https://www.lotteon.com/p/product/LO2630710241?sitmNo="+sku,price,0L,Mall.LOTTE_ON,null);}
    Store lf(List<ExternalIdentity> ids){return new Store("LF",Mall.LOTTE_ON,"COMPANY","주식회사 LF","company:lf",List.of("주식회사 LF"),0,null,ids);}
    @Test void researchSampleIdentifiesCompanyModelAndMappedFreeStock()throws Exception {
        var d=details(fixture());assertThat(d.modelCode()).isEqualTo("WCBA5E052BK");assertThat(d.optionsComplete()).isTrue();
        assertThat(d.options()).containsExactly(new SourceOption(sku,"FREE",2L,"AVAILABLE"));
        var b=SupplierBranch.resolve(offer("1","롯데ON",134000),d);
        assertThat(b.state()).isEqualTo("CONFIRMED");assertThat(b.store().kind()).isEqualTo("COMPANY");assertThat(b.store().externalId()).isEqualTo("LO10004813");
        assertThat(SupplierStorePolicy.identity(offer("1","롯데ON",134000),b).key()).isEqualTo("company:lf");
    }
    @Test void legalCompanyNeedsItsOwnIdAndCannotBeInferredFromShopOrBrand()throws Exception {
        var r=fixture();node(r,"/data/basicInfo").remove("trNo");node(r,"/data/slrInfo/trBase").remove("trNo");assertThat(details(r).storeEvidence()).isNull();
        r=fixture();node(r,"/data/basicInfo").put("trNm","다른 회사");node(r,"/data/slrInfo/trBase").put("trNm","다른 회사");
        assertThat(details(r).storeEvidence().kind()).isEqualTo("SELLER");
        assertThat(SupplierStorePolicy.companyContradiction(lf(List.of()),SupplierBranch.resolve(offer("1","롯데ON",1),details(r)))).isTrue();
        node(r,"/data/basicInfo").put("trNm","주식회사 LF");assertThat(details(r).storeEvidence().kind()).isEqualTo("CONFLICT");
        r=fixture();node(r,"/data/slrInfo/trBase").put("trNo","OTHER");assertThat(details(r).storeEvidence().kind()).isEqualTo("CONFLICT");
        r=fixture();r.put("returnCode","500");assertThat(details(r).options()).isEmpty();assertThat(details(r).storeEvidence()).isNull();
        r=fixture();node(r,"/data/basicInfo").put("sitmNo","OTHER");assertThat(details(r).options()).isEmpty();assertThat(details(r).storeEvidence()).isNull();
    }
    @Test void specificPreferencesExcludeOtherCompaniesButKeepIdentityConflictsForReview()throws Exception {
        var d=details(fixture());var o=offer("1","롯데ON",1);var branch=SupplierBranch.resolve(o,d);var store=lf(List.of());
        var allowed=new Preferences(1,List.of(store),List.of(new Rule("R",Mall.LOTTE_ON,"LF",0)));
        assertThat(SupplierStorePolicy.include(allowed,Map.of(),o,branch)).isTrue();
        var branchOnly=new Preferences(1,List.of(store),List.of(new Rule("R",Mall.LOTTE_ON,"BRANCH",0)));
        assertThat(SupplierStorePolicy.include(branchOnly,Map.of(),o,branch)).isFalse();
        var other=fixture();node(other,"/data/basicInfo").put("trNm","다른 회사");node(other,"/data/slrInfo/trBase").put("trNm","다른 회사");
        var unknown=SupplierBranch.resolve(o,details(other));assertThat(SupplierStorePolicy.include(allowed,Map.of(),o,unknown)).isFalse();
        assertThat(SupplierStorePolicy.include(new Preferences(1,List.of(),List.of(new Rule("R",Mall.LOTTE_ON,null,0))),Map.of(),o,unknown)).isTrue();
        assertThat(SupplierStorePolicy.include(new Preferences(1,List.of(),List.of(new Rule("R",Mall.LOTTE_ON,null,0)),Map.of(Mall.LOTTE_ON,false)),Map.of(),o,new BranchInfo(null,"UNKNOWN",null,null))).isTrue();
        var conflicting=lf(List.of(new ExternalIdentity("LOTTE_COMPANY","OLD")));
        var identity=SupplierStorePolicy.identity(o,branch);assertThat(SupplierStorePolicy.conflicts(List.of(conflicting),Mall.LOTTE_ON,identity)).isTrue();assertThat(SupplierStorePolicy.resolve(List.of(conflicting),Mall.LOTTE_ON,identity)).isNull();
    }
    @Test void hiddenMissingNegativeAndConflictingStockNeverBecomeZero()throws Exception {
        for(String field:List.of("hide","missing","negative","string")){
            var r=fixture();var selected=node(r,"/data/stckInfo");var mapped=node(r,"/data/optionInfo/optionMappingInfo/720679812FREE");
            for(var n:List.of(selected,mapped))switch(field){case "hide"->n.put("hideStkQty",true);case "missing"->n.remove("stkQty");case "negative"->n.put("stkQty",-1);case "string"->n.put("stkQty","2");}
            assertThat(details(r).options().getFirst().stock()).as(field).isNull();assertThat(details(r).options().getFirst().state()).isEqualTo("STOCK_UNKNOWN");
        }
        var r=fixture();node(r,"/data/stckInfo").put("stkQty",7);assertThat(details(r).options().getFirst().stock()).isNull();assertThat(details(r).optionsComplete()).isFalse();
        r=fixture();node(r,"/data/basicInfo").put("sitmSlStatCd","STOP");assertThat(details(r).options().getFirst().state()).isEqualTo("UNAVAILABLE");
        r=fixture();node(r,"/data/stckInfo").put("stkQty",0);node(r,"/data/optionInfo/optionMappingInfo/720679812FREE").put("stkQty",0);assertThat(details(r).options().getFirst().state()).isEqualTo("SOLD_OUT");
    }
    @Test void incompleteMappingKeepsOnlySelectedSkuAndMarksPartial()throws Exception {
        var r=fixture();((ArrayNode)r.at("/data/optionInfo/optionList")).addObject().put("title","색상");
        assertThat(details(r).optionsComplete()).isFalse();assertThat(details(r).options()).containsExactly(new SourceOption(sku,"FREE",2L,"AVAILABLE"));
        r=fixture();node(r,"/data/optionInfo/optionMappingInfo/720679812FREE").put("spdNo","OTHER");assertThat(details(r).optionsComplete()).isFalse();assertThat(details(r).options()).hasSize(1);
    }
    @Test void singleAxisOptionsKeepIndependentStocksAndMissingCoverageIsPartial()throws Exception {
        var r=fixture();String second="LO2630710241_2630710243";
        ((ArrayNode)r.at("/data/optionInfo/optionList/0/options")).addObject().put("label","M").put("value","M");
        node(r,"/data/optionInfo/optionMappingInfo").putObject("M").put("spdNo","LO2630710241").put("sitmNo",second).put("sitmNoSlStatCd","SALE").put("spdNoSlStatCd","SALE").put("stkQty",9);
        ((ArrayNode)r.at("/data/basicInfo/sitmNoList")).add(second);
        assertThat(details(r).optionsComplete()).isTrue();assertThat(details(r).options()).extracting(SourceOption::stock).containsExactly(2L,9L);
        ((ArrayNode)r.at("/data/basicInfo/sitmNoList")).add("LO2630710241_2630710244");assertThat(details(r).optionsComplete()).isFalse();
        node(r,"/data/optionInfo/optionMappingInfo/M").put("hideStkQty",true);assertThat(details(r).options().getLast().stock()).isNull();
    }
    @Test void departmentLabelsResolveToSameBranchAndOtherBranchesAreExcluded()throws Exception {
        String raw;try(var in=getClass().getResourceAsStream("/product/lotte-store.json")){raw=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        var d=parser.details(Mall.LOTTE_ON,raw,"LE1204272674_1222907552");
        var store=new Store("J",Mall.LOTTE_ON,"BRANCH","잠실점","branch:롯데백화점:잠실점",List.of("잠실점"),0,"롯데백화점",List.of());
        var p=new Preferences(1,List.of(store),List.of(new Rule("R",Mall.LOTTE_ON,"J",0)));
        for(String name:List.of("롯데ON","롯데백화점")){
            var o=new Offer(name,"DCWA2E417BK",name,"LE1204272674_1222907552","https://www.lotteon.com/p/product/LE1204272674",134000L,0L,Mall.LOTTE_ON,null);
            var b=SupplierBranch.resolve(o,d);assertThat(SupplierStorePolicy.resolve(p.stores(),Mall.LOTTE_ON,SupplierStorePolicy.identity(o,b))).isEqualTo(store);assertThat(SupplierStorePolicy.include(p,Map.of(),o,b)).isTrue();
            var other=parser.details(Mall.LOTTE_ON,raw.replace("잠실점","본점"),"LE1204272674_1222907552");assertThat(SupplierStorePolicy.include(p,Map.of(),o,SupplierBranch.resolve(o,other))).isFalse();
        }
    }
    @Test void duplicateNaverExposuresShareDetailsButRetainSeparatePricesAndIds()throws Exception {
        var d=details(fixture());var calls=new ArrayList<String>();
        ProductSourceGateway gateway=new ProductSourceGateway(){public String validateUrl(Mall m,String u){return u;}public List<SourceOption> options(Mall m,String id,String u){return List.of();}public SourceDetails inspect(Mall m,String id,String u){calls.add(id);return d;}};
        var service=new ProductLookupService(gateway,new ProductTime());var p=new Preferences(1,List.of(lf(List.of())),List.of(new Rule("R",Mall.LOTTE_ON,"LF",0)));
        var w=new ProductRefreshService.Work(1,2,0,"WCBA052","WCBA5F052BK","LF_ACCESSORY","DAKS",p,Map.of());
        var result=service.lookup(w,new SearchResult(List.of(offer("NV1","롯데ON",134000),offer("NV2","롯데백화점",140000)),true,null),()->true);
        assertThat(calls).containsExactly(sku);assertThat(result.suppliers()).hasSize(2);assertThat(result.suppliers()).extracting(s->s.offer().price()).containsExactly(134000L,140000L);
        assertThat(result.suppliers()).extracting(s->s.offer().naverProductId()).containsExactly("NV1","NV2");
    }

}
