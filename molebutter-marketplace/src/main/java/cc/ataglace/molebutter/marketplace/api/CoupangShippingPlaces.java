package cc.ataglace.molebutter.marketplace.api;
import java.util.List;
/** Read-only seller address book. Credentials and vendor identity stay on the server. */
public interface CoupangShippingPlaces {
    Page list(Long actor,boolean returns,int page,String requestId);
    Page outbound(Long actor,String code,String requestId);
    record Address(String addressType,String zipCode,String address,String detail,String phone) {}
    record Place(String code,String name,boolean usable,List<Address> addresses) {}
    record Page(List<Place> items,int page,long totalCount,boolean hasNext) {}
}
