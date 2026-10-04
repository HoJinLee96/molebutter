package cc.ataglace.molebutter.product;
import cc.ataglace.molebutter.service.common.BusinessTime;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.infra.product.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;
import cc.ataglace.molebutter.service.product.*;

class SupplierMetadataTest {
    ObjectMapper json=new ObjectMapper();MallOptionParser parser=new MallOptionParser(json);
    String fixture(String name)throws Exception {try(var in=getClass().getResourceAsStream("/product/"+name)){return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}}
    Offer offer(ProcurementMall mall,String id,String title){return new Offer("NV",title,mall.getDisplayName(),id,"https://shopping.naver.com/window-products/department/"+id,100L,0L,mall,null);}
    @Test void liveHiStoreIsBoundToExactProductWithoutRequiringInventory()throws Exception {
        String html=fixture("hi-store.html");var d=parser.details(ProcurementMall.HI_THEHYUNDAI,html,"2248595115");assertThat(d.storeEvidence().name()).isEqualTo("신촌점");assertThat(d.storeEvidence().externalId()).isEqualTo("270");
        assertThat(parser.details(ProcurementMall.HI_THEHYUNDAI,html,"OTHER").storeEvidence()).isNull();
        String noStock="{\"slitmCd\":\"P\",\"storeNm\":\"신촌점\",\"storeCd\":\"270\"}";
        assertThat(parser.details(ProcurementMall.HI_THEHYUNDAI,noStock,"P").storeEvidence().name()).isEqualTo("신촌점");
        String recommendation="{\"slitmCd\":\"P\",\"recommendations\":[{\"slitmCd\":\"OTHER\",\"storeNm\":\"목동점\"}]}";
        assertThat(parser.details(ProcurementMall.HI_THEHYUNDAI,recommendation,"P").storeEvidence()).isNull();
    }
    @Test void lotteTriesEveryCandidateAndDoesNotChooseConflictingBranches()throws Exception {
        String raw=fixture("lotte-store.json");String id="LE1204272674_1222907552";
        var d=parser.details(ProcurementMall.LOTTE_ON,raw,id);assertThat(d.storeEvidence().name()).isEqualTo("잠실점");assertThat(d.storeEvidence().retailer()).isEqualTo("롯데백화점");assertThat(d.storeEvidence().references()).containsEntry("trNo","LE10016");
        var tree=json.readTree(raw);((tools.jackson.databind.node.ObjectNode)tree.path("data").path("basicInfo")).put("lrtrNm","롯데쇼핑");
        assertThat(parser.details(ProcurementMall.LOTTE_ON,json.writeValueAsString(tree),id).storeEvidence().name()).isEqualTo("잠실점");
        ((tools.jackson.databind.node.ObjectNode)tree.path("data").path("slrInfo").path("trBase")).put("lrtrNm","본점");
        var conflict=parser.details(ProcurementMall.LOTTE_ON,json.writeValueAsString(tree),id);
        assertThat(SupplierBranch.resolve(offer(ProcurementMall.LOTTE_ON,id,"상품"),conflict).state()).isEqualTo("CONFLICT");
    }
    @Test void naverDepartmentMetadataIsIndependentOfInventoryAndValidatesBothIds()throws Exception {
        String raw=fixture("naver-store.json");var d=parser.details(ProcurementMall.NAVER_SMART_STORE,raw,"13381746601");
        assertThat(d.options()).isEmpty();assertThat(d.storeEvidence().retailer()).isEqualTo("현대백화점");assertThat(d.storeEvidence().name()).isEqualTo("천호점");assertThat(d.storeEvidence().externalId()).isEqualTo("10001/10001006");
        assertThat(parser.details(ProcurementMall.NAVER_SMART_STORE,raw,"OTHER").storeEvidence()).isNull();
        assertThat(parser.details(ProcurementMall.NAVER_SMART_STORE,raw.replace("\"_id\": \"13381746601\"","\"_id\": \"OTHER\""),"13381746601").storeEvidence()).isNull();
        assertThat(parser.details(ProcurementMall.NAVER_SMART_STORE,"", "13381746601").storeEvidence()).isNull();
    }
    @Test void externalIdentitySurvivesRenameAndRetailersWithSameBranchStaySeparate(){
        var e=new StoreEvidence("BRANCH","현대백화점","천호점","NAVER_DEPARTMENT","10001/10001006");
        var branch=new BranchInfo("천호점","CONFIRMED","META","천호점",e);var o=offer(ProcurementMall.NAVER_SMART_STORE,"1","상품");var identity=SupplierStorePolicy.identity(o,branch);
        var renamed=new Store("7",o.mall(),"BRANCH","내 매장","old",List.of("다른 표시명"),0,"현대백화점",List.of(new ExternalIdentity(e.namespace(),e.externalId())));
        assertThat(SupplierStorePolicy.resolve(List.of(renamed),o.mall(),identity)).isEqualTo(renamed);
        assertThat(SupplierStorePolicy.identity(offer(ProcurementMall.LOTTE_ON,"2","본점"),new BranchInfo("본점","CONFIRMED","TITLE","본점"))).isNull();
        var other=new Store("8",o.mall(),"BRANCH","천호점","other",List.of("천호점"),0,"롯데백화점",List.of());
        assertThat(SupplierStorePolicy.resolve(List.of(other),o.mall(),identity)).isNull();
        var contradiction=new SupplierStorePolicy.Identity(identity.kind(),identity.key(),identity.name(),"롯데백화점",identity.identities());
        assertThat(SupplierStorePolicy.resolve(List.of(renamed),o.mall(),contradiction)).isNull();assertThat(SupplierStorePolicy.conflicts(List.of(renamed),o.mall(),contradiction)).isTrue();
    }
    @Test void lookupCachesMetadataPerRunAndKeepsMissingStockUnknown(){
        var calls=new ArrayList<String>();SupplierProductGateway gateway=new SupplierProductGateway(){public String validateUrl(ProcurementMall m,String u){return u;}public List<SourceOption> options(ProcurementMall m,String id,String u){throw new AssertionError();}public SourceDetails inspect(ProcurementMall m,String id,String u){calls.add(id);return new SourceDetails("ABCD123","","",List.of(),"천호점",new StoreEvidence("BRANCH","현대백화점","천호점","NAVER_DEPARTMENT","1/2"),true,new NaverChannel(NaverChannelType.WINDOW,"DEPARTMENT"));}};
        var service=new SupplierLookupService(gateway,new BusinessTime());var preferences=new Preferences(1,List.of(),List.of(new Rule("1",ProcurementMall.NAVER_SMART_STORE,null,0)));var work=new SupplierRefreshService.Work(42,2,0,"ABCD123","ABCD6F123BK","LF_ACCESSORY","HAZZYS",preferences,Map.of());var search=new SearchResult(List.of(offer(ProcurementMall.NAVER_SMART_STORE,"1","ABCD123")),true,null);
        var result=service.lookup(work,search,()->true);service.lookup(work,search,()->true);
        assertThat(calls).containsExactly("1");assertThat(result.suppliers().getFirst().state()).isEqualTo("OPTIONS_UNKNOWN");assertThat(result.suppliers().getFirst().options()).isEmpty();assertThat(result.suppliers().getFirst().branch().name()).isEqualTo("천호점");
    }
}
