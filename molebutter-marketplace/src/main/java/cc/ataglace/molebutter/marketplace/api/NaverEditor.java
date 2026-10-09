package cc.ataglace.molebutter.marketplace.api;

import java.util.List;
import java.util.Map;

/** SmartStore form inputs. Only allowlisted request fields are accepted, never a remote source body. */
public interface NaverEditor {
    record Input(Map<String,Object> fields,String optionMode,List<String> optionNames,
                 List<Option> options,List<Image> images,String description) {}
    record Option(String id,List<String> values,Long price,Long stockQuantity,
                  String sellerManagerCode,Boolean usable) {}
    record Image(String id,String assetId,String url,boolean representative,int order) {}
    record Limits(boolean groupProduct,boolean optionStructureReadonly,boolean categoryReadonly,
                  boolean modelReadonly,String message) {}
    record OptionIdentity(String id,String remoteId) {}
    record EditorDocument(Input input,Limits limits,List<OptionIdentity> optionIdentities,
                          String originProductNo,String channelProductNo) {}
}
