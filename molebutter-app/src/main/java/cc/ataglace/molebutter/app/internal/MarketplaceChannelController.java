package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import cc.ataglace.molebutter.marketplace.api.MarketplaceChannels;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class MarketplaceChannelController {
    private final BusinessAccess access;
    public MarketplaceChannelController(BusinessAccess access) { this.access = access; }
    @GetMapping("/api/marketplaces/channels")
    public ApiResponse<List<MarketplaceChannels.Channel>> channels(@AuthenticationPrincipal UserPrincipal actor) {
        access.productActor(actor.userId(), true);
        return ApiResponse.success(MarketplaceChannels.all());
    }
}
