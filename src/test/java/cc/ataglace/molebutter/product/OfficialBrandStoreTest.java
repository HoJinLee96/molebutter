package cc.ataglace.molebutter.product;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.infra.product.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;
import cc.ataglace.molebutter.service.product.*;

class OfficialBrandStoreTest {
    ProcurementMall mall=ProcurementMall.NAVER_SMART_STORE;
    Store official=new Store("1",mall,"BRAND_STORE","관리자 표시명","external:NAVER_CHANNEL:uid",List.of("헤지스"),0,null,List.of(new ExternalIdentity("NAVER_CHANNEL","uid")));
    BranchInfo branch(String uid){return new BranchInfo("헤지스","CONFIRMED","META","헤지스",new StoreEvidence("SELLER",null,"헤지스","NAVER_CHANNEL",uid));}
    Offer offer=new Offer("nv","ABCD6E123BK","헤지스","123","https://brand.naver.com/test/products/123",100L,0L,ProcurementMall.NAVER_SMART_STORE,null);
    @Test void onlySupportedProductUrlsCanBeResolved(){
        for(String url:List.of("https://brand.naver.com/daks/products/123?x=1","https://shopping.naver.com/window-products/brandfashion/123","https://shopping.naver.com/window-products/department/123","https://shopping.naver.com/outlink/itemdetail/123"))assertThat(OfficialBrandStoreService.productId(url)).isEqualTo("123");
        for(String url:List.of("https://smartstore.naver.com/daks/products/123","https://brand.naver.com/daks","https://brand.naver.com.evil.test/daks/products/123","http://localhost/products/123","https://user@brand.naver.com/daks/products/123","https://brand.naver.com:443/daks/products/123","https://brand.naver.com/daks/products/OTHER","https://brand.naver.com/daks/products/123/other","file:///etc/passwd"))assertThatThrownBy(()->OfficialBrandStoreService.productId(url)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void officialIdentitySurvivesRenameButNeverMergesByName(){
        var identity=SupplierStorePolicy.identity(offer,branch("uid"));
        assertThat(SupplierStorePolicy.resolve(List.of(official),mall,identity)).isEqualTo(official);
        assertThat(SupplierStorePolicy.conflicts(List.of(official),mall,identity)).isFalse();
        var prefs=new Preferences(1,List.of(official),List.of(new Rule("2",mall,"1",0)));
        assertThat(SupplierStorePolicy.include(prefs,Map.of(),offer,branch("uid"))).isTrue();
        assertThat(SupplierStorePolicy.include(prefs,Map.of(),offer,branch("other"))).isFalse();
        assertThat(SupplierStorePolicy.resolve(List.of(official),mall,SupplierStorePolicy.identity(offer,branch("other")))).isNull();
        assertThat(SupplierStorePolicy.storeContradiction(official,branch("other"))).isTrue();
        assertThat(SupplierStorePolicy.storeContradiction(official,null)).isTrue();
        assertThat(SupplierStorePolicy.storeContradiction(official,branch("uid"))).isFalse();
        var department=new BranchInfo("천호점","CONFIRMED","META","현대백화점",new StoreEvidence("BRANCH","현대백화점","천호점","NAVER_DEPARTMENT","uid"));
        assertThat(SupplierStorePolicy.storeContradiction(official,department)).isTrue();
    }
    @Test void parserChecksProductIdsAndNeverInfersOfficial()throws Exception {
        String raw;try(var in=getClass().getResourceAsStream("/product/naver-brand-store.json")){raw=new String(in.readAllBytes());}
        var parser=new MallOptionParser(new ObjectMapper());
        var details=parser.details(mall,raw,"13197489089");
        assertThat(details.storeEvidence().kind()).isEqualTo("SELLER");assertThat(details.storeEvidence().namespace()).isEqualTo("NAVER_CHANNEL");assertThat(details.options()).isEmpty();
        assertThat(parser.details(mall,raw,"wrong").storeEvidence()).isNull();
        assertThat(parser.details(mall,raw.replace("\"id\": 13197489089","\"id\": 999"),"13197489089").storeEvidence()).isNull();
        assertThat(parser.details(mall,raw.replace("2sWE1WHVrXUFoJpa77gZb",""),"13197489089").storeEvidence()).isNull();
        String nested="{\"_id\":\"123\",\"contents\":{\"id\":\"123\",\"channel\":{\"channelUid\":\"uid\",\"name\":\"헤지스\"}}}";
        assertThat(parser.details(mall,nested,"123").storeEvidence().externalId()).isEqualTo("uid");
        String conflicting=nested.replace("{\"_id\"", "{\"channel\":{\"channelUid\":\"other\",\"name\":\"헤지스\"},\"_id\"");
        assertThat(parser.details(mall,conflicting,"123").storeEvidence().kind()).isEqualTo("CONFLICT");
    }
}
