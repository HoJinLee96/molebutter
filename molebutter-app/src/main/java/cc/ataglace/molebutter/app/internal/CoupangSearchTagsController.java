package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.coupang.api.CoupangSearchTags;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.identity.api.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/coupang/working-copies/search-tags")
public final class CoupangSearchTagsController {
    private final CoupangSearchTags tags; private final BusinessAccess access;
    public CoupangSearchTagsController(CoupangSearchTags tags,BusinessAccess access){this.tags=tags;this.access=access;}
    @PostMapping public ApiResponse<CoupangSearchTags.Result> generate(@AuthenticationPrincipal UserPrincipal actor,@RequestBody CoupangSearchTags.Input input){
        access.productActor(actor.userId(),true);return ApiResponse.success(tags.generate(input));
    }
}
