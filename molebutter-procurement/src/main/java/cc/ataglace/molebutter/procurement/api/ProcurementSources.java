package cc.ataglace.molebutter.procurement.api;
import java.util.List;
import cc.ataglace.molebutter.procurement.internal.ProductSourceMetadata;
public final class ProcurementSources {
    private ProcurementSources() {}
    public static List<ProductDtos.ProcurementMall> supportedMalls(){return ProductSourceMetadata.supportedMalls();}
}
