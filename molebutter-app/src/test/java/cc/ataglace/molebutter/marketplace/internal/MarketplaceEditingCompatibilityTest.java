package cc.ataglace.molebutter.marketplace.internal;

import cc.ataglace.molebutter.marketplace.api.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts;
import static org.assertj.core.api.Assertions.*;

class MarketplaceEditingCompatibilityTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void historicalPreparedAndResultRemainReadableWithoutNewIntentOrRequestFields(){
        var old=json.readValue("""
            {"accountKey":"old-account","mapping":null,"steps":[],"changes":[],
             "preparedAt":"2026-10-05T14:00:00Z","expectedSkus":["SKU"]}
            """,MarketplaceWriteGateway.Prepared.class);
        assertThat(old.editIntent()).isNull();assertThat(old.schemaVersion()).isEqualTo(1);
        assertThat(old.expectedSkus()).containsExactly("SKU");
        var result=json.readValue("""
            {"state":"UNKNOWN","mapping":null,"code":"INTERRUPTED","message":null,
             "attemptedAt":"2026-10-05T14:00:00Z"}
            """,MarketplaceWriteGateway.Result.class);
        assertThat(result.requestJson()).isNull();
        assertThat(result.state()).isEqualTo(MarketplaceWriteGateway.State.UNKNOWN);
    }
    @Test void oldReferenceJsonKeepsZeroDefaultsAndPreparedNaverInputs(){
        var old=json.readValue("""
            {"id":"1","revision":0,"common":{"name":"공통"},
             "options":[{"id":"00000000-0000-4000-8000-000000000001","name":"블랙","price":"0","quantity":"0"}],
             "selectedMarkets":["COUPANG","NAVER"],
             "markets":{"COUPANG":{"categoryCode":"123"},"NAVER":{"categoryCode":"456",
             "naver":{"channelId":"channel","status":"SALE","saleType":"NEW","originCode":"LOCAL",
             "deliveryTemplateId":"shipping","afterServiceTelephone":"","attributes":[],"notices":[]}}}}
            """,MarketplaceDrafts.Document.class);
        var reference=MarketplaceDocuments.normalize(old);var live=cc.ataglace.molebutter.marketplacecoupang.internal.ProviderCoupangDocumentsFixture.create(org.mockito.Mockito.mock(CoupangEditor.class),org.mockito.Mockito.mock(CoupangBrands.class)).newDocument(reference);
        assertThat(reference.options().getFirst().price()).isEqualTo("0");
        assertThat(live.options().getFirst().quantity()).isEqualTo("0");
        assertThat(live.markets().get(MarketplaceDrafts.Market.COUPANG).coupang().options()).hasSize(1);
        assertThat(live.markets().get(MarketplaceDrafts.Market.NAVER).naver()).isEqualTo(reference.markets().get(MarketplaceDrafts.Market.NAVER).naver());
        assertThat(reference.markets().get(MarketplaceDrafts.Market.COUPANG).coupang()).isNull();
    }
}
