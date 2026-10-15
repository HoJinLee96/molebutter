package cc.ataglace.molebutter.marketplace.api;

import java.util.List;

/** Stored, PII-free order observations; shipping detail is fetched live and never persisted. */
public interface MarketplaceOrders {
    OrderPage orders(Long actor, Search search, int page, int size);
    Detail detail(Long actor, String market, String orderId);
    record Search(String market, String status, String query, String dateFrom, String dateTo, String claimType, String dateBasis) {
        public Search(String market,String status,String query,String dateFrom,String dateTo){this(market,status,query,dateFrom,dateTo,null,null);}
    }
    record Money(String currency, String amount) {}
    record ClaimSummary(String type, String id, String status, Long quantity, String updatedAt, boolean linked,boolean latestVerified, String productName, String optionName, String sellerProductId, String vendorItemId, String shipmentBoxId, Long purchaseQuantity, String createdAt) {
        public ClaimSummary(String type,String id,String status,Long quantity,String updatedAt,boolean linked,boolean latestVerified){this(type,id,status,quantity,updatedAt,linked,latestVerified,null,null,null,null,null,null,null);}
        public ClaimSummary(String type,String id,String status,Long quantity,String updatedAt,boolean linked){this(type,id,status,quantity,updatedAt,linked,updatedAt!=null&&!updatedAt.isBlank());}
    }
    record OrderRow(String market, String orderId, String shipmentBoxId, String sequenceNo,
                    String vendorItemId, String sellerProductId, String sellerProductCode, String productName, String optionName,
                    String status, Long quantity, Long cancelQuantity, Long holdQuantity,
                    Money unitPrice, Money orderPrice, String orderedAt, String paidAt, String collectedAt, String statusUpdatedAt,
                    List<ClaimSummary> claims) {
        public OrderRow { claims = List.copyOf(claims); }
    }
    record OrderPage(List<OrderRow> items, int page, int size, long total, long productTotal, long claimOnlyTotal) {
        public OrderPage(List<OrderRow> items,int page,int size,long total){this(items,page,size,total,total,0);}
        public OrderPage { items = List.copyOf(items); }
    }
    record ShipmentDetail(String shipmentBoxId, String status, String recipientName, String phone,
                          String postCode, String address, String addressDetail, String deliveryCompany,
                          String invoiceNumber, String message) {}
    record Detail(String orderId, List<ShipmentDetail> shipments, List<OrderRow> items, String fetchedAt, String storedCollectedAt, String unavailableReason) {
        public Detail(String orderId,List<ShipmentDetail> shipments,List<OrderRow> items,String fetchedAt,String storedCollectedAt){this(orderId,shipments,items,fetchedAt,storedCollectedAt,null);}
        public Detail(String orderId,List<ShipmentDetail> shipments,List<OrderRow> items){this(orderId,shipments,items,null,null);}
        public Detail { shipments = List.copyOf(shipments); items = List.copyOf(items); }
    }
}
