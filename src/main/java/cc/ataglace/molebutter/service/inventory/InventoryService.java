package cc.ataglace.molebutter.service.inventory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.dto.PageResponse;
import cc.ataglace.molebutter.dto.inventory.InventoryDtos.*;
import cc.ataglace.molebutter.exception.*;
import cc.ataglace.molebutter.service.product.ProductStore;
import cc.ataglace.molebutter.service.product.ProductTime;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class InventoryService {
    private final JdbcTemplate jdbc;
    private final ProductStore products;
    private final ProductTime time;
    private final ObjectMapper json;
    private static final long MAX_QUANTITY=1_000_000;
    private static final Set<String> PRIVATE=Set.of("unitPrice","remainingAmount","orderNumber","orderUrl","paymentMethod","paymentMethodId","paymentAmount","purchaseAmount","paymentAlias","paidOn","privateNote","refundStatus","refundAmount","refundedOn","refundRevision","pendingRefundCount","deleteToken","deleteBlockedReason");
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

    public boolean admin(Long actor) {
        products.authorize(actor,false);
        return "ADMIN".equals(jdbc.queryForObject("SELECT user_role FROM `user` WHERE id=?",String.class,actor));
    }
    private Map<String,Object> visible(Map<String,Object> row,boolean admin) {
        Map<String,Object> result=new LinkedHashMap<>(row);
        if(!admin)PRIVATE.forEach(result::remove);
        return result;
    }
    private <T> T required(T value,String message) {if(value==null)throw new IllegalArgumentException(message);return value;}
    private static String text(String value,int max) {
        String s=value==null?"":value.trim();if(s.length()>max)throw new IllegalArgumentException("입력 길이를 확인해 주세요.");return s;
    }
    private static Long id(String value) {
        if(value==null||value.isBlank())return null;
        try {long x=Long.parseLong(value);if(x<=0)throw new NumberFormatException();return x;}
        catch(NumberFormatException ex){throw new IllegalArgumentException("ID가 올바르지 않습니다.");}
    }
    private long quantity(Long value) {if(value==null||value<1||value>MAX_QUANTITY)throw new IllegalArgumentException("수량은 1~1,000,000의 정수로 입력해 주세요.");return value;}
    private void price(Long value){if(value!=null&&(value<0||value>1_000_000_000))throw new IllegalArgumentException("단가는 0~1,000,000,000원으로 입력해 주세요.");}
    private void paymentAmount(Long value) {if(value!=null&&(value<0||value>1_000_000_000_000_000L))throw new IllegalArgumentException("결제 금액은 0~1,000,000,000,000,000원의 정수로 입력해 주세요.");}
    private String paymentName(Long method,String legacy,Map<String,Object> current) {
        if(method==null)return text(legacy,100);
        if(current!=null&&Objects.equals(method,current.get("payment_method_id")))return current.get("payment_method").toString();
        var rows=jdbc.queryForList("SELECT name FROM inventory_payment_method WHERE id=? AND deleted_at IS NULL",method);
        if(rows.isEmpty())throw new IllegalArgumentException("사용 가능한 결제 수단을 선택해 주세요.");
        return rows.getFirst().get("name").toString();
    }
    private String url(String value) {
        String s=text(value,2000);if(s.isEmpty())return s;
        try {URI u=URI.create(s);if(!Set.of("http","https").contains(Objects.toString(u.getScheme(),"").toLowerCase(Locale.ROOT))||u.getHost()==null||u.getUserInfo()!=null)throw new IllegalArgumentException();}
        catch(IllegalArgumentException ex){throw new IllegalArgumentException("주문서 URL은 http/https 주소로 입력해 주세요.");}return s;
    }
    private String request(String value) {
        try {String v=UUID.fromString(value).toString();if(!v.equalsIgnoreCase(value))throw new IllegalArgumentException();return v;}
        catch(Exception ex){throw new IllegalArgumentException("요청 식별자가 필요합니다.");}
    }
    private String hash(Object value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception ex){throw new IllegalStateException(ex);}
    }
    private Map<String,Object> one(String query,Object... args) {
        var rows=jdbc.queryForList(query,args);if(rows.isEmpty())throw new BusinessException(ErrorCode.NOT_FOUND);return rows.getFirst();
    }
    private long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private void version(Map<String,Object> row,Long revision){ProductStore.revision(number(row,"revision"),revision);}
    private Map<String,Object> rawItem(long item){var row=one("SELECT * FROM inventory_item WHERE id=? FOR UPDATE",item);activePurchase(one("SELECT deleted_at FROM inventory_purchase WHERE id=?",row.get("purchase_id")));return row;}
    private void activePurchase(Map<String,Object> row){if(row.get("deleted_at")!=null)throw new OperationFailure("삭제된 구매 주문은 변경할 수 없습니다. 이력만 조회할 수 있습니다.");}
    private void activeProduct(Long product) {
        required(product,"연결 상품을 선택해 주세요.");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product WHERE id=? AND merged_into IS NULL AND deleted_at IS NULL",Long.class,product)==0)throw new IllegalArgumentException("현재 관리 중인 상품을 선택해 주세요.");
    }
    private void activeItemProduct(Map<String,Object> row){activeProduct(number(row,"product_id"));}
    private void supplier(Long product,Long supplier) {
        if(supplier!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM product_supplier WHERE id=? AND product_id=?",Long.class,supplier,product)==0)throw new IllegalArgumentException("연결 상품의 매입처 판매글을 선택해 주세요.");
    }
    private void paging(int page,int size){if(page<0||!List.of(20,50,100).contains(size))throw new IllegalArgumentException("페이지와 표시 개수(20·50·100)를 확인해 주세요.");}
    private PageResponse<Map<String,Object>> page(String select,String from,String where,List<Object> args,String order,int page,int size,boolean admin) {
        paging(page,size);long total=jdbc.queryForObject("SELECT COUNT(*) "+from+where,Long.class,args.toArray());
        var params=new ArrayList<>(args);params.add(size);params.add((long)page*size);
        var rows=jdbc.queryForList(select+where+order+" LIMIT ? OFFSET ?",params.toArray()).stream().map(r->visible(r,admin)).toList();
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
        else if(!"ALL".equals(state))throw new IllegalArgumentException("재고 상태를 확인해 주세요.");
        return page(ITEMS,"FROM inventory_item i JOIN inventory_purchase o ON o.id=i.purchase_id JOIN catalog_product p ON p.id=i.product_id",where,args," ORDER BY o.purchased_on,i.id",page,size,a);
    }
    public Map<String,Object> item(Long actor,long item){boolean a=admin(actor);return visible(one(ITEMS+" WHERE i.id=?",item),a);}
    public PageResponse<Map<String,Object>> purchases(Long actor,String q,int page,int size) {return purchases(actor,q,"",page,size);}
    public PageResponse<Map<String,Object>> purchases(Long actor,String q,String brandId,int page,int size) {
        boolean a=admin(actor);String term=text(q,255);
        String where=" WHERE o.deleted_at IS NULL AND (?='' OR LOCATE(?,o.supplier_name)>0"+(a?" OR LOCATE(?,o.order_number)>0":"")+")";
        var args=new ArrayList<Object>(a?List.of(term,term,term):List.of(term,term));
        if(!brandId.isBlank())where+=" AND EXISTS(SELECT 1 FROM inventory_item bi JOIN catalog_product bp ON bp.id=bi.product_id WHERE bi.purchase_id=o.id"+brandCondition("bp",brandId,args)+")";
        return page(ORDERS,"FROM inventory_purchase o",where,args," ORDER BY o.purchased_on DESC,o.id DESC",page,size,a);
    }
    public Map<String,Object> purchase(Long actor,long purchase) {
        boolean a=admin(actor);var result=visible(one(ORDERS+" WHERE o.id=?",purchase),a);
        result.put("items",jdbc.queryForList(ITEMS+" WHERE i.purchase_id=? ORDER BY i.id",purchase).stream().map(r->visible(r,a)).toList());
        if(a){result.put("deleteBlockedReason",deletionBlock(result));result.put("deleteToken",deletionToken(purchase,number(result,"revision")));}return result;
    }
    private String deletionBlock(Map<String,Object> order) {
        if(order.get("deletedAt")!=null)return "이미 삭제된 구매 주문입니다.";
        if(number(order,"onHand")>0)return "보유 재고가 남아 있어 삭제할 수 없습니다. 해당 재고의 입출고 기록을 먼저 정리해 주세요.";
        if(number(order,"pendingRefundCount")>0)return "환불 대기 기록이 남아 있어 삭제할 수 없습니다. 환불 상태를 먼저 정리해 주세요.";
        return null;
    }
    private String deletionToken(long order,long revision) {
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
        return page(MOVEMENTS,"FROM inventory_movement m JOIN inventory_item i ON i.id=m.item_id JOIN inventory_purchase o ON o.id=i.purchase_id JOIN catalog_product p ON p.id=i.product_id JOIN `user` u ON u.id=m.actor_id",where,args," ORDER BY m.id DESC",page,size,a);
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
        return visible(row,admin);
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
        return one("SELECT (SELECT CAST(COALESCE(SUM(i.on_hand),0) AS CHAR) FROM inventory_item i JOIN inventory_purchase o ON o.id=i.purchase_id WHERE o.deleted_at IS NULL) totalOnHand,CAST(COALESCE(SUM(g.onHand),0) AS CHAR) filteredOnHand "+STOCK_FROM+filter.where(),filter.args().toArray());
    }
    public Map<String,Object> stockProduct(Long actor,long product) {
        boolean a=admin(actor);byte[] key=groupKey(product);
        var row=one(STOCK_SELECT+STOCK_FROM+" WHERE g.stock_key=?",key);
        row.put("options",jdbc.queryForList("SELECT CAST(i.color AS BINARY) color_key,CAST(i.size AS BINARY) size_key,MIN(i.color) color,MIN(i.size) size,SUM(i.on_hand) onHand,SUM(i.pending) pending FROM inventory_item i JOIN inventory_purchase o ON o.id=i.purchase_id JOIN catalog_product p ON p.id=i.product_id WHERE o.deleted_at IS NULL AND "+stockKey("p")+"=? GROUP BY color_key,size_key ORDER BY color,size",key).stream().map(r->{r.remove("color_key");r.remove("size_key");return r;}).toList());
        return visibleStock(row,a);
    }
    public List<Map<String,Object>> summaries(Long actor,List<String> productIds) {
        admin(actor);var ids=ProductStore.ids(productIds);if(ids.size()>100)throw new IllegalArgumentException("한 번에 100개 상품까지 조회할 수 있습니다.");if(ids.isEmpty())return List.of();
        return jdbc.queryForList("SELECT CAST(i.product_id AS CHAR) productId,SUM(i.on_hand) onHand,SUM(i.pending) pending FROM inventory_item i JOIN inventory_purchase o ON o.id=i.purchase_id WHERE o.deleted_at IS NULL AND i.product_id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+") GROUP BY i.product_id",ids.toArray());
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> create(Long actor,String requestId,PurchaseInput input) {
        products.authorize(actor,true);products.lock();String req=request(requestId),digest=hash(List.of(actor,input));
        var existing=jdbc.queryForList("SELECT id,request_hash FROM inventory_purchase WHERE request_id=?",req);
        if(!existing.isEmpty()){if(!digest.equals(existing.getFirst().get("request_hash")))throw new OperationFailure("동일 요청 식별자의 내용이 다릅니다.");return purchase(actor,number(existing.getFirst(),"id"));}
        required(input.purchasedOn(),"매입일을 입력해 주세요.");
        if(input.items()==null||input.items().isEmpty()||input.items().size()>100)throw new IllegalArgumentException("주문에는 1~100개의 매입 상품이 필요합니다.");
        Long method=id(input.paymentMethodId());paymentAmount(input.paymentAmount());String payment=paymentName(method,input.paymentMethod(),null);
        long order=ProductStore.id();var now=time.now();
        jdbc.update("INSERT INTO inventory_purchase(id,purchased_on,supplier_name,order_number,order_url,payment_method,payment_alias,paid_on,private_note,created_by,request_id,request_hash,created_at,updated_at,payment_method_id,payment_amount) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",order,input.purchasedOn(),text(input.supplierName(),255),text(input.orderNumber(),255),url(input.orderUrl()),payment,text(input.paymentAlias(),100),input.paidOn(),text(input.privateNote(),2000),actor,req,digest,now,now,method,input.paymentAmount());
        for(var x:input.items()) {
            required(x,"매입 상품을 확인해 주세요.");Long product=id(x.productId()),source=id(x.supplierId());activeProduct(product);supplier(product,source);long qty=quantity(x.orderedQuantity());price(x.unitPrice());
            long item=ProductStore.id();
            jdbc.update("INSERT INTO inventory_item(id,purchase_id,product_id,origin_product_id,supplier_id,color,size,option_label,external_option_id,original_ordered_quantity,ordered_quantity,pending,unit_price,public_note,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",item,order,product,product,source,text(x.color(),100),text(x.size(),100),text(x.optionLabel(),500),text(x.externalOptionId(),200),qty,qty,qty,x.unitPrice(),text(x.publicNote(),2000),now,now);
            if((x.receivedQuantity()!=null&&x.receivedQuantity()!=0)||x.receivedAt()!=null)
                throw new IllegalArgumentException("주문 저장 후 구매 주문에서 입고를 기록해 주세요.");
        }
        return purchase(actor,order);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> editPurchase(Long actor,long order,PurchaseEdit x) {
        products.authorize(actor,true);products.lock();var current=one("SELECT * FROM inventory_purchase WHERE id=? FOR UPDATE",order);activePurchase(current);version(current,x.revision());
        Long method=id(x.paymentMethodId());paymentAmount(x.paymentAmount());String payment=paymentName(method,x.paymentMethod(),current);
        jdbc.update("UPDATE inventory_purchase SET purchased_on=?,supplier_name=?,order_number=?,order_url=?,payment_method=?,payment_alias=?,paid_on=?,private_note=?,payment_method_id=?,payment_amount=?,revision=revision+1,updated_at=? WHERE id=?",required(x.purchasedOn(),"매입일을 입력해 주세요."),text(x.supplierName(),255),text(x.orderNumber(),255),url(x.orderUrl()),payment,x.paymentAlias()==null?current.get("payment_alias"):text(x.paymentAlias(),100),x.paidOn(),text(x.privateNote(),2000),method,x.paymentAmount(),time.now(),order);
        return purchase(actor,order);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> deletePurchase(Long actor,long order,String requestId,PurchaseDelete x) {
        products.authorize(actor,true);products.lock();String req=request(requestId),digest=hash(List.of(actor,order,x));
        var current=one("SELECT * FROM inventory_purchase WHERE id=? FOR UPDATE",order);
        if(current.get("deleted_at")!=null){
            if(req.equals(current.get("delete_request_id"))&&digest.equals(current.get("delete_request_hash")))return purchase(actor,order);
            throw new OperationFailure("이미 삭제된 구매 주문입니다. 목록을 다시 조회해 주세요.");
        }
        if(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_purchase WHERE delete_request_id=?",Long.class,req)>0)throw new OperationFailure("동일 요청 식별자의 내용이 다릅니다.");
        version(current,x.revision());
        if(!deletionToken(order,number(current,"revision")).equals(x.deleteToken()))throw new OperationFailure("주문 항목 또는 입출고·환불 기록이 변경되었습니다. 최신 주문을 확인한 뒤 다시 삭제해 주세요.");
        String blocked=deletionBlock(one(ORDERS+" WHERE o.id=?",order));if(blocked!=null)throw new OperationFailure(blocked);
        for(var row:jdbc.queryForList("SELECT * FROM inventory_item WHERE purchase_id=? ORDER BY id FOR UPDATE",order)) {
            long pending=number(row,"pending");
            if(pending>0)apply(actor,row,UUID.randomUUID().toString(),"",new MovementInput(number(row,"revision"),Kind.CANCEL_PENDING,pending,time.now(),"구매 주문 삭제로 미입고 취소","",null));
        }
        jdbc.update("UPDATE inventory_purchase SET deleted_at=?,deleted_by=?,delete_request_id=?,delete_request_hash=?,revision=revision+1,updated_at=? WHERE id=?",time.now(),actor,req,digest,time.now(),order);
        return purchase(actor,order);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> editItem(Long actor,long item,ItemEdit x) {
        boolean a=admin(actor);products.lock();var r=rawItem(item);version(r,x.revision());
        if(!a&&(x.unitPrice()!=null||x.orderedQuantity()!=null))throw new BusinessException(ErrorCode.HANDLE_ACCESS_DENIED);
        long ordered=a?quantity(x.orderedQuantity()):number(r,"ordered_quantity");long difference=ordered-number(r,"ordered_quantity"),pending=number(r,"pending")+difference;
        if(pending<0)throw new OperationFailure("입고·취소 처리한 수량 아래로 주문 수량을 줄일 수 없습니다.");
        if(difference>0)activeItemProduct(r);
        if(a){price(x.unitPrice());jdbc.update("UPDATE inventory_item SET ordered_quantity=?,pending=?,unit_price=? WHERE id=?",ordered,pending,x.unitPrice(),item);}
        jdbc.update("UPDATE inventory_item SET color=?,size=?,option_label=?,public_note=?,revision=revision+1,updated_at=? WHERE id=?",text(x.color(),100),text(x.size(),100),x.optionLabel()==null?r.get("option_label"):text(x.optionLabel(),500),text(x.publicNote(),2000),time.now(),item);
        if(difference!=0)record(actor,item,UUID.randomUUID().toString(),"",difference>0?"ORDER_INCREASE":"ORDER_DECREASE",Math.abs(difference),0,difference,time.now(),"주문 수량 변경","",null,null);
        return item(actor,item);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> move(Long actor,long item,String requestId,MovementInput input) {
        boolean a=admin(actor);products.lock();String req=request(requestId),digest=movementHash(actor,item,input);
        var exists=jdbc.queryForList("SELECT * FROM inventory_movement WHERE request_id=?",req);
        if(!exists.isEmpty()){if(!digest.equals(exists.getFirst().get("request_hash")))throw new OperationFailure("동일 요청 식별자의 내용이 다릅니다.");return item(actor,item);}
        if(input.kind()==Kind.CANCEL_PENDING&&!a)throw new BusinessException(ErrorCode.HANDLE_ACCESS_DENIED);
        var row=rawItem(item);version(row,input.revision());apply(actor,row,req,digest,input);return item(actor,item);
    }
    private String movementHash(Long actor,long item,MovementInput input) {
        // Retired location is used only in the original fingerprint so historical requests can be retried.
        var fields=new LinkedHashMap<String,Object>();fields.put("revision",input.revision());fields.put("kind",input.kind());fields.put("quantity",input.quantity());fields.put("occurredAt",input.occurredAt());fields.put("reason",input.reason());fields.put("referenceNumber",input.referenceNumber());fields.put("referenceId",input.referenceId());
        if(input.location()!=null)fields.put("location",input.location());
        return hash(List.of(actor,item,fields));
    }
    private void apply(Long actor,Map<String,Object> item,String req,String digest,MovementInput x) {
        Kind kind=required(x.kind(),"입출고 유형을 선택해 주세요.");long qty=quantity(x.quantity()),itemId=number(item,"id");
        required(x.occurredAt(),"발생일을 입력해 주세요.");String reason=text(x.reason(),1000);
        if(Set.of(Kind.ADJUST_IN,Kind.ADJUST_OUT,Kind.REVERSE,Kind.DISPOSE).contains(kind)&&reason.isBlank())throw new IllegalArgumentException("정정·취소·폐기 사유를 입력해 주세요.");
        long dh=0,dp=0;Long reference=id(x.referenceId()),reverses=null;
        switch(kind) {
            case RECEIPT -> {dh=qty;dp=-qty;}
            case CANCEL_PENDING -> dp=-qty;
            case SALE_OUT,SUPPLIER_RETURN,DISPOSE,ADJUST_OUT -> dh=-qty;
            case ADJUST_IN -> dh=qty;
            case CUSTOMER_RETURN -> {
                required(reference,"원래 판매 출고를 선택해 주세요.");var origin=one("SELECT * FROM inventory_movement WHERE id=? AND item_id=?",reference,itemId);
                if(!"SALE_OUT".equals(origin.get("kind"))||isReversed(reference))throw new OperationFailure("유효한 판매 출고를 선택해 주세요.");
                long returned=returned(reference);
                if(qty>number(origin,"quantity")-returned)throw new OperationFailure("원출고의 남은 반품 가능 수량을 초과했습니다.");dh=qty;
            }
            case REVERSE -> {
                required(reference,"취소할 원래 기록을 선택해 주세요.");var origin=one("SELECT * FROM inventory_movement WHERE id=? AND item_id=?",reference,itemId);
                if(isReversed(reference)||Set.of("REVERSE","ORDER_INCREASE","ORDER_DECREASE").contains(origin.get("kind")))throw new OperationFailure("취소할 수 없는 기록입니다.");
                if("CANCEL_PENDING".equals(origin.get("kind")))products.authorize(actor,true);
                if("SALE_OUT".equals(origin.get("kind"))&&returned(reference)>0)throw new OperationFailure("연결된 고객 반품을 먼저 취소해 주세요.");
                if(!"NONE".equals(origin.get("refund_status")))throw new OperationFailure("환불 기록을 먼저 해제해 주세요.");
                if(qty!=number(origin,"quantity"))throw new IllegalArgumentException("기록 취소는 원래 수량 전체로 처리합니다.");
                dh=-number(origin,"hand_delta");dp=-number(origin,"pending_delta");reverses=reference;
            }
        }
        if(kind!=Kind.CUSTOMER_RETURN&&kind!=Kind.REVERSE&&reference!=null)throw new IllegalArgumentException("이 유형에는 원래 기록을 연결할 수 없습니다.");
        long hand=number(item,"on_hand")+dh,pending=number(item,"pending")+dp;
        if(hand<0||hand>MAX_QUANTITY||pending<0||pending>number(item,"ordered_quantity"))throw new OperationFailure("보유·미입고 수량 범위를 벗어납니다. 관련 기록과 수량을 확인해 주세요.");
        if(dh>0||dp>0)activeItemProduct(item);
        record(actor,itemId,req,digest,kind.name(),qty,dh,dp,x.occurredAt(),reason,text(x.referenceNumber(),255),reference,reverses);
        jdbc.update("UPDATE inventory_item SET on_hand=?,pending=?,revision=revision+1,updated_at=? WHERE id=?",hand,pending,time.now(),itemId);
    }
    private boolean isReversed(long movement){return jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE reverses_id=?",Long.class,movement)>0;}
    private long returned(long sale){return jdbc.queryForObject("SELECT COALESCE(SUM(m.quantity),0) FROM inventory_movement m WHERE m.reference_id=? AND m.kind='CUSTOMER_RETURN' AND NOT EXISTS(SELECT 1 FROM inventory_movement r WHERE r.reverses_id=m.id)",Long.class,sale);}
    private void record(Long actor,long item,String req,String digest,String kind,long qty,long dh,long dp,LocalDateTime at,String reason,String refNumber,Long reference,Long reverses) {
        jdbc.update("INSERT INTO inventory_movement(id,item_id,kind,quantity,hand_delta,pending_delta,occurred_at,reason,reference_number,reference_id,reverses_id,actor_id,request_id,request_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",ProductStore.id(),item,kind,qty,dh,dp,at,reason,refNumber,reference,reverses,actor,req,digest,time.now());
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public Map<String,Object> refund(Long actor,long movement,RefundInput x) {
        products.authorize(actor,true);products.lock();var r=one("SELECT * FROM inventory_movement WHERE id=? FOR UPDATE",movement);
        rawItem(number(r,"item_id"));
        ProductStore.revision(number(r,"refund_revision"),x.revision());
        if(!"SUPPLIER_RETURN".equals(r.get("kind"))||isReversed(movement))throw new OperationFailure("유효한 매입 반품 기록에서 환불을 관리해 주세요.");
        if(!Set.of("NONE","PENDING","COMPLETED").contains(Objects.toString(x.status(),"")))throw new IllegalArgumentException("환불 상태를 확인해 주세요.");
        if(x.amount()!=null&&(x.amount()<0||x.amount()>1_000_000_000_000_000L))throw new IllegalArgumentException("환불액을 확인해 주세요.");
        if("COMPLETED".equals(x.status())){required(x.amount(),"환불액을 입력해 주세요.");required(x.refundedOn(),"환불일을 입력해 주세요.");}
        if(!"COMPLETED".equals(x.status())&&x.refundedOn()!=null)throw new IllegalArgumentException("환불 완료 시에만 환불일을 입력합니다.");
        jdbc.update("UPDATE inventory_movement SET refund_status=?,refund_amount=?,refunded_on=?,refund_revision=refund_revision+1 WHERE id=?",x.status(),"NONE".equals(x.status())?null:x.amount(),x.refundedOn(),movement);
        return visible(one(MOVEMENTS+" WHERE m.id=?",movement),true);
    }
}
