package cc.ataglace.molebutter.marketplace.api;

import java.util.List;
import java.util.Map;
import cc.ataglace.molebutter.common.api.PageResponse;

/** Internal sales drafts only. None of these contracts is an external write payload. */
public interface MarketplaceDrafts {
    Document create(Long actor, Document input);
    Document save(Long actor, String id, Long expectedRevision, Document input);
    Document get(Long actor, String id);
    PageResponse<Summary> list(Long actor, String query, int page, int size);
    Validation validate(Long actor, Document input);
    default Validation validate(Long actor, Document input, String requestId) { return validate(actor, input); }
    Document importCoupang(Long actor, String sellerProductId, String requestId);

    enum Market { COUPANG, NAVER, GMARKET, AUCTION, LOTTEON, ELEVENST }
    enum StockMode { PRODUCT, OPTION }
    record Common(String productCode, String name, String productName, String brand,
                  String manufacturer, String origin, String material, String model,
                  String afterService, String taxType, String adultOnly) {}
    record Attribute(String name, String value) {}
    record Option(String id, String name, String sku, String price, String quantity,
                  List<Attribute> attributes) {}
    record ServiceOption(String id, String name, List<String> choices) {}
    record Image(String id, String assetId, String url, boolean representative,
                 int order, String optionId, String type) {
        public Image(String id,String assetId,String url,boolean representative,int order,String optionId){this(id,assetId,url,representative,order,optionId,null);}
        public String imageType(){return representative?"REPRESENTATION":type==null?"DETAIL":type;}
    }
    record Content(String id, String type, String value, String optionId) {}
    record Media(List<Image> images, List<Content> contents) {}
    record Delivery(String method, String carrier, String chargeType, String charge,
                    String freeOver, String returnCharge, String initialReturnCharge,
                    String remoteArea, String bundle, String outboundCode, String returnCode,
                    String returnName, String returnContact, String returnZip,
                    String returnAddress, String returnAddressDetail) {}
    /** A null override inherits the common value; an explicit empty string remains empty. */
    record OptionOverride(String optionId, String name, String sku, String price, String quantity) {}
    record Overrides(String name, String productName, String brand,
                     List<OptionOverride> options, List<String> imageIds, String description) {}
    record CoupangOption(String optionId, String sellerProductItemId, String vendorItemId,
                         List<CoupangCatalog.Field> registration,
                         List<CoupangCatalog.Attribute> attributes,
                         List<CoupangCatalog.Notice> notices,
                         List<CoupangCatalog.Certification> certifications) {}
    record Coupang(String sellerProductId, List<CoupangCatalog.Field> delivery,
                   List<CoupangCatalog.Field> settings, List<CoupangOption> options,
                   List<CoupangEditor.Document> documents, CoupangEditor.EditorDocument source) {}
    record Naver(String channelId, String status, String saleType, String originCode,
                 String deliveryTemplateId, String afterServiceTelephone,
                 List<Attribute> attributes, List<Attribute> notices,
                 String channelProductName, Boolean naverShoppingRegistration,
                 String channelProductDisplayStatusType,NaverEditor.Input editorInput) {
        public Naver(String channelId,String status,String saleType,String originCode,String deliveryTemplateId,
                     String afterServiceTelephone,List<Attribute> attributes,List<Attribute> notices,
                     String channelProductName,Boolean naverShoppingRegistration,String channelProductDisplayStatusType){
            this(channelId,status,saleType,originCode,deliveryTemplateId,afterServiceTelephone,attributes,notices,channelProductName,naverShoppingRegistration,channelProductDisplayStatusType,null);
        }
        public Naver(String channelId,String status,String saleType,String originCode,String deliveryTemplateId,
                     String afterServiceTelephone,List<Attribute> attributes,List<Attribute> notices) {
            this(channelId,status,saleType,originCode,deliveryTemplateId,afterServiceTelephone,attributes,notices,null,null,null,null);
        }
    }
    /** returnPolicyId is the ESM seller return/exchange address number (addrNo), not a policy API ID. */
    record Esm(String siteId, String goodsStatus, String shippingPolicyId,
               String returnPolicyId, String itemCode, List<Attribute> attributes,
               List<Attribute> notices) {}
    record MarketConfig(String categoryCode, Overrides overrides, Coupang coupang,
                        Naver naver, Esm esm) {}
    record Document(String id, Long revision, Common common, List<Option> options,
                    StockMode stockMode, String productQuantity, List<ServiceOption> services,
                    Media media, Delivery delivery, List<Market> selectedMarkets,
                    Map<Market, MarketConfig> markets) {}
    record Summary(String id, long revision, String productCode, String name,
                   List<Market> selectedMarkets, boolean imported, String updatedAt,String editorKind,
                   String importMarket,String externalProductId) {
        public Summary(String id,long revision,String productCode,String name,List<Market> selectedMarkets,boolean imported,String updatedAt,String editorKind){this(id,revision,productCode,name,selectedMarkets,imported,updatedAt,editorKind,null,null);}
        public Summary(String id,long revision,String productCode,String name,List<Market> selectedMarkets,boolean imported,String updatedAt){this(id,revision,productCode,name,selectedMarkets,imported,updatedAt,"COMMON",null,null);}
    }
    record Issue(String market, String path, String message) {}
    record Validation(boolean valid, List<Issue> errors, List<Issue> unverified) {}
}
