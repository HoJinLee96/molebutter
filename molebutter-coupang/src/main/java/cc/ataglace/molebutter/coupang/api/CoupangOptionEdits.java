package cc.ataglace.molebutter.coupang.api;
public interface CoupangOptionEdits {
    record Basis(String productId,String fingerprint,Long pricingRevision) {}
    record Context(CoupangWorkingCopies.Entry entry,String optionId,String kind,Long currentValue,Long pendingValue,
        CoupangRegisteredProducts.Detail registry,CoupangQuickEditSources.Source source,CoupangPricing.Settings settings,CoupangPricing.Calculation calculation,
        CoupangCatalog.CurrentInventory currentInventory) {}
    record Prepare(String kind,Long value,String sessionId,Long revision,long observedVersion,String productId,
        String fingerprint,Long pricingRevision,Long purchaseCost) {
        @com.fasterxml.jackson.annotation.JsonCreator
        public static Prepare read(@com.fasterxml.jackson.annotation.JsonProperty("kind") String kind,
                @com.fasterxml.jackson.annotation.JsonProperty("value") Object value,
                @com.fasterxml.jackson.annotation.JsonProperty("sessionId") String sessionId,
                @com.fasterxml.jackson.annotation.JsonProperty("revision") Long revision,
                @com.fasterxml.jackson.annotation.JsonProperty("observedVersion") long observedVersion,
                @com.fasterxml.jackson.annotation.JsonProperty("productId") String productId,
                @com.fasterxml.jackson.annotation.JsonProperty("fingerprint") String fingerprint,
                @com.fasterxml.jackson.annotation.JsonProperty("pricingRevision") Long pricingRevision,
                @com.fasterxml.jackson.annotation.JsonProperty("purchaseCost") Long purchaseCost) {
            Long parsed=null;
            if(value!=null){
                String text=value instanceof String s&&"PRICE".equals(kind)?CoupangPrices.normalize(s):value.toString();
                if(!text.matches("[0-9]+"))throw new cc.ataglace.molebutter.common.api.InputValidationFailure("가격·수량은 0 이상의 정수로 입력해 주세요.");
                try{parsed=Long.valueOf(text);}catch(NumberFormatException invalid){throw new cc.ataglace.molebutter.common.api.InputValidationFailure("가격·수량의 정수 범위를 확인해 주세요.");}
            }
            return new Prepare(kind,parsed,sessionId,revision,observedVersion,productId,fingerprint,pricingRevision,purchaseCost);
        }
    }
    record Result(CoupangWorkingCopies.Entry entry,CoupangSubmissions.Preview preview,CoupangRegisteredProducts.Detail registry,CoupangPricing.Calculation calculation) {}
    record OptionRequest(String vendorItemId,String internalProductId) {}
    record BulkOpen(String kind,java.util.List<OptionRequest> options,String sessionId) {public BulkOpen(String kind,java.util.List<OptionRequest> options){this(kind,options,null);}}
    java.util.List<Context> stored(Long actor,String product,BulkOpen input);
    Result prepareStored(Long actor,String product,String vendor,Prepare input);
    Context open(Long actor,String product,String vendor,String kind,String internalProduct);
    Result prepare(Long actor,String product,String vendor,Prepare input);
}
