package cc.ataglace.molebutter.inventory.internal;
import cc.ataglace.molebutter.inventory.api.InventoryDtos.*;
import cc.ataglace.molebutter.inventory.internal.InventoryMovementService;
import cc.ataglace.molebutter.inventory.internal.InventoryQueryService;
import cc.ataglace.molebutter.inventory.internal.InventorySupport;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import cc.ataglace.molebutter.common.api.ErrorCode;
import cc.ataglace.molebutter.common.api.OperationFailure;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.common.api.BusinessException;
import cc.ataglace.molebutter.catalog.api.CatalogConsistencyGuard;
import cc.ataglace.molebutter.common.api.BusinessTime;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.common.api.BusinessIds;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;


@Service
@Transactional(readOnly=true)
public class InventoryPurchaseService extends InventorySupport {
    @org.springframework.beans.factory.annotation.Autowired private cc.ataglace.molebutter.inventory.api.SupplierReferencePort supplierReferences;
    private void supplier(Long product,Long supplier){if(supplier!=null)supplierReferences.validate(product,supplier);}

    private final InventoryQueryService queries;
    private final InventoryMovementService movements;
    public InventoryPurchaseService(JdbcTemplate jdbc, BusinessAccess access, CatalogConsistencyGuard guard, BusinessTime time, ObjectMapper json, InventoryQueryService queries, InventoryMovementService movements) {
        super(jdbc,access,guard,time,json);
        this.queries=queries; this.movements=movements;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> create(Long actor,String requestId,PurchaseInput input) {
        access.productActor(actor,true);guard.shared();String req=request(requestId),digest=hash(List.of(actor,input));requestLock(req);
        var existing=jdbc.queryForList("SELECT id,request_hash FROM inventory_purchase WHERE request_id=?",req);
        if(!existing.isEmpty()){if(!digest.equals(existing.getFirst().get("request_hash")))throw new OperationFailure("동일 요청 식별자의 내용이 다릅니다.");return queries.purchase(actor,number(existing.getFirst(),"id"));}
        required(input.purchasedOn(),"매입일을 입력해 주세요.");
        if(input.items()==null||input.items().isEmpty()||input.items().size()>100)throw new InputValidationFailure("주문에는 1~100개의 매입 상품이 필요합니다.");
        Long method=id(input.paymentMethodId());paymentAmount(input.paymentAmount());String payment=paymentName(method,input.paymentMethod(),null);
        long order=BusinessIds.next();var now=time.now();
        jdbc.update("INSERT INTO inventory_purchase(id,purchased_on,supplier_name,order_number,order_url,payment_method,payment_alias,paid_on,private_note,created_by,request_id,request_hash,created_at,updated_at,payment_method_id,payment_amount) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",order,input.purchasedOn(),text(input.supplierName(),255),text(input.orderNumber(),255),url(input.orderUrl()),payment,text(input.paymentAlias(),100),input.paidOn(),text(input.privateNote(),2000),actor,req,digest,now,now,method,input.paymentAmount());
        for(var x:input.items()) {
            required(x,"매입 상품을 확인해 주세요.");Long product=id(x.productId()),source=id(x.supplierId());activeProduct(product);supplier(product,source);long qty=quantity(x.orderedQuantity());price(x.unitPrice());
            long item=BusinessIds.next();
            jdbc.update("INSERT INTO inventory_item(id,purchase_id,product_id,origin_product_id,supplier_id,color,size,option_label,external_option_id,original_ordered_quantity,ordered_quantity,pending,unit_price,public_note,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",item,order,product,product,source,text(x.color(),100),text(x.size(),100),text(x.optionLabel(),500),text(x.externalOptionId(),200),qty,qty,qty,x.unitPrice(),text(x.publicNote(),2000),now,now);
            if((x.receivedQuantity()!=null&&x.receivedQuantity()!=0)||x.receivedAt()!=null)
                throw new InputValidationFailure("주문 저장 후 구매 주문에서 입고를 기록해 주세요.");
        }
        return queries.purchase(actor,order);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> editPurchase(Long actor,long order,PurchaseEdit x) {
        access.productActor(actor,true);guard.shared();var current=one("SELECT * FROM inventory_purchase WHERE id=? FOR UPDATE",order);activePurchase(current);version(current,x.revision());
        Long method=id(x.paymentMethodId());paymentAmount(x.paymentAmount());String payment=paymentName(method,x.paymentMethod(),current);
        jdbc.update("UPDATE inventory_purchase SET purchased_on=?,supplier_name=?,order_number=?,order_url=?,payment_method=?,payment_alias=?,paid_on=?,private_note=?,payment_method_id=?,payment_amount=?,revision=revision+1,updated_at=? WHERE id=?",required(x.purchasedOn(),"매입일을 입력해 주세요."),text(x.supplierName(),255),text(x.orderNumber(),255),url(x.orderUrl()),payment,x.paymentAlias()==null?current.get("payment_alias"):text(x.paymentAlias(),100),x.paidOn(),text(x.privateNote(),2000),method,x.paymentAmount(),time.now(),order);
        return queries.purchase(actor,order);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> deletePurchase(Long actor,long order,String requestId,PurchaseDelete x) {
        access.productActor(actor,true);guard.shared();String req=request(requestId),digest=hash(List.of(actor,order,x));requestLock(req);
        var current=one("SELECT * FROM inventory_purchase WHERE id=? FOR UPDATE",order);
        if(current.get("deleted_at")!=null){
            if(req.equals(current.get("delete_request_id"))&&digest.equals(current.get("delete_request_hash")))return queries.purchase(actor,order);
            throw new OperationFailure("이미 삭제된 구매 주문입니다. 목록을 다시 조회해 주세요.");
        }
        if(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_purchase WHERE delete_request_id=?",Long.class,req)>0)throw new OperationFailure("동일 요청 식별자의 내용이 다릅니다.");
        version(current,x.revision());
        if(!queries.deletionToken(order,number(current,"revision")).equals(x.deleteToken()))throw new OperationFailure("주문 항목 또는 입출고·환불 기록이 변경되었습니다. 최신 주문을 확인한 뒤 다시 삭제해 주세요.");
        String blocked=queries.deletionBlock(queries.rawPurchase(order));if(blocked!=null)throw new OperationFailure(blocked);
        for(var row:jdbc.queryForList("SELECT * FROM inventory_item WHERE purchase_id=? ORDER BY id FOR UPDATE",order)) {
            long pending=number(row,"pending");
            if(pending>0)movements.apply(actor,row,UUID.randomUUID().toString(),"",new MovementInput(number(row,"revision"),Kind.CANCEL_PENDING,pending,time.now(),"구매 주문 삭제로 미입고 취소","",null));
        }
        jdbc.update("UPDATE inventory_purchase SET deleted_at=?,deleted_by=?,delete_request_id=?,delete_request_hash=?,revision=revision+1,updated_at=? WHERE id=?",time.now(),actor,req,digest,time.now(),order);
        return queries.purchase(actor,order);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> editItem(Long actor,long item,ItemEdit x) {
        boolean a=admin(actor);guard.shared();var r=rawItem(item);version(r,x.revision());
        if(!a&&(x.unitPrice()!=null||x.orderedQuantity()!=null))throw new BusinessException(ErrorCode.HANDLE_ACCESS_DENIED);
        long ordered=a?quantity(x.orderedQuantity()):number(r,"ordered_quantity");long difference=ordered-number(r,"ordered_quantity"),pending=number(r,"pending")+difference;
        if(pending<0)throw new OperationFailure("입고·취소 처리한 수량 아래로 주문 수량을 줄일 수 없습니다.");
        if(difference>0)activeItemProduct(r);
        if(a){price(x.unitPrice());jdbc.update("UPDATE inventory_item SET ordered_quantity=?,pending=?,unit_price=? WHERE id=?",ordered,pending,x.unitPrice(),item);}
        jdbc.update("UPDATE inventory_item SET color=?,size=?,option_label=?,public_note=?,revision=revision+1,updated_at=? WHERE id=?",text(x.color(),100),text(x.size(),100),x.optionLabel()==null?r.get("option_label"):text(x.optionLabel(),500),text(x.publicNote(),2000),time.now(),item);
        if(difference!=0)movements.record(actor,item,UUID.randomUUID().toString(),"",difference>0?"ORDER_INCREASE":"ORDER_DECREASE",Math.abs(difference),0,difference,time.now(),"주문 수량 변경","",null,null);
        return queries.item(actor,item);
    }
}
