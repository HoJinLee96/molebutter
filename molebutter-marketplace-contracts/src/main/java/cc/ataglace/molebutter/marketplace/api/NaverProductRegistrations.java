package cc.ataglace.molebutter.marketplace.api;

/** Dedicated SmartStore drafts. Saving a draft does not register an external product. */
public interface NaverProductRegistrations {
    Draft create(Long actor,NaverEditor.Input input);
    Draft get(Long actor,String id);
    Draft save(Long actor,String id,Save input);
    MarketplaceSubmissions.Preview prepare(Long actor,String id,Prepare input);
    record Save(Long revision,NaverEditor.Input input) {}
    record Prepare(Long revision) {}
    record Draft(String id,long revision,NaverEditor.Input input,String externalProductId,boolean blocked) {}
}
