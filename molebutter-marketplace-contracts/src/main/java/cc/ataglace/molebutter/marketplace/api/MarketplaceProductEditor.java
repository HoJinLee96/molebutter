package cc.ataglace.molebutter.marketplace.api;

import java.util.List;
import cc.ataglace.molebutter.common.api.PageResponse;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.Document;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditing.*;

/** Unified editor navigation and explicit internal links. Never performs provider writes. */
public interface MarketplaceProductEditor {
    record Entry(String mode,String kind,String originMarket,String commonId,List<String> activeTabs,
                 String initialTab,Document reference,Session session,String token,List<NaverEditor.OptionIdentity> optionIdentities) {
        public Entry(String mode,String kind,String originMarket,String commonId,List<String> activeTabs,String initialTab,Document reference,Session session,String token){this(mode,kind,originMarket,commonId,activeTabs,initialTab,reference,session,token,List.of());}
    }
    record Save(String token,String market,Document reference,List<TargetChanges> targets) {}
    record OptionLink(String sourceOptionId,String commonOptionId,boolean add) {}
    record Link(String token,String commonId,Long revision,String productCode,List<OptionLink> options,List<Change> changes) {}
    record Validate(String token,String sessionId,Document reference,List<TargetChanges> targets,List<String> markets) {}
    record Recreate(String sessionId,Long revision,String market,Long listingVersion,String requestId,Document reference,List<TargetChanges> targets) {}
    record Validation(boolean valid,List<MarketplaceDrafts.Issue> errors,List<MarketplaceDrafts.Issue> unverified,List<MarketplaceDrafts.Issue> warnings,List<cc.ataglace.molebutter.media.api.ImageInspection.Check> images) {}
    Validation validate(Long actor,Validate request);
    Entry open(Long actor,String mode,String market,String productId,String draftId);
    Entry refresh(Long actor,String token);
    DraftSaveResult save(Long actor,Save request);
    Entry connect(Long actor,Link request);
    Entry recreate(Long actor,Recreate request);
    ResolveResult resolve(Long actor,Resolve request);
    PageResponse<MarketplaceDrafts.Summary> candidates(Long actor,String query,int page,int size);
}
