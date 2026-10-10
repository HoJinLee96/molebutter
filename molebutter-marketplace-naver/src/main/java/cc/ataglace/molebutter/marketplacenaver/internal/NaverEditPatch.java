package cc.ataglace.molebutter.marketplacenaver.internal;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDocuments;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.Document;

/** Typed SmartStore editor values; neither external identities nor source envelopes enter this boundary. */
final class NaverEditPatch {
    private static final ObjectMapper JSON=new ObjectMapper();
    static final String PREFIX="markets.NAVER.naver.editorInput.";
    static final Set<String> SCALARS=Set.of(
        "originProduct.customerBenefit.immediateDiscountPolicy.discountMethod.value",
        "originProduct.customerBenefit.immediateDiscountPolicy.discountMethod.unitType",
        "originProduct.customerBenefit.immediateDiscountPolicy.discountMethod.startDate",
        "originProduct.customerBenefit.immediateDiscountPolicy.discountMethod.endDate",
        "originProduct.statusType",
        "originProduct.saleType",
        "originProduct.leafCategoryId",
        "originProduct.name",
        "originProduct.salePrice",
        "originProduct.stockQuantity",
        "originProduct.saleStartDate",
        "originProduct.saleEndDate",
        "originProduct.detailAttribute.naverShoppingSearchInfo.brandId",
        "originProduct.detailAttribute.naverShoppingSearchInfo.brandName",
        "originProduct.detailAttribute.naverShoppingSearchInfo.manufacturerName",
        "originProduct.detailAttribute.naverShoppingSearchInfo.modelId",
        "originProduct.detailAttribute.naverShoppingSearchInfo.modelName",
        "originProduct.detailAttribute.manufactureDefineNo",
        "originProduct.detailAttribute.afterServiceInfo.afterServiceTelephoneNumber",
        "originProduct.detailAttribute.afterServiceInfo.afterServiceGuideContent",
        "originProduct.detailAttribute.purchaseQuantityInfo.minPurchaseQuantity",
        "originProduct.detailAttribute.purchaseQuantityInfo.maxPurchaseQuantityPerId",
        "originProduct.detailAttribute.purchaseQuantityInfo.maxPurchaseQuantityPerOrder",
        "originProduct.detailAttribute.originAreaInfo.originAreaCode",
        "originProduct.detailAttribute.originAreaInfo.importer",
        "originProduct.detailAttribute.originAreaInfo.content",
        "originProduct.detailAttribute.originAreaInfo.plural",
        "originProduct.detailAttribute.sellerCodeInfo.sellerManagementCode",
        "originProduct.detailAttribute.sellerCodeInfo.sellerBarcode",
        "originProduct.detailAttribute.sellerCodeInfo.sellerCustomCode1",
        "originProduct.detailAttribute.sellerCodeInfo.sellerCustomCode2",
        "originProduct.detailAttribute.taxType",
        "originProduct.detailAttribute.minorPurchasable",
        "originProduct.detailAttribute.manufactureDate",
        "originProduct.detailAttribute.releaseDate",
        "originProduct.detailAttribute.validDate",
        "originProduct.detailAttribute.certificationTargetExcludeContent.childCertifiedProductExclusionYn",
        "originProduct.detailAttribute.certificationTargetExcludeContent.kcExemptionType",
        "originProduct.detailAttribute.certificationTargetExcludeContent.kcCertifiedProductExclusionYn",
        "originProduct.detailAttribute.certificationTargetExcludeContent.greenCertifiedProductExclusionYn",
        "originProduct.detailAttribute.certificationTargetExcludeContent.chemicalCertifiedProductExclusionYn",
        "originProduct.detailAttribute.seoInfo.pageTitle",
        "originProduct.detailAttribute.seoInfo.metaDescription",
        "originProduct.deliveryInfo.deliveryType",
        "originProduct.deliveryInfo.deliveryAttributeType",
        "originProduct.deliveryInfo.deliveryCompany",
        "originProduct.deliveryInfo.deliveryBundleGroupUsable",
        "originProduct.deliveryInfo.deliveryBundleGroupId",
        "originProduct.deliveryInfo.deliveryFee.deliveryFeeType",
        "originProduct.deliveryInfo.deliveryFee.baseFee",
        "originProduct.deliveryInfo.deliveryFee.freeConditionalAmount",
        "originProduct.deliveryInfo.deliveryFee.repeatQuantity",
        "originProduct.deliveryInfo.deliveryFee.secondBaseQuantity",
        "originProduct.deliveryInfo.deliveryFee.secondExtraFee",
        "originProduct.deliveryInfo.deliveryFee.thirdBaseQuantity",
        "originProduct.deliveryInfo.deliveryFee.thirdExtraFee",
        "originProduct.deliveryInfo.deliveryFee.deliveryFeePayType",
        "originProduct.deliveryInfo.deliveryFee.deliveryFeeByArea.deliveryAreaType",
        "originProduct.deliveryInfo.deliveryFee.deliveryFeeByArea.area2extraFee",
        "originProduct.deliveryInfo.deliveryFee.deliveryFeeByArea.area3extraFee",
        "originProduct.deliveryInfo.deliveryFee.differentialFeeByArea",
        "originProduct.deliveryInfo.claimDeliveryInfo.returnDeliveryCompanyPriorityType",
        "originProduct.deliveryInfo.claimDeliveryInfo.returnDeliveryFee",
        "originProduct.deliveryInfo.claimDeliveryInfo.exchangeDeliveryFee",
        "originProduct.deliveryInfo.claimDeliveryInfo.shippingAddressId",
        "originProduct.deliveryInfo.claimDeliveryInfo.returnAddressId",
        "smartstoreChannelProduct.channelProductName",
        "smartstoreChannelProduct.naverShoppingRegistration",
        "smartstoreChannelProduct.channelProductDisplayStatusType",
        "smartstoreChannelProduct.storeKeepExclusiveProduct",
        "smartstoreChannelProduct.bbsSeq");
    static final Set<String> COLLECTIONS=Set.of("originProduct.detailAttribute.productCertificationInfos","originProduct.detailAttribute.productAttributes","originProduct.detailAttribute.seoInfo.sellerTags","originProduct.detailAttribute.productInfoProvidedNotice");
    private static final Set<String> INTEGERS=Set.of("salePrice","stockQuantity","brandId","modelId","minPurchaseQuantity","maxPurchaseQuantityPerId","maxPurchaseQuantityPerOrder","deliveryBundleGroupId","baseFee","freeConditionalAmount","repeatQuantity","secondBaseQuantity","secondExtraFee","thirdBaseQuantity","thirdExtraFee","area2extraFee","area3extraFee","returnDeliveryFee","exchangeDeliveryFee","shippingAddressId","returnAddressId","bbsSeq","certificationInfoId","attributeSeq","attributeValueSeq","code");
    private static final Set<String> BOOLEANS=Set.of("minorPurchasable","plural","deliveryBundleGroupUsable","naverShoppingRegistration","storeKeepExclusiveProduct","childCertifiedProductExclusionYn","greenCertifiedProductExclusionYn","chemicalCertifiedProductExclusionYn","certificationMark");
    static final Map<String,Set<String>> NOTICE_FIELDS;
    static final Map<String,Set<String>> NOTICE_REQUIRED;
    static {var notice=new LinkedHashMap<String,Set<String>>();
        notice.put("wear",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","material","color","size","manufacturer","caution","packDate","packDateText","warrantyPolicy","afterServiceDirector"));
        notice.put("shoes",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","material","color","size","height","manufacturer","caution","warrantyPolicy","afterServiceDirector"));
        notice.put("bag",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","type","material","color","size","manufacturer","caution","warrantyPolicy","afterServiceDirector"));
        notice.put("fashionItems",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","type","material","size","manufacturer","caution","warrantyPolicy","afterServiceDirector"));
        notice.put("sleepingGear",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","material","color","size","components","manufacturer","caution","warrantyPolicy","afterServiceDirector"));
        notice.put("furniture",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","certificationType","color","components","material","manufacturer","importer","producer","size","installedCharge","warrantyPolicy","refurb","afterServiceDirector"));
        notice.put("imageAppliances",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","ratedVoltage","powerConsumption","energyEfficiencyRating","releaseDate","releaseDateText","manufacturer","size","additionalCost","displaySpecification","warrantyPolicy","afterServiceDirector"));
        notice.put("homeAppliances",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","ratedVoltage","powerConsumption","energyEfficiencyRating","releaseDate","releaseDateText","manufacturer","size","additionalCost","warrantyPolicy","afterServiceDirector"));
        notice.put("seasonAppliances",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","ratedVoltage","powerConsumption","energyEfficiencyRating","releaseDate","releaseDateText","manufacturer","size","area","installedCharge","warrantyPolicy","afterServiceDirector"));
        notice.put("officeAppliances",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","ratedVoltage","powerConsumption","energyEfficiencyRating","releaseDate","releaseDateText","manufacturer","size","weight","specification","warrantyPolicy","afterServiceDirector"));
        notice.put("opticsAppliances",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","releaseDate","releaseDateText","manufacturer","size","weight","specification","warrantyPolicy","afterServiceDirector"));
        notice.put("microElectronics",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","ratedVoltage","powerConsumption","releaseDate","releaseDateText","manufacturer","size","weight","specification","warrantyPolicy","afterServiceDirector"));
        notice.put("navigation",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","ratedVoltage","powerConsumption","releaseDate","releaseDateText","manufacturer","size","weight","specification","updateCost","freeCostPeriod","warrantyPolicy","afterServiceDirector"));
        notice.put("carArticles",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","releaseDate","releaseDateText","certificationType","caution","manufacturer","size","applyModel","warrantyPolicy","roadWorthyCertification","afterServiceDirector"));
        notice.put("medicalAppliances",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","licenceNo","advertisingCertificationType","ratedVoltage","powerConsumption","releaseDate","releaseDateText","manufacturer","purpose","usage","caution","warrantyPolicy","afterServiceDirector"));
        notice.put("kitchenUtensils",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","material","component","size","releaseDate","releaseDateText","manufacturer","producer","importDeclaration","warrantyPolicy","afterServiceDirector"));
        notice.put("cosmetic",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","capacity","specification","expirationDate","expirationDateText","usage","manufacturer","producer","distributor","customizedDistributor","mainIngredient","certificationType","caution","warrantyPolicy","customerServicePhoneNumber"));
        notice.put("jewellery",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","material","purity","bandMaterial","weight","manufacturer","producer","size","caution","specification","provideWarranty","warrantyPolicy","afterServiceDirector"));
        notice.put("food",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","foodItem","weight","amount","size","packDate","packDateText","expirationDate","expirationDateText","consumptionDate","consumptionDateText","producer","relevantLawContent","productComposition","keep","adCaution","customerServicePhoneNumber"));
        notice.put("generalFood",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","productName","foodType","producer","location","packDate","packDateText","expirationDate","expirationDateText","consumptionDate","consumptionDateText","weight","amount","ingredients","nutritionFacts","geneticallyModified","consumerSafetyCaution","importDeclarationCheck","customerServicePhoneNumber"));
        notice.put("dietFood",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","productName","producer","location","expirationDate","expirationDateText","consumptionDate","consumptionDateText","storageMethod","weight","amount","ingredients","nutritionFacts","specification","cautionAndSideEffect","nonMedicinalUsesMessage","geneticallyModified","importDeclarationCheck","consumerSafetyCaution","customerServicePhoneNumber"));
        notice.put("kids",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","size","weight","color","material","recommendedAge","releaseDate","releaseDateText","manufacturer","caution","warrantyPolicy","afterServiceDirector","numberLimit"));
        notice.put("musicalInstrument",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","size","color","material","components","releaseDate","releaseDateText","manufacturer","detailContent","warrantyPolicy","afterServiceDirector"));
        notice.put("sportsEquipment",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","size","weight","color","material","components","releaseDate","releaseDateText","manufacturer","detailContent","warrantyPolicy","afterServiceDirector"));
        notice.put("books",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","title","author","publisher","size","pages","components","publishDate","publishDateText","description"));
        notice.put("rentalEtc",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","ownershipTransferCondition","payingForLossOrDamage","refundPolicyForCancel","customerServicePhoneNumber"));
        notice.put("rentalHa",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","ownershipTransferCondition","payingForLossOrDamage","refundPolicyForCancel","customerServicePhoneNumber","maintenance","specification"));
        notice.put("digitalContents",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","producer","termsOfUse","usePeriod","medium","requirement","cancelationPolicy","customerServicePhoneNumber"));
        notice.put("giftCard",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","issuer","periodStartDate","periodEndDate","periodDays","termsOfUse","useStorePlace","useStoreAddressId","useStoreUrl","refundPolicy","customerServicePhoneNumber"));
        notice.put("mobileCoupon",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","issuer","usableCondition","usableStore","cancelationPolicy","customerServicePhoneNumber"));
        notice.put("movieShow",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","sponsor","actor","rating","showTime","showPlace","cancelationCondition","cancelationPolicy","customerServicePhoneNumber"));
        notice.put("etcService",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","serviceProvider","certificateDetails","usableCondition","cancelationStandard","cancelationPolicy","customerServicePhoneNumber"));
        notice.put("biochemistry",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","productName","dosageForm","packDate","packDateText","expirationDate","expirationDateText","weight","effect","importer","producer","manufacturer","childProtection","chemicals","caution","safeCriterionNo","customerServicePhoneNumber"));
        notice.put("biocidal",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","productName","weight","effect","rangeOfUse","importer","producer","manufacturer","childProtection","harmfulChemicalSubstance","maleficence","caution","approvalNumber","customerServicePhoneNumber","expirationDate","expirationDateText"));
        notice.put("cellPhone",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","releaseDate","releaseDateText","manufacturer","importer","producer","size","weight","telecomType","joinProcess","extraBurden","specification","warrantyPolicy","afterServiceDirector"));
        notice.put("etc",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificateDetails","manufacturer","afterServiceDirector","customerServicePhoneNumber"));
        NOTICE_FIELDS=Collections.unmodifiableMap(notice);
        var required=new LinkedHashMap<String,Set<String>>();
        required.put("wear",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","material","color","size","manufacturer","caution","warrantyPolicy","afterServiceDirector"));
        required.put("shoes",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","material","color","size","manufacturer","caution","warrantyPolicy","afterServiceDirector"));
        required.put("bag",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","type","material","color","size","manufacturer","caution","warrantyPolicy","afterServiceDirector"));
        required.put("fashionItems",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","type","material","size","manufacturer","caution","warrantyPolicy","afterServiceDirector"));
        required.put("sleepingGear",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","material","color","size","components","manufacturer","caution","warrantyPolicy","afterServiceDirector"));
        required.put("furniture",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","certificationType","color","components","material","manufacturer","producer","size","installedCharge","warrantyPolicy","afterServiceDirector"));
        required.put("imageAppliances",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","manufacturer","size","additionalCost","displaySpecification","warrantyPolicy","afterServiceDirector"));
        required.put("homeAppliances",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","manufacturer","size","additionalCost","warrantyPolicy","afterServiceDirector"));
        required.put("seasonAppliances",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","manufacturer","size","area","installedCharge","warrantyPolicy","afterServiceDirector"));
        required.put("officeAppliances",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","manufacturer","size","specification","warrantyPolicy","afterServiceDirector"));
        required.put("opticsAppliances",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","manufacturer","size","weight","specification","warrantyPolicy","afterServiceDirector"));
        required.put("microElectronics",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","ratedVoltage","powerConsumption","manufacturer","size","weight","specification","warrantyPolicy","afterServiceDirector"));
        required.put("navigation",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","ratedVoltage","powerConsumption","manufacturer","size","weight","specification","updateCost","freeCostPeriod","warrantyPolicy","afterServiceDirector"));
        required.put("carArticles",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","caution","manufacturer","size","applyModel","warrantyPolicy","roadWorthyCertification","afterServiceDirector"));
        required.put("medicalAppliances",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","advertisingCertificationType","manufacturer","purpose","usage","caution","warrantyPolicy","afterServiceDirector"));
        required.put("kitchenUtensils",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","material","component","size","manufacturer","producer","warrantyPolicy","afterServiceDirector"));
        required.put("cosmetic",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","capacity","specification","usage","manufacturer","producer","distributor","mainIngredient","certificationType","caution","warrantyPolicy","customerServicePhoneNumber"));
        required.put("jewellery",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","material","purity","weight","manufacturer","size","caution","specification","provideWarranty","warrantyPolicy","afterServiceDirector"));
        required.put("food",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","foodItem","weight","amount","size","producer","productComposition","keep","adCaution","customerServicePhoneNumber"));
        required.put("generalFood",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","productName","foodType","producer","location","weight","amount","ingredients","geneticallyModified","consumerSafetyCaution","customerServicePhoneNumber"));
        required.put("dietFood",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","productName","producer","location","storageMethod","weight","amount","ingredients","nutritionFacts","specification","cautionAndSideEffect","nonMedicinalUsesMessage","geneticallyModified","consumerSafetyCaution","customerServicePhoneNumber"));
        required.put("kids",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","size","weight","color","material","recommendedAge","manufacturer","caution","warrantyPolicy","afterServiceDirector"));
        required.put("musicalInstrument",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","size","color","material","components","manufacturer","detailContent","warrantyPolicy","afterServiceDirector"));
        required.put("sportsEquipment",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","size","weight","color","material","components","manufacturer","detailContent","warrantyPolicy","afterServiceDirector"));
        required.put("books",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","title","author","publisher","size","pages","description"));
        required.put("rentalEtc",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","payingForLossOrDamage","refundPolicyForCancel","customerServicePhoneNumber"));
        required.put("rentalHa",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","payingForLossOrDamage","refundPolicyForCancel","customerServicePhoneNumber"));
        required.put("digitalContents",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","producer","termsOfUse","usePeriod","medium","requirement","cancelationPolicy","customerServicePhoneNumber"));
        required.put("giftCard",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","issuer","termsOfUse","refundPolicy","customerServicePhoneNumber"));
        required.put("mobileCoupon",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","issuer","usableCondition","usableStore","cancelationPolicy","customerServicePhoneNumber"));
        required.put("movieShow",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","sponsor","actor","rating","showTime","showPlace","cancelationCondition","cancelationPolicy","customerServicePhoneNumber"));
        required.put("etcService",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","serviceProvider","certificateDetails","usableCondition","cancelationStandard","cancelationPolicy","customerServicePhoneNumber"));
        required.put("biochemistry",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","productName","dosageForm","weight","effect","producer","manufacturer","childProtection","chemicals","caution","safeCriterionNo","customerServicePhoneNumber"));
        required.put("biocidal",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","productName","weight","effect","rangeOfUse","producer","manufacturer","childProtection","harmfulChemicalSubstance","maleficence","caution","approvalNumber","customerServicePhoneNumber"));
        required.put("cellPhone",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","certificationType","manufacturer","producer","size","weight","telecomType","joinProcess","extraBurden","specification","warrantyPolicy","afterServiceDirector"));
        required.put("etc",Set.of("returnCostReason","noRefundReason","qualityAssuranceStandard","compensationProcedure","troubleShootingContents","itemName","modelName","manufacturer"));
        NOTICE_REQUIRED=Collections.unmodifiableMap(required);
    }
    private NaverEditPatch(){}
    static NaverEditor.Input normalize(NaverEditor.Input input){
        if(input==null)throw invalid("스마트스토어 입력을 확인해 주세요.");
        var fields=new LinkedHashMap<String,Object>();
        if(input.fields()!=null)for(var entry:input.fields().entrySet()){
            String path=entry.getKey();if(path==null||!SCALARS.contains(path)&&!COLLECTIONS.contains(path))throw invalid("수정할 수 없는 스마트스토어 항목입니다: "+path);
            var value=JSON.valueToTree(entry.getValue());
            if(COLLECTIONS.contains(path))value=collection(path,value,false);
            else if(!value.isNull()&&!value.isValueNode())throw invalid("항목의 입력 형식을 확인해 주세요: "+path);
            if(!COLLECTIONS.contains(path))value=scalar(path,value);
            if(value.isString()&&(value.asString().length()>4000||value.asString().indexOf('\0')>=0))throw invalid("입력 내용의 길이를 줄여 주세요.");
            fields.put(path,JSON.convertValue(value,Object.class));
        }
        String mode=input.optionMode()==null?"NONE":input.optionMode();if(!Set.of("NONE","COMBINATION","PRESERVE").contains(mode))throw invalid("옵션 유형을 확인해 주세요.");
        var names=MarketplaceDocuments.list(input.optionNames(),3).stream().map(v->MarketplaceDocuments.text(v,200)).toList();
        var options=new ArrayList<NaverEditor.Option>();var ids=new HashSet<String>();
        for(var o:MarketplaceDocuments.list(input.options(),200)){
            String id=MarketplaceDocuments.uuid(o.id());if(!ids.add(id))throw invalid("옵션 식별자가 중복되었습니다.");
            var values=MarketplaceDocuments.list(o.values(),3).stream().map(v->MarketplaceDocuments.text(v,200)).toList();
            options.add(new NaverEditor.Option(id,values,o.price(),o.stockQuantity(),MarketplaceDocuments.text(o.sellerManagerCode(),200),o.usable()));
        }
        var images=new ArrayList<NaverEditor.Image>();ids.clear();
        for(var image:MarketplaceDocuments.list(input.images(),10)){
            String id=MarketplaceDocuments.uuid(image.id());if(!ids.add(id))throw invalid("이미지 식별자가 중복되었습니다.");
            String asset=blank(image.assetId())?null:MarketplaceDocuments.uuid(image.assetId());String url=blank(image.url())?null:MarketplaceDocuments.text(image.url(),4000);
            if(asset==null&&url!=null&&!MarketplaceDocuments.https(url))throw invalid("이미지 주소는 공개 HTTPS 주소로 입력해 주세요.");
            if(image.order()<0||image.order()>1000)throw invalid("이미지 순서를 확인해 주세요.");
            images.add(new NaverEditor.Image(id,asset,asset==null?url:"/api/marketplaces/assets/"+asset,image.representative(),image.order()));
        }
        return new NaverEditor.Input(Collections.unmodifiableMap(fields),mode,names,List.copyOf(options),List.copyOf(images),MarketplaceDocuments.text(input.description(),2*1024*1024));
    }
    static List<MarketplaceEditing.Change> diff(NaverEditor.Input observed,NaverEditor.Input edited){
        var before=normalize(observed);var after=normalize(edited);var changes=new ArrayList<MarketplaceEditing.Change>();
        var keys=new LinkedHashSet<>(before.fields().keySet());keys.addAll(after.fields().keySet());
        for(var key:keys)if(!equivalent(before.fields().get(key),after.fields().get(key)))changes.add(new MarketplaceEditing.Change(PREFIX+"fields."+key,null,after.fields().get(key)));
        var b=JSON.valueToTree(before);var a=JSON.valueToTree(after);
        for(String key:List.of("optionMode","optionNames","options","images","description"))if(!jsonEquivalent(b.path(key),a.path(key)))changes.add(new MarketplaceEditing.Change(PREFIX+key,null,JSON.convertValue(a.path(key),Object.class)));
        return List.copyOf(changes);
    }
    static Document apply(Document document,List<MarketplaceEditing.Change> changes){
        var input=NaverDraftAdapter.from(document);var tree=JSON.valueToTree(input).deepCopy().asObject();
        for(var c:MarketplaceDocuments.list(changes,500)){
            if(c.optionId()!=null||c.path()==null||!c.path().startsWith(PREFIX))throw invalid("스마트스토어 수정 항목을 확인해 주세요.");
            String key=c.path().substring(PREFIX.length());
            if(key.startsWith("fields.")){String path=key.substring(7);if(!SCALARS.contains(path)&&!COLLECTIONS.contains(path))throw invalid("수정할 수 없는 스마트스토어 항목입니다.");tree.path("fields").asObject().set(path,JSON.valueToTree(c.value()));}
            else if(Set.of("optionMode","optionNames","options","images","description").contains(key))tree.set(key,JSON.valueToTree(c.value()));
            else throw invalid("스마트스토어 수정 항목을 확인해 주세요.");
        }
        return NaverDraftAdapter.document(document.id(),document.revision(),normalize(JSON.treeToValue(tree,NaverEditor.Input.class)));
    }
    static Object value(NaverEditor.Input input,MarketplaceEditing.Change change){String key=change.path().substring(PREFIX.length());return key.startsWith("fields.")?input.fields().get(key.substring(7)):JSON.convertValue(JSON.valueToTree(input).path(key),Object.class);}
    static boolean equivalent(Object a,Object b){return jsonEquivalent(JSON.valueToTree(a),JSON.valueToTree(b));}
    /** JSON persistence can narrow LongNode to IntNode. Numeric value is semantic; structure is exact. */
    static boolean jsonEquivalent(JsonNode a,JsonNode b){
        if(a==b)return true;if(a==null||b==null)return false;
        if(a.isNumber()&&b.isNumber())try{return new java.math.BigDecimal(a.asString()).compareTo(new java.math.BigDecimal(b.asString()))==0;}catch(NumberFormatException e){return false;}
        if(a.isObject()&&b.isObject()){if(a.size()!=b.size())return false;for(var entry:a.properties())if(!b.has(entry.getKey())||!jsonEquivalent(entry.getValue(),b.path(entry.getKey())))return false;return true;}
        if(a.isArray()&&b.isArray()){if(a.size()!=b.size())return false;for(int i=0;i<a.size();i++)if(!jsonEquivalent(a.get(i),b.get(i)))return false;return true;}
        return a.equals(b);
    }
    private static JsonNode scalar(String path,JsonNode value){
        String key=path.substring(path.lastIndexOf('.')+1);
        if(INTEGERS.contains(key)){
            if(value.isNull()||value.isString()&&value.asString().isBlank())return JSON.nullNode();
            try{var n=new java.math.BigInteger(value.asString());if(n.bitLength()>63)throw new IllegalArgumentException();return JSON.valueToTree(n.longValueExact());}catch(RuntimeException e){throw invalid("정수 입력을 확인해 주세요: "+path);}
        }
        if(path.equals("originProduct.customerBenefit.immediateDiscountPolicy.discountMethod.value")){
            if(value.isNull()||value.isString()&&value.asString().isBlank())return JSON.nullNode();
            try{return JSON.valueToTree(new BigDecimal(value.asString()));}catch(RuntimeException e){throw invalid("할인 값을 확인해 주세요.");}
        }
        if(BOOLEANS.contains(key)){
            if(value.isNull())return value;if(value.isBoolean())return value;if(value.isString()&&Set.of("true","false").contains(value.asString()))return JSON.valueToTree(Boolean.parseBoolean(value.asString()));throw invalid("참·거짓 선택 값을 확인해 주세요.");
        }
        return value;
    }
    static JsonNode collection(String path,JsonNode value,boolean selectedOnly){
        if(value.isNull())return value;
        if(path.endsWith("productInfoProvidedNotice")){
            if(!value.isObject())throw invalid("상품정보제공고시 형식을 확인해 주세요.");
            String type=value.path("productInfoProvidedNoticeType").asString("");String selected=noticeKey(type);
            if(selected==null){if(type.isEmpty()&&value.isEmpty())return value;throw invalid("지원하지 않는 상품정보제공고시 유형입니다.");}
            if(!value.path(selected).isObject())throw invalid("선택한 고시 유형의 값을 입력해 주세요.");
            var result=JSON.createObjectNode().put("productInfoProvidedNoticeType",type);var child=JSON.createObjectNode();
            for(var e:value.path(selected).properties()){
                if(!NOTICE_FIELDS.get(selected).contains(e.getKey()))throw invalid("상품정보제공고시 항목을 확인해 주세요.");
                if(!e.getValue().isValueNode()){
                    if(!e.getValue().isObject()||!Set.of("seasonAppliances","officeAppliances","sportsEquipment").contains(selected)||!e.getKey().equals("releaseDate"))throw invalid("상품정보제공고시 항목의 형식을 확인해 주세요.");
                    for(var date:e.getValue().properties())if(!Set.of("year","month","monthValue","leapYear").contains(date.getKey())||!date.getValue().isValueNode())throw invalid("고시 출시일의 형식을 확인해 주세요.");
                }
                child.set(e.getKey(),e.getValue());
            }
            result.set(selected,child);return result;
        }
        if(!value.isArray()||value.size()>200)throw invalid("인증·속성·검색어 입력 항목을 확인해 주세요.");
        Set<String> allowed=path.endsWith("productCertificationInfos")?Set.of("certificationInfoId","certificationKindType","name","certificationNumber","certificationMark","companyName","certificationDate"):path.endsWith("productAttributes")?Set.of("attributeSeq","attributeValueSeq","attributeRealValue","attributeRealValueUnitCode"):Set.of("code","text");
        for(var row:value){if(!row.isObject())throw invalid("입력 항목의 형식을 확인해 주세요.");for(var e:row.properties())if(!allowed.contains(e.getKey())||!e.getValue().isValueNode())throw invalid("허용되지 않는 인증·속성·검색어 항목입니다.");}
        var canonical=value.deepCopy();for(var row:canonical)for(var e:row.properties())row.asObject().set(e.getKey(),scalar(e.getKey(),e.getValue()));return canonical;
    }
    static String noticeKey(String type){
        if(type.equals("MICROELECTRONICS"))return "microElectronics";if(type.equals("CELLPHONE"))return "cellPhone";
        String[] words=type.toLowerCase(Locale.ROOT).split("_");var s=new StringBuilder(words[0]);for(int i=1;i<words.length;i++)s.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));String key=s.toString();return NOTICE_FIELDS.containsKey(key)?key:null;
    }
    static NaverEditor.EditorDocument editor(JsonNode source){
        JsonNode origin=source.path("originProduct");if(!origin.isObject())throw invalid("스마트스토어 상품 조회 형식을 확인해 주세요.");
        String originNo=id(source.path("originProductNo"));if(originNo==null)originNo=id(origin.path("originProductNo"));
        JsonNode channel=source.path("smartstoreChannelProduct");String channelNo=id(source.path("smartstoreChannelProductNo"));if(channelNo==null)channelNo=id(channel.path("channelProductNo"));
        var fields=new LinkedHashMap<String,Object>();for(String path:SCALARS){var v=at(source,path);if(!v.isMissingNode()&&!v.isNull())fields.put(path,JSON.convertValue(v,Object.class));}
        for(String path:COLLECTIONS){var v=at(source,path);if(!v.isMissingNode()&&!v.isNull())fields.put(path,JSON.convertValue(collection(path,v,true),Object.class));}
        var info=origin.path("detailAttribute").path("optionInfo");boolean combination=info.path("optionCombinations").isArray()&&!info.path("optionCombinations").isEmpty();
        boolean standard=nonempty(info.path("standardOptionGroups"))||nonempty(info.path("optionStandards"));
        boolean branch=!info.path("optionCombinationGroupNames").path("optionGroupName4").asString("").isBlank();
        boolean other=nonempty(info.path("optionSimple"))||nonempty(info.path("optionCustom"))||standard||branch||nonempty(info.path("optionSimpleNames"));
        var options=new ArrayList<NaverEditor.Option>();var identities=new ArrayList<NaverEditor.OptionIdentity>();var names=new ArrayList<String>();
        String mode=other?"PRESERVE":combination?"COMBINATION":"NONE";
        if(combination&&!other){for(int i=1;i<=3;i++){String n=info.path("optionCombinationGroupNames").path("optionGroupName"+i).asString("");if(!n.isBlank())names.add(n);}
            for(var row:info.path("optionCombinations")){var values=new ArrayList<String>();for(int i=1;i<=names.size();i++)values.add(row.path("optionName"+i).asString(""));String remote=id(row.path("id"));String local=uuid("naver:"+originNo+":option:"+(remote==null?values.toString():remote));options.add(new NaverEditor.Option(local,List.copyOf(values),number(row.path("price"),null),number(row.path("stockQuantity"),null),row.path("sellerManagerCode").asString(""),row.path("usable").asBoolean(true)));identities.add(new NaverEditor.OptionIdentity(local,remote));}}
        var images=new ArrayList<NaverEditor.Image>();var rawImages=origin.path("images");String representative=rawImages.path("representativeImage").path("url").asString("");if(!representative.isBlank())images.add(new NaverEditor.Image(uuid("naver:"+originNo+":image:"+representative),null,representative,true,0));int order=1;for(var image:rawImages.path("optionalImages")){String url=image.path("url").asString("");if(!url.isBlank())images.add(new NaverEditor.Image(uuid("naver:"+originNo+":image:"+(order)+":"+url),null,url,false,order++));}
        boolean group=nonempty(source.path("groupProduct").path("groupProductNo"))||nonempty(origin.path("groupProductNo"))||nonempty(source.path("groupProductNo"));boolean model=origin.path("detailAttribute").path("naverShoppingSearchInfo").path("catalogMatchingYn").asBoolean(false)||origin.path("detailAttribute").path("catalogMatchingYn").asBoolean(false);
        String message=group?"그룹 상품은 전용 그룹 상품 API가 필요하여 이 화면에서 수정할 수 없습니다.":other?"단독형·직접 입력형·표준형·지점형 옵션 구조는 유지합니다. 기존 옵션 원문은 서버에서 보존합니다.":model?"카탈로그에 매칭된 상품의 카테고리·모델은 스마트스토어센터에서 변경해 주세요.":null;
        var input=normalize(new NaverEditor.Input(fields,mode,names,options,images,origin.path("detailContent").asString("")));
        return new NaverEditor.EditorDocument(input,new NaverEditor.Limits(group,other,standard||model,model,message),List.copyOf(identities),originNo,channelNo);
    }
    static JsonNode at(JsonNode root,String path){JsonNode value=root;for(String part:path.split("\\."))value=value.path(part);return value;}
    static void put(ObjectNode root,String path,JsonNode value){String[] parts=path.split("\\.");ObjectNode parent=root;for(int i=0;i<parts.length-1;i++){var node=parent.path(parts[i]);if(!node.isObject()){node=JSON.createObjectNode();parent.set(parts[i],node);}parent=node.asObject();}if(value==null||value.isNull())parent.remove(parts[parts.length-1]);else parent.set(parts[parts.length-1],value);}
    static String id(JsonNode node){if(node.isIntegralNumber()&&node.canConvertToLong()&&node.asLong()>0)return node.asString();if(node.isString()&&node.asString().matches("[1-9][0-9]*"))return node.asString();return null;}
    static Long number(JsonNode node,Long fallback){if(node.isIntegralNumber()&&node.canConvertToLong())return node.asLong();return fallback;}
    static boolean blank(String value){return value==null||value.isBlank();}
    static String uuid(String seed){return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8)).toString();}
    static boolean nonempty(JsonNode n){return !n.isMissingNode()&&!n.isNull()&&(!n.isContainer()||!n.isEmpty());}
    static InputValidationFailure invalid(String message){return new InputValidationFailure(message);}
}
