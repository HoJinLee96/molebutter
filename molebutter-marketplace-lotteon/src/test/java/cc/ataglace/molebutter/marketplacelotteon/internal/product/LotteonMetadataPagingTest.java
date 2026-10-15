package cc.ataglace.molebutter.marketplacelotteon.internal.product;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LotteonMetadataPagingTest {
    @Test void categoryChildrenUseOfficialParentFilterAndPreserveExactOpaqueIds(){
        var json=new ObjectMapper();var calls=new ArrayList<String>();
        var client=new LotteonClient("synthetic-parent",json,(method,host,path,body)->{
            calls.add(path);return new LotteonClient.Response(200,json.writeValueAsBytes(Map.of("itemList",List.of(Map.of("data",Map.of("std_cat_id","BC01030100","std_cat_nm","지갑","upr_std_cat_id","BC01030000","leaf_yn","Y","use_yn","Y"))))),null);
        },0);
        var page=new LotteonCatalog(client,mock(BusinessAccess.class),json).metadataPage(1L,"categories","","BC01030000",0);
        assertThat(calls).singleElement().asString().contains("filter_2=BC01030000");assertThat(page.items().getFirst().id()).isEqualTo("BC01030100");assertThat(page.items().getFirst().parent()).isEqualTo("BC01030000");
    }
    @Test void aFullInactivePageStillAllowsReadingTheNextActivePage(){
        var json=new ObjectMapper();var calls=new ArrayList<String>();
        var client=new LotteonClient("synthetic-metadata",json,(method,host,path,body)->{
            calls.add(path);var rows=new ArrayList<Map<String,Object>>();
            boolean first=path.contains("skip=0&");
            for(int n=0;n<(first?100:1);n++)rows.add(Map.of("data",Map.of("std_cat_id","CAT"+n,"std_cat_nm","합성 분류","use_yn",first?"N":"Y","leaf_yn","Y")));
            return new LotteonClient.Response(200,json.writeValueAsBytes(Map.of("itemList",rows)),null);
        },0);
        var catalog=new LotteonCatalog(client,mock(BusinessAccess.class),json);
        var first=catalog.metadataPage(1L,"categories","",0);
        assertThat(first.items()).isEmpty();assertThat(first.hasNext()).isTrue();
        var second=catalog.metadataPage(1L,"categories","",1);
        assertThat(second.items()).hasSize(1);assertThat(second.hasNext()).isFalse();assertThat(second.page()).isEqualTo(1);
        assertThat(calls).hasSize(2);assertThat(calls.getLast()).contains("skip=100&limit=100");
    }
}
