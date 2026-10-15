package cc.ataglace.molebutter.marketplace.internal;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;

class RecreationDocumentsTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void naverRecreationRetainsLocalOptionAndProviderInputWithoutOldChannelIdentity(){
        String option=UUID.randomUUID().toString(),image=UUID.randomUUID().toString();
        var input=new NaverEditor.Input(Map.of("originProduct.name","마켓 상품명","originProduct.statusType","SUSPENSION","originProduct.salePrice",128000),"COMBINATION",List.of("색상"),List.of(new NaverEditor.Option(option,List.of("레드"),0L,50L,"WCWA705R2",true)),List.of(new NaverEditor.Image(image,null,"https://assets.test/01.jpg",true,0)),"<p>마켓 설명</p>");
        var naver=new Naver("old-channel","SUSPENSION","NEW","00","","1544-0000",List.of(),List.of(),null,null,null,input,"1217");
        var common=new Common("WCWA705R2","공통 이름","","","","","","","","TAX","EVERYONE");
        var d=new Document("1",3L,common,List.of(new Option(option,"레드","WCWA705R2","87000","50",List.of())),StockMode.OPTION,"",List.of(),new Media(List.of(),List.of()),null,List.of(Market.NAVER),Map.of(Market.NAVER,new MarketConfig("50003982",null,null,naver,null)));
        var clean=RecreationDocuments.clean(d,"NAVER",json);var result=clean.markets().get(Market.NAVER).naver();
        assertThat(result.channelId()).isEmpty();assertThat(result.editorInput().options()).isEqualTo(input.options());assertThat(result.editorInput().images()).isEqualTo(input.images());assertThat(result.editorInput().description()).isEqualTo(input.description());assertThat(result.editorInput().fields().get("originProduct.statusType")).isEqualTo("SALE");assertThat(result.editorState().pricing()).isEqualTo("CUSTOM");assertThat(clean.options().getFirst().id()).isEqualTo(option);assertThat(RecreationDocuments.reference(d,clean,"NAVER").common()).isEqualTo(common);
    }
    @Test void missingHistoricalInputIsExplainedWithoutCreatingAnIncompleteProviderDocument(){
        var d=json.readValue("{\"common\":{\"productCode\":\"A\",\"name\":\"상품\"},\"options\":[],\"markets\":{\"NAVER\":{}}}",Document.class);
        assertThatThrownBy(()->RecreationDocuments.clean(d,"NAVER",json)).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class).hasMessageContaining("마지막 마켓 입력");
    }
}
