package cc.ataglace.molebutter.marketplacenaver.internal;

import java.util.*;
import org.junit.jupiter.api.Test;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import cc.ataglace.molebutter.marketplacenaver.api.NaverGateway;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NaverProductDocumentsTest {
    private final NaverCatalog catalog=mock(NaverCatalog.class);
    private final NaverGateway gateway=mock(NaverGateway.class);
    private final NaverProductDocuments documents=new DefaultNaverProductDocuments(catalog,gateway);
    @Test void nativePortNormalizesNumbersAndKeepsDocumentAndOptionIdentities(){
        var id=UUID.randomUUID().toString();var input=new NaverEditor.Input(Map.of("originProduct.salePrice","1000","originProduct.stockQuantity","0"),"COMBINATION",List.of("색상"),List.of(new NaverEditor.Option(id,List.of("검정"),0L,0L,"SKU",true)),List.of(),"");
        var document=documents.document("15",7L,input);var normalized=documents.normalize(document);assertThat(normalized.id()).isEqualTo("15");assertThat(normalized.revision()).isEqualTo(7L);assertThat(((Number)documents.input(normalized).fields().get("originProduct.salePrice")).longValue()).isEqualTo(1000L);assertThat(documents.input(normalized).options().getFirst().id()).isEqualTo(id);assertThat(documents.input(normalized).options().getFirst().stockQuantity()).isZero();verifyNoInteractions(catalog,gateway);
    }
    @Test void newNativeDraftDoesNotInventRemoteIdsOrUseCoupangProductRules(){
        var draft=documents.document(null,null,new NaverEditor.Input(Map.of(),"NONE",List.of(),List.of(),List.of(),""));assertThat(documents.supports(draft)).isTrue();assertThat(documents.legacyMapping(draft,"account")).isNull();assertThat(documents.newDocument(draft).markets().get(Market.NAVER).naver().editorInput().optionMode()).isEqualTo("NONE");assertThat(draft.options()).isEmpty();verifyNoInteractions(catalog,gateway);
    }
    @Test void malformedStructureIsLeftForTheCoreValidationBoundary(){
        assertThat(documents.normalize((Document)null)).isNull();
        var draft=documents.document(null,null,new NaverEditor.Input(Map.of(),"NONE",List.of(),List.of(),List.of(),""));
        var malformed=new Document(draft.id(),draft.revision(),draft.common(),draft.options(),draft.stockMode(),draft.productQuantity(),draft.services(),draft.media(),draft.delivery(),draft.selectedMarkets(),null);
        assertThat(documents.normalize(malformed)).isSameAs(malformed);verifyNoInteractions(catalog,gateway);
    }
}
