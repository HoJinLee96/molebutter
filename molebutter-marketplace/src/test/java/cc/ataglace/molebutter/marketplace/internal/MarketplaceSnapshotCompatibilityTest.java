package cc.ataglace.molebutter.marketplace.internal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway;
import static org.assertj.core.api.Assertions.*;

class MarketplaceSnapshotCompatibilityTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void historicalKeysAndMissingVersionKeepLegacyMeaningAndNewFieldsAreAdditive(){
        var historical=json.readValue("""
            {"accountKey":"account","mapping":{"accountKey":"account","sellerProductId":"123","options":[{"optionId":"internal-id","sellerProductItemId":"0","vendorItemId":null}]},"steps":[],"changes":[],"preparedAt":"2026-10-08T00:00:00Z","expectedSkus":["SKU"]}
            """,MarketplaceWriteGateway.Prepared.class);
        assertThat(historical.schemaVersion()).isEqualTo(1);assertThat(historical.market()).isEqualTo("COUPANG");assertThat(historical.mapping().channelProductId()).isNull();assertThat(historical.mapping().options().getFirst().sellerProductItemId()).isEqualTo("0");assertThat(historical.editIntent()).isNull();
        var wire=json.valueToTree(historical);assertThat(wire.path("mapping").path("sellerProductId").asString()).isEqualTo("123");assertThat(wire.path("schemaVersion").asInt()).isEqualTo(1);assertThat(wire.has("externalProductId")).isFalse();
    }
    @Test void futureVersionIsRetainedInsteadOfSilentlyCoercedToCurrent(){
        var future=json.readValue("""
            {"accountKey":"account","mapping":null,"steps":[],"changes":[],"preparedAt":"2026-10-08T00:00:00Z","expectedSkus":[],"market":"NAVER","schemaVersion":9}
            """,MarketplaceWriteGateway.Prepared.class);
        assertThat(future.schemaVersion()).isEqualTo(9);assertThat(future.market()).isEqualTo("NAVER");
    }
}
