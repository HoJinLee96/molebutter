package cc.ataglace.molebutter.marketplace.api;

/** 마켓 조립용 계약. 각 기능은 작은 조회·메타데이터·입력 계약으로 주입할 수 있다. */
public interface PartnerProducts extends MarketplaceProductAdapter, PartnerProductCatalog,
        PartnerProductMetadata, PartnerProductInputs {
}
