package cc.ataglace.molebutter.dto.inventory;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

public final class InventoryDtos {
    private InventoryDtos() {}
    public record PurchaseInput(LocalDate purchasedOn, String supplierName, String orderNumber, String orderUrl,
                                String paymentMethod, String paymentAlias, LocalDate paidOn, String privateNote,
                                List<ItemInput> items, String paymentMethodId,
                                @JsonDeserialize(using=InventoryIntegerDeserializer.class) Long paymentAmount) {}
    public record PurchaseEdit(@JsonDeserialize(using=InventoryIntegerDeserializer.class) Long revision, LocalDate purchasedOn, String supplierName, String orderNumber,
                               String orderUrl, String paymentMethod, String paymentAlias, LocalDate paidOn, String privateNote,
                               String paymentMethodId, @JsonDeserialize(using=InventoryIntegerDeserializer.class) Long paymentAmount) {}
    public record PurchaseDelete(@JsonDeserialize(using=InventoryIntegerDeserializer.class) Long revision, String deleteToken) {}
    // Retired location and snapshot request fields are accepted only to preserve old operation fingerprints.
    public record ItemInput(String productId, String supplierId, String purchasedCode, String purchasedName,
                            String color, String size, String optionLabel, String externalOptionId,
                            @JsonDeserialize(using=InventoryIntegerDeserializer.class) Long orderedQuantity, @JsonDeserialize(using=InventoryIntegerDeserializer.class) Long unitPrice, String location, String publicNote,
                            @JsonDeserialize(using=InventoryIntegerDeserializer.class) Long receivedQuantity, LocalDateTime receivedAt) {}
    public record ItemEdit(@JsonDeserialize(using=InventoryIntegerDeserializer.class) Long revision, String purchasedCode, String purchasedName, String color, String size,
                           String optionLabel, String location, String publicNote, @JsonDeserialize(using=InventoryIntegerDeserializer.class) Long orderedQuantity, @JsonDeserialize(using=InventoryIntegerDeserializer.class) Long unitPrice) {}
    public enum Kind { RECEIPT, CANCEL_PENDING, SALE_OUT, SUPPLIER_RETURN, CUSTOMER_RETURN, DISPOSE,
                       ADJUST_IN, ADJUST_OUT, REVERSE }
    public record MovementInput(@JsonDeserialize(using=InventoryIntegerDeserializer.class) Long revision, Kind kind, @JsonDeserialize(using=InventoryIntegerDeserializer.class) Long quantity, LocalDateTime occurredAt,
                                String reason, String referenceNumber, String referenceId, String location) {
        public MovementInput(Long revision,Kind kind,Long quantity,LocalDateTime occurredAt,String reason,String referenceNumber,String referenceId) {
            this(revision,kind,quantity,occurredAt,reason,referenceNumber,referenceId,null);
        }
    }
    public record RefundInput(@JsonDeserialize(using=InventoryIntegerDeserializer.class) Long revision, String status, @JsonDeserialize(using=InventoryIntegerDeserializer.class) Long amount, LocalDate refundedOn) {}
}
