package cc.ataglace.molebutter.marketplace.api;

import java.util.List;
import java.util.Map;

/** Dedicated new-product inputs; no external identifiers or remote source payloads. */
public interface CoupangProductRegistrations {
    Draft create(Long actor,Input input);
    Draft get(Long actor,String id);
    Draft save(Long actor,String id,Save input);
    MarketplaceSubmissions.Preview prepare(Long actor,String id,Prepare input);
    record Input(Map<String,String> basic,List<Option> options,Map<String,String> delivery,
                 Map<String,String> settings,List<CoupangEditor.Document> documents) {}
    record Option(String id,List<CoupangCatalog.Attribute> attributes,List<Image> images,
                  List<Content> contents,List<CoupangCatalog.Notice> notices,
                  Map<String,String> registration,List<CoupangCatalog.Certification> certifications,String noticeCategory) {}
    record Image(String id,String assetId,String url,String type,Integer order) {}
    record Content(String id,String type,String detailType,String content) {}
    record Save(Long revision,Input input) {}
    record Prepare(Long revision,boolean requested) {}
    record Draft(String id,long revision,Input input,String externalProductId,boolean blocked) {}
}
