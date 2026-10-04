package cc.ataglace.molebutter.dto.inventory;

import java.util.*;

/** Explicit SQL-to-API contracts. New columns require deliberate approval in a role's view. */
public enum InventoryView {
    ITEM("id purchaseId productId originProductId supplierId revision productCode imageUrl color size optionLabel externalOptionId orderedQuantity onHand pending receivedQuantity cancelledQuantity publicNote productDeleted brand purchasedOn supplierName purchaseDeleted", "unitPrice remainingAmount"),
    PURCHASE("id revision purchasedOn supplierName createdBy deletedAt itemCount orderedQuantity receivedQuantity cancelledQuantity onHand pending", "orderNumber orderUrl paymentMethodId paymentAmount paymentMethod paymentAlias paidOn purchaseAmount privateNote pendingRefundCount"),
    MOVEMENT("id itemId kind quantity handDelta pendingDelta occurredAt reason referenceNumber referenceId reversesId actorId actorName reversed productCode productId purchaseId color size optionLabel purchaseDeleted", "refundStatus refundAmount refundRevision refundedOn"),
    STOCK("productId productCode brand imageUrl productDeleted onHand pending itemCount", "remainingAmount"),
    OPTION("color size onHand pending", ""),
    TOTALS("totalOnHand filteredOnHand", ""),
    SUMMARY("productId onHand pending", "");
    private final List<String> publicFields;
    private final List<String> adminFields;
    InventoryView(String publicFields, String adminFields) {
        this.publicFields = List.of(publicFields.split(" "));
        this.adminFields = adminFields.isBlank() ? List.of() : List.of(adminFields.split(" "));
    }
    public Map<String,Object> project(Map<String,Object> row, boolean admin) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (String field : publicFields) if (row.containsKey(field)) result.put(field, row.get(field));
        if (admin) for (String field : adminFields) if (row.containsKey(field)) result.put(field, row.get(field));
        return result;
    }
}
