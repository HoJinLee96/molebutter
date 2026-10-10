package cc.ataglace.molebutter.app.test;

import static org.assertj.core.api.Assertions.assertThat;
import cc.ataglace.molebutter.marketplace.api.MarketplaceChannels;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MarketplaceChannelsTest {
    @Test void browserFixtureMatchesServerCapabilitiesAndDistinctEsmChannels() throws Exception {
        var json = new ObjectMapper();
        try (var input = getClass().getResourceAsStream("/marketplace-channels.json")) {
            assertThat(input).isNotNull();
            assertThat((tools.jackson.databind.JsonNode)json.valueToTree(MarketplaceChannels.all())).isEqualTo(json.readTree(input));
        }
        assertThat(MarketplaceChannels.ids()).hasSize(6);
        assertThat(MarketplaceChannels.find("GMARKET").provider()).isEqualTo(MarketplaceChannels.Provider.ESM);
        assertThat(MarketplaceChannels.find("AUCTION").provider()).isEqualTo(MarketplaceChannels.Provider.ESM);
        assertThat(MarketplaceChannels.find("NAVER").productCreate()).isTrue();
        assertThat(MarketplaceChannels.find("NAVER").orderRead()).isFalse();
        assertThat(MarketplaceChannels.find("ELEVENST").productCreate()).isFalse();
    }
}
