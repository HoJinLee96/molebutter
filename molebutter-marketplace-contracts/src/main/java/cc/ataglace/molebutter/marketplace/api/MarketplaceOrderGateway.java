package cc.ataglace.molebutter.marketplace.api;

import java.time.LocalDate;
import java.util.List;
import cc.ataglace.molebutter.marketplace.api.MarketplaceOrders.*;

/** Normalized collection observations exclude raw responses and shipping PII. */
public interface MarketplaceOrderGateway {
    record Snapshot(String orderId,List<OrderRow> items,boolean fullDetailValid) {
        public Snapshot { items=List.copyOf(items); }
    }
    record ClaimObservation(String orderId,String shipmentBoxId,String vendorItemId,ClaimSummary summary,String createdAt) {
        public ClaimObservation(String orderId,String shipmentBoxId,String vendorItemId,ClaimSummary summary) { this(orderId,shipmentBoxId,vendorItemId,summary,null); }
    }
    record Page(List<Snapshot> orders,List<ClaimObservation> claims,String nextCursor,boolean complete,String message) {
        public Page { orders=List.copyOf(orders); claims=List.copyOf(claims); }
    }
    String market();
    String accountKey();
    boolean configured();
    boolean claimsVerified();
    List<String> streams();
    boolean orderStream(String stream);
    Page fetch(String stream,LocalDate from,LocalDate to,String cursor);
    Snapshot snapshot(String orderId);
    Detail detail(String orderId);
    Detail liveDetail(String orderId);
    Page returnClaim(String receiptId);
    Page withdrawalClaims(List<String> receiptIds);
    Page exchangeForOrder(LocalDate from,LocalDate to,String cursor,String orderId);
}
