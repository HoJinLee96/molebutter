package cc.ataglace.molebutter.marketplace.api;

import java.util.HashSet;
import java.util.List;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.Mapping;

/** Adapters verify live product/option IDs and market status separately from historical values. */
public final class MarketplaceCreationConfirmation {
    private MarketplaceCreationConfirmation() {}

    public static boolean completeOptions(Mapping mapping,List<String> expectedLocalIds,boolean requireVendorIds) {
        if(mapping==null||blank(mapping.sellerProductId())||mapping.options()==null||expectedLocalIds==null)return false;
        var expected=new HashSet<>(expectedLocalIds);
        if(expected.size()!=expectedLocalIds.size()||expected.stream().anyMatch(MarketplaceCreationConfirmation::blank)
                ||mapping.options().size()!=expected.size())return false;
        var local=new HashSet<String>();var remote=new HashSet<String>();var vendor=new HashSet<String>();
        for(var row:mapping.options()) {
            if(row==null||!expected.contains(row.optionId())||!local.add(row.optionId())
                    ||blank(row.sellerProductItemId())||!remote.add(row.sellerProductItemId()))return false;
            if(requireVendorIds&&blank(row.vendorItemId()))return false;
            if(!blank(row.vendorItemId())&&!vendor.add(row.vendorItemId()))return false;
        }
        return true;
    }

    private static boolean blank(String value){return value==null||value.isBlank();}
}
