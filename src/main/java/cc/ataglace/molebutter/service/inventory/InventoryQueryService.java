package cc.ataglace.molebutter.service.inventory;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import cc.ataglace.molebutter.exception.*;
import cc.ataglace.molebutter.service.common.*;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.dto.PageResponse;
import cc.ataglace.molebutter.dto.inventory.InventoryView;

@Service
@Transactional(readOnly=true)
public class InventoryQueryService extends InventorySupport {
    public InventoryQueryService(JdbcTemplate jdbc, BusinessAccess access, CatalogConsistencyGuard guard, BusinessTime time, ObjectMapper json) {
        super(jdbc,access,guard,time,json);
    }
    private static final String RECEIPT_TOTALS="""
        (SELECT m.item_id,SUM(CASE WHEN m.kind='RECEIPT' THEN m.quantity ELSE 0 END) received_quantity,
          SUM(CASE WHEN m.kind='CANCEL_PENDING' THEN m.quantity ELSE 0 END) cancelled_quantity
          FROM inventory_movement m WHERE m.kind IN ('RECEIPT','CANCEL_PENDING')
          AND NOT EXISTS(SELECT 1 FROM inventory_movement r WHERE r.reverses_id=m.id) GROUP BY m.item_id)
        """;
    private static final String ITEMS="""
        SELECT CAST(i.id AS CHAR) id,CAST(i.purchase_id AS CHAR) purchaseId,CAST(i.product_id AS CHAR) productId,
        CAST(i.origin_product_id AS CHAR) originProductId,CAST(i.supplier_id AS CHAR) supplierId,i.revision,
        p.product_code productCode,COALESCE(ss.image_url,p.image_url) imageUrl,i.color,i.size,i.option_label optionLabel,
        i.external_option_id externalOptionId,i.ordered_quantity orderedQuantity,i.on_hand onHand,i.pending,
        COALESCE(t.received_quantity,0) receivedQuantity,COALESCE(t.cancelled_quantity,0) cancelledQuantity,
        i.unit_price unitPrice,(CAST(i.unit_price AS DECIMAL(30,0))*i.on_hand) remainingAmount,
        i.public_note publicNote,
        (p.deleted_at IS NOT NULL) productDeleted,COALESCE(b.name,'') brand,
        DATE_FORMAT(o.purchased_on,'%%Y-%%m-%%d') purchasedOn,o.supplier_name supplierName,(o.deleted_at IS NOT NULL) purchaseDeleted
        FROM inventory_item i JOIN inventory_purchase o ON o.id=i.purchase_id
        JOIN catalog_product p ON p.id=i.product_id LEFT JOIN product_brand b ON b.id=p.brand_id
        LEFT JOIN product_supplier_selection sel ON sel.product_id=p.id LEFT JOIN product_supplier ss ON ss.id=sel.supplier_id
        LEFT JOIN %s t ON t.item_id=i.id
        """.formatted(RECEIPT_TOTALS);
    private static final String ORDERS="""
        SELECT CAST(o.id AS CHAR) id,o.revision,DATE_FORMAT(o.purchased_on,'%%Y-%%m-%%d') purchasedOn,
        o.supplier_name supplierName,o.order_number orderNumber,o.order_url orderUrl,
        CAST(o.payment_method_id AS CHAR) paymentMethodId,o.payment_amount paymentAmount,o.payment_method paymentMethod,o.payment_alias paymentAlias,DATE_FORMAT(o.paid_on,'%%Y-%%m-%%d') paidOn,
        CASE WHEN EXISTS(SELECT 1 FROM inventory_item i WHERE i.purchase_id=o.id AND i.unit_price IS NULL) THEN NULL
        ELSE CAST((SELECT COALESCE(SUM(CAST(i.ordered_quantity AS DECIMAL(30,0))*i.unit_price),0) FROM inventory_item i WHERE i.purchase_id=o.id) AS CHAR) END purchaseAmount,
        o.private_note privateNote,CAST(o.created_by AS CHAR) createdBy,DATE_FORMAT(o.deleted_at,'%%Y-%%m-%%dT%%H:%%i:%%s') deletedAt,
        (SELECT COUNT(*) FROM inventory_movement m JOIN inventory_item i ON i.id=m.item_id WHERE i.purchase_id=o.id AND m.refund_status='PENDING') pendingRefundCount,
        (SELECT COUNT(*) FROM inventory_item i WHERE i.purchase_id=o.id) itemCount,
        (SELECT COALESCE(SUM(i.ordered_quantity),0) FROM inventory_item i WHERE i.purchase_id=o.id) orderedQuantity,
        (SELECT COALESCE(SUM(t.received_quantity),0) FROM inventory_item i JOIN %s t ON t.item_id=i.id WHERE i.purchase_id=o.id) receivedQuantity,
        (SELECT COALESCE(SUM(t.cancelled_quantity),0) FROM inventory_item i JOIN %s t ON t.item_id=i.id WHERE i.purchase_id=o.id) cancelledQuantity,
        (SELECT COALESCE(SUM(i.on_hand),0) FROM inventory_item i WHERE i.purchase_id=o.id) onHand,
        (SELECT COALESCE(SUM(i.pending),0) FROM inventory_item i WHERE i.purchase_id=o.id) pending
        FROM inventory_purchase o
        """.formatted(RECEIPT_TOTALS,RECEIPT_TOTALS);
    private static final String MOVEMENTS="""
        SELECT CAST(m.id AS CHAR) id,CAST(m.item_id AS CHAR) itemId,m.kind,m.quantity,m.hand_delta handDelta,
        m.pending_delta pendingDelta,DATE_FORMAT(m.occurred_at,'%Y-%m-%dT%H:%i:%s') occurredAt,
        m.reason,m.reference_number referenceNumber,CAST(m.reference_id AS CHAR) referenceId,
        CAST(m.reverses_id AS CHAR) reversesId,CAST(m.actor_id AS CHAR) actorId,u.name actorName,
        EXISTS(SELECT 1 FROM inventory_movement r WHERE r.reverses_id=m.id) reversed,
        m.refund_status refundStatus,m.refund_amount refundAmount,m.refund_revision refundRevision,
        DATE_FORMAT(m.refunded_on,'%Y-%m-%d') refundedOn,p.product_code productCode,
        CAST(i.product_id AS CHAR) productId,CAST(i.purchase_id AS CHAR) purchaseId,i.color,i.size,i.option_label optionLabel,(o.deleted_at IS NOT NULL) purchaseDeleted
        FROM inventory_movement m JOIN inventory_item i ON i.id=m.item_id JOIN inventory_purchase o ON o.id=i.purchase_id JOIN `user` u ON u.id=m.actor_id JOIN catalog_product p ON p.id=i.product_id
        """;
    // A display group only: lot identities and their movement ledgers stay independent.
    private static String stockKey(String alias) {
        return "CAST(CASE WHEN TRIM("+alias+".product_code)='' THEN CONCAT('ID:',"+alias+".id) ELSE CONCAT('CODE:',UPPER(TRIM("+alias+".product_code))) END AS BINARY)";
    }
    private static final String STOCK_FROM="""
        FROM (SELECT %s stock_key,SUM(i.on_hand) onHand,SUM(i.pending) pending,COUNT(*) itemCount,
          CASE WHEN SUM(i.on_hand>0 AND i.unit_price IS NULL)>0 THEN NULL
          ELSE CAST(SUM(CAST(COALESCE(i.unit_price,0) AS DECIMAL(30,0))*i.on_hand) AS CHAR) END remainingAmount
          FROM inventory_item i JOIN inventory_purchase o ON o.id=i.purchase_id JOIN catalog_product p ON p.id=i.product_id
          WHERE o.deleted_at IS NULL GROUP BY stock_key) g
        JOIN (SELECT %s stock_key,COALESCE(MIN(CASE WHEN rp.deleted_at IS NULL THEN rp.id END),MIN(rp.id)) product_id
          FROM catalog_product rp WHERE rp.merged_into IS NULL GROUP BY stock_key) rep ON rep.stock_key=g.stock_key
        JOIN catalog_product p ON p.id=rep.product_id
        LEFT JOIN product_brand b ON b.id=p.brand_id
        LEFT JOIN product_supplier_selection sel ON sel.product_id=p.id LEFT JOIN product_supplier ss ON ss.id=sel.supplier_id
        """.formatted(stockKey("p"),stockKey("rp"));
    private static final String STOCK_SELECT="""
        SELECT CAST(p.id AS CHAR) productId,UPPER(TRIM(p.product_code)) productCode,COALESCE(b.name,'') brand,
          COALESCE(ss.image_url,p.image_url) imageUrl,(p.deleted_at IS NOT NULL) productDeleted,
          g.onHand,g.pending,g.itemCount,g.remainingAmount
        """;

    private PageResponse<Map<String,Object>> page(String select,String from,String where,List<Object> args,String order,int page,int size,boolean admin,InventoryView view) {
        paging(page,size);long total=jdbc.queryForObject("SELECT COUNT(*) "+from+where,Long.class,args.toArray());
        var params=new ArrayList<>(args);params.add(size);params.add((long)page*size);
        var rows=jdbc.queryForList(select+where+order+" LIMIT ? OFFSET ?",params.toArray()).stream().map(r->view.project(r,admin)).toList();
        return new PageResponse<>(rows,page,(int)((total+size-1)/size),total);
    }
    public PageResponse<Map<String,Object>> items(Long actor,String q,String productId,String state,int page,int size) {
        return items(actor,q,productId,"",state,page,size);
    }
    public PageResponse<Map<String,Object>> items(Long actor,String q,String productId,String groupProductId,String state,int page,int size) {
        boolean a=admin(actor);String term=text(q,255);Long product=id(productId);
        String where=" WHERE o.deleted_at IS NULL AND (?='' OR LOCATE(?,p.product_code)>0 OR LOCATE(?,p.search_query)>0 OR LOCATE(?,i.option_label)>0)";
        var args=new ArrayList<Object>(List.of(term,term,term,term));
        if(product!=null){where+=" AND i.product_id=?";args.add(product);}
        if(id(groupProductId)!=null){where+=" AND "+stockKey("p")+"=?";args.add(groupKey(id(groupProductId)));}
        if("ON_HAND".equals(state))where+=" AND i.on_hand>0";
        else if("PENDING".equals(state))where+=" AND i.pending>0";
        else if("EMPTY".equals(state))where+=" AND i.on_hand=0 AND i.pending=0";
        else if(!"ALL".equals(state))throw new InputValidationFailure("재고 상태를 확인해 주세요.");
        return page(ITEMS,"FROM inventory_item i JOIN inventory_purchase o ON o.id=i.purchase_id JOIN catalog_product p ON p.id=i.product_id",where,args," ORDER BY o.purchased_on,i.id",page,size,a,InventoryView.ITEM);
    }
    public Map<String,Object> item(Long actor,long item){boolean a=admin(actor);return InventoryView.ITEM.project(one(ITEMS+" WHERE i.id=?",item),a);}
    public PageResponse<Map<String,Object>> purchases(Long actor,String q,int page,int size) {return purchases(actor,q,"",page,size);}
    public PageResponse<Map<String,Object>> purchases(Long actor,String q,String brandId,int page,int size) {
        boolean a=admin(actor);String term=text(q,255);
        String where=" WHERE o.deleted_at IS NULL AND (?='' OR LOCATE(?,o.supplier_name)>0"+(a?" OR LOCATE(?,o.order_number)>0":"")+")";
        var args=new ArrayList<Object>(a?List.of(term,term,term):List.of(term,term));
        if(!brandId.isBlank())where+=" AND EXISTS(SELECT 1 FROM inventory_item bi JOIN catalog_product bp ON bp.id=bi.product_id WHERE bi.purchase_id=o.id"+brandCondition("bp",brandId,args)+")";
        return page(ORDERS,"FROM inventory_purchase o",where,args," ORDER BY o.purchased_on DESC,o.id DESC",page,size,a,InventoryView.PURCHASE);
    }
    public Map<String,Object> purchase(Long actor,long purchase) {
        boolean a=admin(actor);var result=InventoryView.PURCHASE.project(one(ORDERS+" WHERE o.id=?",purchase),a);
        result.put("items",jdbc.queryForList(ITEMS+" WHERE i.purchase_id=? ORDER BY i.id",purchase).stream().map(r->InventoryView.ITEM.project(r,a)).toList());
        if(a){result.put("deleteBlockedReason",deletionBlock(result));result.put("deleteToken",deletionToken(purchase,number(result,"revision")));}return result;
    }
    String deletionBlock(Map<String,Object> order) {
        if(order.get("deletedAt")!=null)return "이미 삭제된 구매 주문입니다.";
        if(number(order,"onHand")>0)return "보유 재고가 남아 있어 삭제할 수 없습니다. 해당 재고의 입출고 기록을 먼저 정리해 주세요.";
        if(number(order,"pendingRefundCount")>0)return "환불 대기 기록이 남아 있어 삭제할 수 없습니다. 환불 상태를 먼저 정리해 주세요.";
        return null;
    }
    String deletionToken(long order,long revision) {
        // Includes item/merge and refund revisions; the order metadata revision alone cannot detect these changes.
        return hash(List.of(revision,jdbc.queryForList("SELECT id,revision FROM inventory_item WHERE purchase_id=? ORDER BY id",order),
            jdbc.queryForList("SELECT m.id,m.refund_revision FROM inventory_movement m JOIN inventory_item i ON i.id=m.item_id WHERE i.purchase_id=? ORDER BY m.id",order)));
    }
    public PageResponse<Map<String,Object>> movements(Long actor,String itemId,String productId,int page,int size) {
        return movements(actor,itemId,productId,"",page,size);
    }
    public PageResponse<Map<String,Object>> movements(Long actor,String itemId,String productId,String groupProductId,int page,int size) {return movements(actor,itemId,productId,groupProductId,"",page,size);}
    public PageResponse<Map<String,Object>> movements(Long actor,String itemId,String productId,String groupProductId,String brandId,int page,int size) {
        boolean a=admin(actor);String where=" WHERE 1=1";var args=new ArrayList<Object>();
        if(id(itemId)!=null){where+=" AND m.item_id=?";args.add(id(itemId));}
        if(id(productId)!=null){where+=" AND i.product_id=?";args.add(id(productId));}
        if(id(groupProductId)!=null){where+=" AND o.deleted_at IS NULL AND i.product_id IN (SELECT gp.id FROM catalog_product gp WHERE "+stockKey("gp")+"=?)";args.add(groupKey(id(groupProductId)));}
        where+=brandCondition("p",brandId,args);
        return page(MOVEMENTS,"FROM inventory_movement m JOIN inventory_item i ON i.id=m.item_id JOIN inventory_purchase o ON o.id=i.purchase_id JOIN catalog_product p ON p.id=i.product_id JOIN `user` u ON u.id=m.actor_id",where,args," ORDER BY m.id DESC",page,size,a,InventoryView.MOVEMENT);
    }
    private byte[] groupKey(long product) {
        Set<Long> visited=new HashSet<>();
        while(visited.add(product)) {
            var row=one("SELECT merged_into,"+stockKey("p")+" stock_key FROM catalog_product p WHERE id=?",product);
            if(row.get("merged_into")==null)return (byte[])row.get("stock_key");
            product=number(row,"merged_into");
        }
        throw new OperationFailure("상품 통합 연결을 확인해 주세요.");
    }
    private Map<String,Object> visibleStock(Map<String,Object> row,boolean admin) {
        Object deleted=row.get("productDeleted");row.put("productDeleted",Boolean.TRUE.equals(deleted)||deleted instanceof Number n&&n.longValue()!=0);
        var result=InventoryView.STOCK.project(row,admin);
        if(row.containsKey("options"))result.put("options",row.get("options"));
        return result;
    }
    private record StockFilter(String where,List<Object> args) {}
    private String brandCondition(String alias,String brandId,List<Object> args) {
        if(brandId.isBlank())return "";
        if("UNASSIGNED".equals(brandId))return " AND "+alias+".brand_id IS NULL";
        args.add(id(brandId));return " AND "+alias+".brand_id=?";
    }
    private StockFilter stockFilter(String q,String productId,String brandId) {
        String term=text(q,255);
        String where=" WHERE (?='' OR LOCATE(?,p.product_code)>0 OR LOCATE(?,p.search_query)>0 OR LOCATE(?,COALESCE(b.name,''))>0 OR EXISTS (SELECT 1 FROM inventory_item si JOIN inventory_purchase so ON so.id=si.purchase_id JOIN catalog_product sp ON sp.id=si.product_id WHERE so.deleted_at IS NULL AND "+stockKey("sp")+"=g.stock_key AND LOCATE(?,sp.search_query)>0))";
        var args=new ArrayList<Object>(List.of(term,term,term,term,term));
        if(id(productId)!=null){where+=" AND g.stock_key=?";args.add(groupKey(id(productId)));}
        return new StockFilter(where+brandCondition("p",brandId,args),args);
    }
    public PageResponse<Map<String,Object>> stockProducts(Long actor,String q,String productId,int page,int size) {return stockProducts(actor,q,productId,"",page,size);}
    public PageResponse<Map<String,Object>> stockProducts(Long actor,String q,String productId,String brandId,int page,int size) {
        boolean a=admin(actor);paging(page,size);var filter=stockFilter(q,productId,brandId);
        long total=jdbc.queryForObject("SELECT COUNT(*) "+STOCK_FROM+filter.where(),Long.class,filter.args().toArray());
        var args=new ArrayList<Object>(filter.args());args.add(size);args.add((long)page*size);
        var rows=jdbc.queryForList(STOCK_SELECT+STOCK_FROM+filter.where()+" ORDER BY UPPER(TRIM(p.product_code)),p.id LIMIT ? OFFSET ?",args.toArray());
        return new PageResponse<>(rows.stream().map(r->visibleStock(r,a)).toList(),page,(int)((total+size-1)/size),total);
    }
    public Map<String,Object> stockTotals(Long actor,String q,String productId,String brandId) {
        admin(actor);var filter=stockFilter(q,productId,brandId);
        return InventoryView.TOTALS.project(one("SELECT (SELECT CAST(COALESCE(SUM(i.on_hand),0) AS CHAR) FROM inventory_item i JOIN inventory_purchase o ON o.id=i.purchase_id WHERE o.deleted_at IS NULL) totalOnHand,CAST(COALESCE(SUM(g.onHand),0) AS CHAR) filteredOnHand "+STOCK_FROM+filter.where(),filter.args().toArray()),false);
    }
    public Map<String,Object> stockProduct(Long actor,long product) {
        boolean a=admin(actor);byte[] key=groupKey(product);
        var row=one(STOCK_SELECT+STOCK_FROM+" WHERE g.stock_key=?",key);
        row.put("options",jdbc.queryForList("SELECT CAST(i.color AS BINARY) color_key,CAST(i.size AS BINARY) size_key,MIN(i.color) color,MIN(i.size) size,SUM(i.on_hand) onHand,SUM(i.pending) pending FROM inventory_item i JOIN inventory_purchase o ON o.id=i.purchase_id JOIN catalog_product p ON p.id=i.product_id WHERE o.deleted_at IS NULL AND "+stockKey("p")+"=? GROUP BY color_key,size_key ORDER BY color,size",key).stream().map(r->{return InventoryView.OPTION.project(r,false);}).toList());
        return visibleStock(row,a);
    }
    public List<Map<String,Object>> summaries(Long actor,List<String> productIds) {
        admin(actor);var ids=BusinessIds.parseList(productIds);if(ids.size()>100)throw new InputValidationFailure("한 번에 100개 상품까지 조회할 수 있습니다.");if(ids.isEmpty())return List.of();
        return jdbc.queryForList("SELECT CAST(i.product_id AS CHAR) productId,SUM(i.on_hand) onHand,SUM(i.pending) pending FROM inventory_item i JOIN inventory_purchase o ON o.id=i.purchase_id WHERE o.deleted_at IS NULL AND i.product_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") GROUP BY i.product_id",ids.toArray()).stream().map(row->InventoryView.SUMMARY.project(row,false)).toList();
    }

    Map<String,Object> rawPurchase(long order) { return one(ORDERS+" WHERE o.id=?",order); }
    Map<String,Object> movement(Long actor,long movement) { return InventoryView.MOVEMENT.project(one(MOVEMENTS+" WHERE m.id=?",movement),admin(actor)); }
}
