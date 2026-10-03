package cc.ataglace.molebutter;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import cc.ataglace.molebutter.domain.*;
import cc.ataglace.molebutter.repository.UserRepository;
import cc.ataglace.molebutter.service.auth.AuthTokenService;
import cc.ataglace.molebutter.service.inventory.InventoryService;
import cc.ataglace.molebutter.service.product.*;
import cc.ataglace.molebutter.dto.inventory.InventoryDtos.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "product.refresh.worker-enabled=false", "spring.config.import=classpath:bootstrap-admin-test.properties",
    "spring.datasource.username=test_app", "spring.datasource.password=isolated-test-app-password",
    "spring.flyway.user=test_migrator", "spring.flyway.password=isolated-test-migration-password",
    "spring.data.redis.host=127.0.0.1", "spring.data.redis.password=", "mail.provider=test",
    "auth.jwt.secret=isolated-integration-test-secret-at-least-32-bytes", "auth.cookie.secure=false"
})
@ActiveProfiles("bootstrap-admin")
@Import(AuthenticationFlowIT.MailConfiguration.class)
class InventoryFlowIT {
    @DynamicPropertySource static void databases(DynamicPropertyRegistry r){AuthenticationFlowIT.databases(r);}
    @Autowired cc.ataglace.molebutter.service.inventory.InventoryPaymentMethodService payments; @Autowired InventoryService inventory; @Autowired ProductService products; @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users; @Autowired ObjectMapper json; @Autowired AuthTokenService tokens; @LocalServerPort int port;
    long admin,staff,viewer;String product;
    final LocalDate date=LocalDate.of(2026,10,1);final LocalDateTime at=date.atTime(10,0);
    @BeforeEach void prepare(){
        clearInventory();admin=users.findByEmail("admin@example.com").orElseThrow().getId();
        staff=account(UserRole.PRODUCT);viewer=account(UserRole.VIEWER);
        product=products.create(admin,new CatalogEdit(null,"","INV-"+UUID.randomUUID(),"구매 상품 검색어")).id();
    }
    @AfterEach void cleanup(){clearInventory();}
    void clearInventory(){jdbc.update("UPDATE inventory_movement SET reference_id=NULL,reverses_id=NULL");jdbc.update("DELETE FROM inventory_movement");jdbc.update("DELETE FROM inventory_item");jdbc.update("DELETE FROM inventory_purchase");}
    long account(UserRole role){return users.saveAndFlush(User.builder().email(UUID.randomUUID()+"@inventory.test").name("재고 담당자").passwordHash("unused").role(role).status(UserStatus.ACTIVE).build()).getId();}
    ItemInput line(String p,long ordered,Long price,long received){return new ItemInput(p,null,"","","블랙","FREE","실제 옵션","ref-option",ordered,price,"선반 A","공개 메모",received,received>0?at:null);}
    PurchaseInput order(ItemInput... lines){return new PurchaseInput(date,"실제 구매처","private-order","https://orders.test/private","카드","private-card",date,"private-note",List.of(lines),null,null);}
    String request(){return UUID.randomUUID().toString();}
    Map<String,Object> create(ItemInput... lines){
        var pending=Arrays.stream(lines).map(x->new ItemInput(x.productId(),x.supplierId(),x.purchasedCode(),x.purchasedName(),x.color(),x.size(),x.optionLabel(),x.externalOptionId(),x.orderedQuantity(),x.unitPrice(),x.location(),x.publicNote(),0L,null)).toArray(ItemInput[]::new);
        var created=inventory.create(admin,request(),order(pending));
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)created.get("items");
        for(int k=0;k<lines.length;k++)if(lines[k].receivedQuantity()!=null&&lines[k].receivedQuantity()>0)move(items.get(k),Kind.RECEIPT,lines[k].receivedQuantity(),null);
        return inventory.purchase(admin,id(created));
    }
    @SuppressWarnings("unchecked") Map<String,Object> first(Map<String,Object> order){return ((List<Map<String,Object>>)order.get("items")).getFirst();}
    long id(Map<String,Object> item){return Long.parseLong(item.get("id").toString());}
    long n(Map<String,Object> item,String field){return ((Number)item.get(field)).longValue();}
    Map<String,Object> move(Map<String,Object> item,Kind type,long q,String ref){return inventory.move(staff,id(item),request(),new MovementInput(n(item,"revision"),type,q,at,"사유","sale-1",ref));}
    Map<String,Object> current(Map<String,Object> item){return inventory.item(admin,id(item));}
    Map<String,Object> latest(Map<String,Object> item){return inventory.movements(admin,item.get("id").toString(),"",0,20).items().getFirst();}
    PurchaseDelete deletion(Map<String,Object> order){return new PurchaseDelete(n(order,"revision"),order.get("deleteToken").toString());}
    void balances(Map<String,Object> item,long hand,long pending){assertThat(n(item,"onHand")).isEqualTo(hand);assertThat(n(item,"pending")).isEqualTo(pending);}
    void ledger(Map<String,Object> item){
        var actual=current(item);assertThat(n(actual,"orderedQuantity")).isEqualTo(n(actual,"receivedQuantity")+n(actual,"pending")+n(actual,"cancelledQuantity"));
        var state=jdbc.queryForMap("SELECT i.on_hand,i.pending,i.original_ordered_quantity,COALESCE(SUM(m.hand_delta),0) dh,COALESCE(SUM(m.pending_delta),0) dp FROM inventory_item i LEFT JOIN inventory_movement m ON m.item_id=i.id WHERE i.id=? GROUP BY i.id",id(item));
        assertThat(n(state,"on_hand")).isEqualTo(n(state,"dh"));assertThat(n(state,"pending")).isEqualTo(n(state,"original_ordered_quantity")+n(state,"dp"));
    }
    @Test void lotsKeepPricesWithCurrentProductInformationAndUnknownIsDifferentFromZero(){
        var o=create(line(product,2,50_000L,2),line(product,3,60_000L,3),line(product,1,null,0),line(product,1,0L,0));
        var items=inventory.items(admin,"",product,"ALL",0,20).items();assertThat(items).hasSize(4);
        assertThat(items).extracting(i->i.get("unitPrice")).containsExactlyInAnyOrder(50_000L,60_000L,null,0L);
        var total=inventory.summaries(staff,List.of(product)).getFirst();balances(total,5,2);
        assertThat(first(o)).doesNotContainKeys("purchasedCode","purchasedName","currentProductCode");
        var p=products.product(Long.parseLong(product));products.edit(admin,Long.parseLong(product),new CatalogEdit(p.revision(),"","NEW-"+UUID.randomUUID(),"바뀐 검색어"));
        var preserved=current(first(o));assertThat(preserved.get("productCode")).isEqualTo(products.product(Long.parseLong(product)).productCode());assertThat(preserved).doesNotContainKeys("purchasedCode","purchasedName");
        jdbc.update("UPDATE catalog_product SET latest_status='SOLD_OUT',latest_result=JSON_OBJECT('status','SOLD_OUT') WHERE id=?",Long.parseLong(product));balances(current(first(o)),2,0);
    }
    @Test void stockProductsAppearBeforeReceiptAndKeepZeroBalancesWithoutCombiningLots() {
        var a=create(line(product,2,50_000L,0));var b=create(line(product,3,60_000L,0));
        var rows=inventory.stockProducts(admin,"",product,0,100);assertThat(rows.totalElements()).isEqualTo(1);balances(rows.items().getFirst(),0,5);
        assertThat(rows.items().getFirst()).containsEntry("remainingAmount","0");
        var first=move(first(a),Kind.RECEIPT,2,null);var second=move(first(b),Kind.RECEIPT,1,null);
        second=inventory.editItem(staff,id(second),new ItemEdit(n(second,"revision"),null,null,"레드","M",null,"선반 B","별도 메모",null,null));
        var summary=inventory.stockProduct(admin,Long.parseLong(product));balances(summary,3,2);assertThat(summary).containsEntry("remainingAmount","160000");
        assertThat(summary).doesNotContainKeys("location","locations");
        @SuppressWarnings("unchecked") var options=(List<Map<String,Object>>)summary.get("options");assertThat(options).hasSize(2);
        assertThat(inventory.items(admin,"","",product,"ALL",0,20).items()).hasSize(2);
        move(first,Kind.SALE_OUT,2,null);move(second,Kind.SALE_OUT,1,null);inventory.move(admin,id(second),request(),new MovementInput(n(current(second),"revision"),Kind.CANCEL_PENDING,2L,at,"취소","",null));
        balances(inventory.stockProduct(admin,Long.parseLong(product)),0,0);assertThat(inventory.stockProducts(admin,"",product,0,100).items()).hasSize(1);
        inventory.deletePurchase(admin,id(a),request(),deletion(inventory.purchase(admin,id(a))));assertThat(inventory.stockProducts(admin,"",product,0,100).items()).hasSize(1);
        inventory.deletePurchase(admin,id(b),request(),deletion(inventory.purchase(admin,id(b))));assertThat(inventory.stockProducts(admin,"",product,0,100).items()).isEmpty();
        assertThat(inventory.movements(admin,"","",product,0,100).items()).isEmpty();assertThat(inventory.movements(admin,"",product,0,100).items()).isNotEmpty();
    }
    @Test void stockGroupsNormalizeCodesSearchWholeGroupAndPaginateAfterGrouping() {
        String code=products.product(Long.parseLong(product)).productCode();
        String duplicate=products.create(admin,new CatalogEdit(null,"","DUPE-"+request(),"duplicate-only")).id();
        create(line(product,2,100L,2));create(line(duplicate,3,200L,1));
        jdbc.update("UPDATE catalog_product SET product_code=?,search_query='duplicate-only' WHERE id=?"," "+code.toLowerCase(Locale.ROOT)+" ",duplicate);
        jdbc.update("UPDATE catalog_product SET search_query='unique-lot-match' WHERE id=?",duplicate);
        for(String q:List.of("",code,"unique-lot-match")) {var page=inventory.stockProducts(admin,q,"",0,20);assertThat(page.totalElements()).isEqualTo(1);balances(page.items().getFirst(),3,2);assertThat(page.items().getFirst().get("productId")).isEqualTo(product);}
        for(String anchor:List.of(product,duplicate)){balances(inventory.stockProduct(staff,Long.parseLong(anchor)),3,2);assertThat(inventory.items(staff,"","",anchor,"ALL",0,20).items()).hasSize(2);assertThat(inventory.movements(staff,"","",anchor,0,20).items()).hasSize(2);}
        assertThat(inventory.summaries(staff,List.of(product,duplicate))).extracting(r->n(r,"onHand")).containsExactlyInAnyOrder(2L,1L);
        for(int k=0;k<21;k++){String p=products.create(admin,new CatalogEdit(null,"","PAGE-"+String.format("%02d",k)+"-"+request(),"page")).id();create(line(p,1,null,0));}
        var page0=inventory.stockProducts(admin,"","",0,20);var page1=inventory.stockProducts(admin,"","",1,20);assertThat(page0.totalElements()).isEqualTo(22);assertThat(page0.totalPages()).isEqualTo(2);assertThat(page0.items()).hasSize(20);assertThat(page1.items()).hasSize(2);
        assertThat(page0.items()).extracting(r->r.get("productCode").toString()).isSorted();assertThat(page1.items()).extracting(r->r.get("productId")).doesNotContainAnyElementsOf(page0.items().stream().map(r->r.get("productId")).toList());
        jdbc.update("UPDATE catalog_product SET product_code='' WHERE id IN (?,?)",product,duplicate);assertThat(inventory.stockProducts(admin,"","",0,100).totalElements()).isEqualTo(23);
    }
    @Test void brandFiltersAndTotalQuantitiesCoverAllPagesWholeGroupsAndActiveOrders() throws Exception {
        long brandA=ProductStore.id(),brandB=ProductStore.id();
        jdbc.update("INSERT INTO product_brand(id,name,created_at,updated_at) VALUES(?,?,NOW(6),NOW(6)),(?,?,NOW(6),NOW(6))",brandA,"재고 A-"+request(),brandB,"재고 B-"+request());
        String duplicate=products.create(admin,new CatalogEdit(null,"","DUP-"+request(),"중복 검색어")).id(),other=products.create(admin,new CatalogEdit(null,"","OTHER-"+request(),"다른 브랜드")).id(),unassigned=products.create(admin,new CatalogEdit(null,"","NONE-"+request(),"미지정")).id();
        var a=create(line(product,2,100L,2));create(line(duplicate,3,null,1));var b=create(line(other,4,200L,4));create(line(unassigned,2,0L,2));
        jdbc.update("UPDATE catalog_product SET brand_id=? WHERE id=?",brandA,product);jdbc.update("UPDATE catalog_product SET brand_id=? WHERE id IN (?,?)",brandB,duplicate,other);
        jdbc.update("UPDATE catalog_product SET product_code=? WHERE id=?"," "+products.product(Long.parseLong(product)).productCode().toLowerCase(Locale.ROOT)+" ",duplicate);
        String ba=Long.toString(brandA),bb=Long.toString(brandB);
        assertThat(inventory.stockProducts(staff,"","",ba,0,20).items()).singleElement().satisfies(r->balances(r,3,2));
        assertThat(inventory.stockTotals(staff,"중복 검색어",duplicate,ba)).containsEntry("totalOnHand","9").containsEntry("filteredOnHand","3");
        assertThat(inventory.stockTotals(staff,"","",bb)).containsEntry("filteredOnHand","4");assertThat(inventory.stockTotals(staff,"","","UNASSIGNED")).containsEntry("filteredOnHand","2");
        assertThat(inventory.purchases(staff,"",bb,0,20).totalElements()).isEqualTo(2);assertThat(inventory.movements(staff,"","","",bb,0,20).totalElements()).isEqualTo(2);
        for(int k=0;k<21;k++){String p=products.create(admin,new CatalogEdit(null,"","TOTAL-"+k+"-"+request(),"페이지 밖 재고")).id();jdbc.update("UPDATE catalog_product SET brand_id=? WHERE id=?",brandA,p);create(line(p,1,null,1));}
        assertThat(inventory.stockProducts(staff,"","",ba,0,20).items()).hasSize(20);assertThat(inventory.stockProducts(staff,"","",ba,1,20).items()).hasSize(2);
        assertThat(inventory.stockTotals(staff,"","",ba)).containsEntry("totalOnHand","30").containsEntry("filteredOnHand","24");
        assertThat(inventory.stockTotals(staff,"검색 결과 없음","",ba)).containsEntry("totalOnHand","30").containsEntry("filteredOnHand","0");
        for(long actor:List.of(admin,staff)){var http=new Browser(actor);var result=http.get("/api/inventory/stock-totals?brandId="+ba);assertThat(result.statusCode()).isEqualTo(200);assertThat(result.body()).contains("\"totalOnHand\":\"30\"","\"filteredOnHand\":\"24\"").doesNotContain("unitPrice","remainingAmount","paymentAmount");assertThat(http.get("/api/inventory/stock-products?brandId="+ba+"&size=20&page=1").statusCode()).isEqualTo(200);assertThat(http.get("/api/inventory/purchases?brandId="+bb).statusCode()).isEqualTo(200);assertThat(http.get("/api/inventory/movements?brandId="+bb).statusCode()).isEqualTo(200);}
        assertThat(new Browser(viewer).get("/api/inventory/stock-totals").statusCode()).isEqualTo(403);assertThat(new Browser(staff).get("/api/inventory/stock-totals?brandId=bad").statusCode()).isEqualTo(400);
        move(first(b),Kind.SALE_OUT,4,null);inventory.deletePurchase(admin,id(b),request(),deletion(inventory.purchase(admin,id(b))));
        assertThat(inventory.stockTotals(staff,"","",bb)).containsEntry("totalOnHand","26").containsEntry("filteredOnHand","0");
    }
    @Test void stockTotalsRemainExactAndFinancialFieldsStayPrivateOverHttp() throws Exception {
        var lines=new ArrayList<ItemInput>();for(int k=0;k<10;k++)lines.add(line(product,1_000_000,1_000_000_000L,1_000_000));
        var a=create(lines.toArray(ItemInput[]::new));assertThat(inventory.stockProduct(admin,Long.parseLong(product))).containsEntry("remainingAmount","10000000000000000");
        var missing=create(line(product,1,null,0));assertThat(inventory.stockProduct(admin,Long.parseLong(product))).containsEntry("remainingAmount","10000000000000000");
        var item=move(first(missing),Kind.RECEIPT,1,null);assertThat(inventory.stockProduct(admin,Long.parseLong(product)).get("remainingAmount")).isNull();
        var receipt=latest(item);move(item,Kind.REVERSE,1,receipt.get("id").toString());assertThat(inventory.stockProduct(admin,Long.parseLong(product))).containsEntry("remainingAmount","10000000000000000");
        for(String path:List.of("/api/inventory/stock-products?productId="+product,"/api/inventory/stock-products/"+product,"/api/inventory/items?groupProductId="+product,"/api/inventory/movements?groupProductId="+product)){
            var response=new Browser(staff).get(path);assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).doesNotContain("remainingAmount","unitPrice","private-note","orderUrl","paymentAmount","purchaseAmount");assertThat(new Browser(viewer).get(path).statusCode()).isEqualTo(403);
        }
        assertThat(new Browser(admin).get("/api/inventory/stock-products/"+product).body()).contains("\"remainingAmount\":\"10000000000000000\"");
        assertThat(new Browser(admin).get("/api/inventory/stock-products?size=19").statusCode()).isEqualTo(400);
        assertThat(new Browser(admin).get("/api/inventory/stock-products/999999999999999999").statusCode()).isEqualTo(404);
        assertThat(inventory.purchase(admin,id(a)).get("itemCount")).isEqualTo(10L);
    }
    @Test void stockGroupsFollowCodeChangesMergeAndDeletedProductsWithoutChangingLots() {
        var a=create(line(product,1,0L,1));var item=first(a);String original=item.get("productCode").toString();
        var p=products.product(Long.parseLong(product));products.edit(admin,Long.parseLong(product),new CatalogEdit(p.revision(),"","UPDATED-"+request(),"new"));
        assertThat(inventory.stockProduct(admin,Long.parseLong(product)).get("productCode")).isNotEqualTo(original);assertThat(current(item).get("productCode")).isEqualTo(products.product(Long.parseLong(product)).productCode());
        String duplicate=products.create(admin,new CatalogEdit(null,"","MERGE-"+request(),"merge")).id();create(line(duplicate,2,100L,1));
        String code=products.product(Long.parseLong(product)).productCode();jdbc.update("UPDATE catalog_product SET product_code=? WHERE id=?",code,duplicate);
        jdbc.update("UPDATE inventory_item SET product_id=? WHERE product_id=?",product,duplicate);jdbc.update("UPDATE catalog_product SET merged_into=? WHERE id=?",product,duplicate);
        balances(inventory.stockProduct(admin,Long.parseLong(duplicate)),2,1);assertThat(inventory.items(admin,"","",duplicate,"ALL",0,20).items()).hasSize(2);
        // A deleted catalogue row still identifies historical, exhausted inventory.
        for(var lot:inventory.items(admin,"",product,"ALL",0,20).items()){if(n(lot,"onHand")>0)move(lot,Kind.SALE_OUT,n(lot,"onHand"),null);var latest=current(lot);if(n(latest,"pending")>0)inventory.move(admin,id(latest),request(),new MovementInput(n(latest,"revision"),Kind.CANCEL_PENDING,n(latest,"pending"),at,"취소","",null));}
        jdbc.update("UPDATE catalog_product SET deleted_at=NOW() WHERE id=?",product);
        var exhausted=inventory.stockProduct(admin,Long.parseLong(product));balances(exhausted,0,0);assertThat(exhausted.get("productDeleted")).isEqualTo(true);
        assertThat(inventory.stockProducts(staff,"",duplicate,0,100).totalElements()).isEqualTo(1);assertThat(current(item).get("productCode")).isEqualTo(code);
    }
    @Test void purchaseAmountUsesCurrentOrderedQuantitiesAndIsIndependentOfPaymentAndMovements() {
        var input=new PurchaseInput(date,"매장","","","","",date,"메모",List.of(line(product,2,50_000L,0),line(product,3,60_000L,0)),null,999L);
        var o=inventory.create(admin,request(),input);assertThat(o).containsEntry("purchaseAmount","280000").containsEntry("paymentAmount",999L);
        var item=move(first(o),Kind.RECEIPT,1,null);item=inventory.move(admin,id(item),request(),new MovementInput(n(item,"revision"),Kind.CANCEL_PENDING,1L,at,"미입고 취소","",null));
        assertThat(inventory.purchase(admin,id(o))).containsEntry("purchaseAmount","280000");
        item=inventory.editItem(admin,id(item),new ItemEdit(n(item,"revision"),null,null,"","",null,"","",3L,60_000L));
        assertThat(inventory.purchase(admin,id(o))).containsEntry("purchaseAmount","360000").containsEntry("paymentAmount",999L);
        move(item,Kind.SALE_OUT,1,null);assertThat(inventory.purchases(admin,"",0,100).items().getFirst()).containsEntry("purchaseAmount","360000");
        assertThat(inventory.purchase(staff,id(o))).doesNotContainKeys("purchaseAmount","paymentAmount");ledger(item);
        var unknown=create(line(product,1,0L,0),line(product,2,null,0));assertThat(unknown).containsEntry("purchaseAmount",null);
        @SuppressWarnings("unchecked") var unknownItem=((List<Map<String,Object>>)unknown.get("items")).get(1);
        inventory.editItem(admin,id(unknownItem),new ItemEdit(n(unknownItem,"revision"),null,null,"","",null,"","",2L,0L));
        assertThat(inventory.purchase(admin,id(unknown))).containsEntry("purchaseAmount","0");
    }
    @Test void purchaseAmountRemainsAnExactStringAboveJavascriptSafeInteger() {
        var lines=new ItemInput[100];Arrays.fill(lines,line(product,1_000_000,1_000_000_000L,0));
        var o=create(lines);assertThat(o).containsEntry("purchaseAmount","100000000000000000");
        assertThat(inventory.purchases(admin,"",0,100).items().getFirst()).containsEntry("purchaseAmount","100000000000000000");
        assertThat(inventory.purchases(staff,"",0,100).items().getFirst()).doesNotContainKey("purchaseAmount");
    }
    @Test void retiredLocationIsIgnoredWithoutChangingHistoricalRetryFingerprints() throws Exception {
        var o=create(line(product,5,100L,0));var item=first(o);String req=request();
        var input=new MovementInput(n(item,"revision"),Kind.RECEIPT,1L,at,"","",null,"old location");
        item=inventory.move(staff,id(item),req,input);assertThat(item).doesNotContainKey("location");balances(item,1,4);
        inventory.move(staff,id(item),req,input);assertThat(inventory.movements(staff,item.get("id").toString(),"",0,100).items()).hasSize(1);
        item=inventory.editItem(staff,id(item),new ItemEdit(n(item,"revision"),null,null,"","",null,"ignored","",null,null));
        assertThat(inventory.move(staff,id(item),req,input)).doesNotContainKey("location");
        var current=item;
        assertThatThrownBy(()->inventory.move(staff,id(current),request(),new MovementInput(n(current,"revision"),Kind.RECEIPT,5L,at,"","",null,"ignored"))).hasMessageContaining("범위");
        balances(current(current),1,4);
        item=inventory.move(staff,id(item),request(),new MovementInput(n(item,"revision"),Kind.RECEIPT,1L,at,"","",null,"x".repeat(256)));balances(item,2,3);
        String receipt=latest(item).get("id").toString();item=move(item,Kind.REVERSE,1,receipt);ledger(item);
        var legacy=new MovementInput(n(item,"revision"),Kind.RECEIPT,1L,at,"","",null);String oldReq=request();
        record LegacyMovement(Long revision,Kind kind,Long quantity,LocalDateTime occurredAt,String reason,String referenceNumber,String referenceId) {}
        String oldHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(List.of(staff,id(item),new LegacyMovement(legacy.revision(),legacy.kind(),legacy.quantity(),legacy.occurredAt(),legacy.reason(),legacy.referenceNumber(),legacy.referenceId()))).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        inventory.move(staff,id(item),oldReq,legacy);assertThat(jdbc.queryForObject("SELECT request_hash FROM inventory_movement WHERE request_id=?",String.class,oldReq)).isEqualTo(oldHash);
        assertThat(inventory.move(staff,id(item),oldReq,legacy)).doesNotContainKey("location");
        assertThat(inventory.stockProduct(staff,Long.parseLong(product))).doesNotContainKeys("location","locations");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='inventory_item' AND column_name='location'",Integer.class)).isZero();
    }
    @Test void concurrentReceiptWithRetiredLocationStillUsesQuantityVersion() throws Exception {
        var item=first(create(line(product,3,null,0)));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var results=new ArrayList<Future<Boolean>>();
            for(String place:List.of("A","B"))results.add(pool.submit(()->{gate.await();try{inventory.move(staff,id(item),request(),new MovementInput(n(item,"revision"),Kind.RECEIPT,1L,at,"","",null,place));return true;}catch(cc.ataglace.molebutter.exception.OperationFailure ex){return false;}}));
            gate.countDown();int winners=0;for(var result:results)if(result.get())winners++;assertThat(winners).isEqualTo(1);assertThat(current(item)).doesNotContainKey("location");balances(current(item),1,2);ledger(item);
        }
    }
    @Test void partialReceiptsCancelPendingAndReversalsKeepAppendOnlyLedger(){
        var item=first(create(line(product,5,100L,0)));balances(item,0,5);
        item=move(item,Kind.RECEIPT,3,null);balances(item,3,2);
        final var before=item;assertThatThrownBy(()->move(before,Kind.RECEIPT,3,null)).hasMessageContaining("범위");
        item=inventory.move(admin,id(item),request(),new MovementInput(n(item,"revision"),Kind.CANCEL_PENDING,2L,at,"미배송 취소","",null));balances(item,3,0);
        String cancellation=latest(item).get("id").toString();final var cancelled=item;
        assertThatThrownBy(()->move(cancelled,Kind.REVERSE,2,cancellation)).isInstanceOf(cc.ataglace.molebutter.exception.BusinessException.class);
        item=inventory.move(admin,id(item),request(),new MovementInput(n(item,"revision"),Kind.REVERSE,2L,at,"취소 오류","",cancellation));balances(item,3,2);
        item=move(item,Kind.ADJUST_OUT,1,null);String adjustment=latest(item).get("id").toString();item=move(item,Kind.REVERSE,1,adjustment);balances(item,3,2);ledger(item);
        final var restored=item;assertThatThrownBy(()->move(restored,Kind.REVERSE,1,adjustment)).hasMessageContaining("취소할 수");
        assertThat(inventory.movements(staff,item.get("id").toString(),"",0,20).items()).hasSize(5);
    }
    @Test void returnsCannotExceedOriginalSaleAndDependentReturnsMustBeReversedFirst(){
        var item=first(create(line(product,3,100L,3)));item=move(item,Kind.SALE_OUT,2,null);String sale=latest(item).get("id").toString();
        final var sold=item;assertThatThrownBy(()->move(sold,Kind.CUSTOMER_RETURN,3,sale)).hasMessageContaining("초과");
        item=move(item,Kind.CUSTOMER_RETURN,1,sale);String returned=latest(item).get("id").toString();balances(item,2,0);
        final var withReturn=item;assertThatThrownBy(()->move(withReturn,Kind.REVERSE,2,sale)).hasMessageContaining("먼저 취소");
        item=move(item,Kind.REVERSE,1,returned);item=move(item,Kind.REVERSE,2,sale);balances(item,3,0);ledger(item);
    }
    @Test void supplierReturnRefundDoesNotAlterStockAndBlocksReversalUntilCleared(){
        var item=first(create(line(product,2,100L,2)));item=move(item,Kind.SUPPLIER_RETURN,1,null);long movement=Long.parseLong(latest(item).get("id").toString());
        inventory.refund(admin,movement,new RefundInput(0L,"PENDING",100L,null));balances(current(item),1,0);
        inventory.refund(admin,movement,new RefundInput(1L,"COMPLETED",90L,date));balances(current(item),1,0);
        final var returned=item;assertThatThrownBy(()->move(returned,Kind.REVERSE,1,Long.toString(movement))).hasMessageContaining("환불");
        assertThatThrownBy(()->inventory.refund(staff,movement,new RefundInput(2L,"NONE",null,null))).isInstanceOf(cc.ataglace.molebutter.exception.BusinessException.class);
        inventory.refund(admin,movement,new RefundInput(2L,"NONE",null,null));item=move(item,Kind.REVERSE,1,Long.toString(movement));balances(item,2,0);ledger(item);
    }
    @Test void orderQuantityEditsAreRecordedAndNeverEraseReceiptHistory(){
        var item=first(create(line(product,3,100L,2)));
        var edited=inventory.editItem(admin,id(item),new ItemEdit(n(item,"revision"),item.get("productCode").toString(),"snapshot","블랙","M","option","B","note",5L,200L));balances(edited,2,3);ledger(edited);
        assertThat(latest(item).get("kind")).isEqualTo("ORDER_INCREASE");
        assertThatThrownBy(()->inventory.editItem(admin,id(item),new ItemEdit(n(edited,"revision"),item.get("productCode").toString(),"","","","","","",1L,null))).hasMessageContaining("줄일 수");
        var staffEdit=inventory.editItem(staff,id(item),new ItemEdit(n(edited,"revision"),null,null,"","L","직접 옵션","C","운영 메모",null,null));assertThat(staffEdit).doesNotContainKeys("unitPrice","remainingAmount");assertThat(current(item)).containsEntry("unitPrice",200L);
    }
    @Test void purchaseReceiptTotalsStaySeparateFromPhysicalStockAndReverseWithTheirOriginalRecords() {
        var order=create(line(product,10,100L,8),line(product,2,0L,0));var item=first(order);
        String receipt=latest(item).get("id").toString();
        item=move(item,Kind.SALE_OUT,3,null);String sale=latest(item).get("id").toString();
        balances(item,5,2);assertThat(n(item,"receivedQuantity")).isEqualTo(8);assertThat(n(item,"cancelledQuantity")).isZero();ledger(item);
        var totals=inventory.purchase(staff,id(order));assertThat(n(totals,"orderedQuantity")).isEqualTo(12);assertThat(n(totals,"receivedQuantity")).isEqualTo(8);assertThat(n(totals,"pending")).isEqualTo(4);
        item=move(item,Kind.CUSTOMER_RETURN,1,sale);item=move(item,Kind.SUPPLIER_RETURN,1,null);item=move(item,Kind.DISPOSE,1,null);item=move(item,Kind.ADJUST_IN,1,null);item=move(item,Kind.ADJUST_OUT,1,null);
        balances(item,4,2);assertThat(n(item,"receivedQuantity")).isEqualTo(8);ledger(item);
        item=inventory.move(admin,id(item),request(),new MovementInput(n(item,"revision"),Kind.CANCEL_PENDING,2L,at,"취소","",null));String cancel=latest(item).get("id").toString();
        assertThat(n(item,"cancelledQuantity")).isEqualTo(2);ledger(item);
        item=inventory.move(admin,id(item),request(),new MovementInput(n(item,"revision"),Kind.REVERSE,2L,at,"취소 복원","",cancel));
        balances(item,4,2);assertThat(n(item,"cancelledQuantity")).isZero();ledger(item);
        item=move(item,Kind.ADJUST_IN,4,null);item=move(item,Kind.REVERSE,8,receipt);
        balances(item,0,10);assertThat(n(item,"receivedQuantity")).isZero();ledger(item);
        item=inventory.editItem(admin,id(item),new ItemEdit(n(item,"revision"),null,null,"","",null,"","",12L,100L));
        balances(item,0,12);ledger(item);assertThat(n(inventory.purchases(admin,"",0,20).items().getFirst(),"receivedQuantity")).isZero();
    }
    @Test void retiredProductFieldsAreIgnoredButLegacyCreationFingerprintsRemainReplayable() throws Exception {
        var old=new ItemInput(product,null,"OLD-CODE","OLD-NAME","","",null,null,10L,100L,"","",0L,null);
        var input=order(old);String req=request();
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(List.of(admin,input)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var order=inventory.create(admin,req,input);var item=first(order);
        assertThat(jdbc.queryForObject("SELECT request_hash FROM inventory_purchase WHERE id=?",String.class,id(order))).isEqualTo(digest);
        assertThat(inventory.create(admin,req,input).get("id")).isEqualTo(order.get("id"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='inventory_item' AND column_name IN ('purchased_code','purchased_name')",Long.class)).isZero();
        assertThat(item).doesNotContainKeys("purchasedCode","purchasedName");
        var changed=products.edit(admin,Long.parseLong(product),new CatalogEdit(products.product(Long.parseLong(product)).revision(),"","CURRENT-CODE","current search"));
        item=inventory.editItem(admin,id(item),new ItemEdit(n(item,"revision"),"IGNORED-CODE","IGNORED-NAME","","",null,"","",10L,100L));
        assertThat(item).containsEntry("productCode",changed.productCode());
        item=move(item,Kind.RECEIPT,1,null);
        assertThat(latest(item)).containsEntry("productCode",changed.productCode()).doesNotContainKeys("purchasedCode","purchasedName");
        assertThat(first(inventory.purchase(admin,id(order)))).containsEntry("productCode",changed.productCode());
        assertThat(inventory.items(admin,"OLD-CODE",product,"ALL",0,20).items()).isEmpty();
        assertThat(inventory.stockProducts(admin,"OLD-NAME",product,0,20).items()).isEmpty();
        assertThat(inventory.create(admin,req,input).get("id")).isEqualTo(order.get("id"));ledger(item);
    }
    @Test void duplicateRequestsReplayOnceAndConcurrentOutsAcceptOnlyOneRevision() throws Exception {
        var input=order(line(product,1,100L,0));String req=request();
        var original=inventory.create(admin,req,input);assertThat(inventory.create(admin,req,input).get("id")).isEqualTo(original.get("id"));assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_purchase",Long.class)).isEqualTo(1);
        assertThatThrownBy(()->inventory.create(admin,req,order(line(product,2,100L,0)))).hasMessageContaining("내용");
        var item=move(first(original),Kind.RECEIPT,1,null);var x=new MovementInput(n(item,"revision"),Kind.SALE_OUT,1L,at,"sale","",null);String moveReq=request();
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Map<String,Object>>>();
            for(int k=0;k<2;k++)tasks.add(pool.submit(()->{gate.await();return inventory.move(staff,id(item),moveReq,x);}));gate.countDown();for(var task:tasks)balances(task.get(),0,0);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE kind='SALE_OUT'",Long.class)).isEqualTo(1);ledger(item);
        var second=first(create(line(product,1,100L,1)));
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Boolean>>();
            for(int k=0;k<2;k++)tasks.add(pool.submit(()->{gate.await();try{move(second,Kind.SALE_OUT,1,null);return true;}catch(cc.ataglace.molebutter.exception.OperationFailure ex){return false;}}));gate.countDown();assertThat(tasks.get(0).get()^tasks.get(1).get()).isTrue();
        }balances(current(second),0,0);ledger(second);
    }
    @Test void mergeRebindsLotsButKeepsTheirHistoryAndStockBlocksDeletion(){
        var item=first(create(line(product,2,100L,1)));var p=products.product(Long.parseLong(product));
        assertThatThrownBy(()->products.delete(staff,new DeleteProducts(List.of(new VersionedId(product,p.revision()))))).hasMessageContaining("재고");
        long duplicate=ProductStore.id();jdbc.update("INSERT INTO catalog_product(id,product_code,search_query,created_at,updated_at) VALUES(?,?,?,NOW(6),NOW(6))",duplicate,p.productCode(),p.searchQuery());
        var target=products.product(duplicate);var merged=products.merge(staff,duplicate,new MergeInput(List.of(new VersionedId(target.id(),target.revision()),new VersionedId(p.id(),p.revision())),"",p.productCode(),p.searchQuery(),true,"","MANUAL","GENERAL",null));
        item=current(item);assertThat(item).containsEntry("productId",target.id()).containsEntry("originProductId",product);balances(item,1,1);ledger(item);
        balances(inventory.stockProduct(admin,Long.parseLong(product)),1,1);assertThat(inventory.stockProduct(admin,duplicate).get("productId")).isEqualTo(target.id());
        item=move(item,Kind.DISPOSE,1,null);item=inventory.move(admin,id(item),request(),new MovementInput(n(item,"revision"),Kind.CANCEL_PENDING,1L,at,"cancel","",null));
        products.delete(staff,new DeleteProducts(List.of(new VersionedId(merged.id(),merged.revision()))));assertThat(n(current(item),"productDeleted")).isEqualTo(1);assertThat(inventory.movements(staff,item.get("id").toString(),"",0,20).items()).hasSize(3);
        assertThat(inventory.stockProduct(admin,Long.parseLong(product))).containsEntry("productDeleted",true);balances(inventory.stockProduct(admin,duplicate),0,0);
    }
    @Test void concurrentReceiptsReplayOnceAndRejectStaleVersions() throws Exception {
        var item=first(create(line(product,1,null,0)));String req=request();
        var input=new MovementInput(n(item,"revision"),Kind.RECEIPT,1L,at,"",null,null);
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Map<String,Object>>>();
            for(int k=0;k<2;k++)tasks.add(pool.submit(()->{gate.await();return inventory.move(staff,id(item),req,input);}));
            gate.countDown();for(var task:tasks)balances(task.get(),1,0);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE request_id=?",Long.class,req)).isEqualTo(1);ledger(item);
        var other=first(create(line(product,2,null,0)));
        try(var pool=Executors.newFixedThreadPool(2)){
            var gate=new CountDownLatch(1);var tasks=new ArrayList<Future<Boolean>>();
            for(int k=0;k<2;k++)tasks.add(pool.submit(()->{gate.await();try{move(other,Kind.RECEIPT,2,null);return true;}catch(cc.ataglace.molebutter.exception.OperationFailure ex){return false;}}));
            gate.countDown();assertThat(tasks.get(0).get()^tasks.get(1).get()).isTrue();
        }
        balances(current(other),2,0);ledger(other);
    }
    @Test void invalidLinesRollbackWholeOrderAndSupplierMustBelongToProduct(){
        assertThatThrownBy(()->create(line(product,1,100L,1),line("999",1,null,0))).hasMessageContaining("상품");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_purchase",Long.class)).isZero();
        String other=products.create(admin,new CatalogEdit(null,"","OTHER-"+UUID.randomUUID(),"other")).id();long source=ProductStore.id();jdbc.update("INSERT INTO product_supplier(id,product_id,mall,mall_product_id,naver_product_id,url) VALUES(?,?,'LFMALL','one','one','https://supplier.test/one')",source,Long.parseLong(other));
        var invalid=new ItemInput(product,Long.toString(source),null,null,null,null,null,null,1L,null,null,null,0L,null);
        assertThatThrownBy(()->create(invalid)).hasMessageContaining("매입처");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_item",Long.class)).isZero();
    }
    @Test void deletingOrderCancelsAllPendingPreservesLedgerAndReplaysWithoutRestoringStock() {
        String other=products.create(admin,new CatalogEdit(null,"","DELETE-"+request(),"별도 상품")).id();
        var o=create(line(product,3,100L,0),line(other,2,null,0));String req=request();var input=deletion(o);
        var deleted=inventory.deletePurchase(admin,id(o),req,input);assertThat(deleted.get("deletedAt")).isNotNull();balances(deleted,0,0);
        assertThat(inventory.deletePurchase(admin,id(o),req,input).get("deletedAt")).isEqualTo(deleted.get("deletedAt"));
        assertThat(inventory.purchases(admin,"",0,100).items()).isEmpty();assertThat(inventory.items(staff,"","","ALL",0,100).items()).isEmpty();assertThat(inventory.summaries(staff,List.of(product,other))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_item WHERE purchase_id=?",Long.class,id(o))).isEqualTo(2);
        var item=first(deleted);ledger(item);assertThat(latest(item)).containsEntry("kind","CANCEL_PENDING").containsEntry("reason","구매 주문 삭제로 미입고 취소").containsEntry("purchaseDeleted",1L);
        assertThat(inventory.movements(staff,"","",0,100).items()).hasSize(2);
        assertThat(inventory.purchase(staff,id(o))).doesNotContainKeys("deleteToken","deleteBlockedReason","pendingRefundCount","paymentAmount","privateNote");
        assertThatThrownBy(()->move(item,Kind.REVERSE,3,latest(item).get("id").toString())).hasMessageContaining("삭제된");
        assertThatThrownBy(()->move(item,Kind.RECEIPT,1,null)).hasMessageContaining("삭제된");
        assertThatThrownBy(()->inventory.editItem(staff,id(item),new ItemEdit(n(item,"revision"),null,null,"red","",null,"","",null,null))).hasMessageContaining("삭제된");
        var p=products.product(Long.parseLong(product));products.delete(staff,new DeleteProducts(List.of(new VersionedId(product,p.revision()))));ledger(item);
        assertThat(inventory.purchase(admin,id(o))).containsEntry("orderNumber","private-order");
        assertThatThrownBy(()->inventory.deletePurchase(admin,id(o),request(),input)).hasMessageContaining("이미 삭제");
    }
    @Test void deletionRequiresEmptyStockSettledRefundsAndCurrentItemAndRefundVersions() {
        var o=create(line(product,3,100L,2));var item=first(o);
        assertThat(o.get("deleteBlockedReason").toString()).contains("보유 재고");
        assertThatThrownBy(()->inventory.deletePurchase(admin,id(o),request(),deletion(o))).hasMessageContaining("보유 재고");balances(current(item),2,1);
        item=move(item,Kind.SUPPLIER_RETURN,2,null);var m=latest(item);var before=inventory.purchase(admin,id(o));
        inventory.refund(admin,id(m),new RefundInput(0L,"PENDING",200L,null));
        assertThatThrownBy(()->inventory.deletePurchase(admin,id(o),request(),deletion(before))).hasMessageContaining("변경");
        var pending=inventory.purchase(admin,id(o));assertThat(pending.get("deleteBlockedReason").toString()).contains("환불 대기");
        assertThatThrownBy(()->inventory.deletePurchase(admin,id(o),request(),deletion(pending))).hasMessageContaining("환불 대기");
        inventory.refund(admin,id(m),new RefundInput(1L,"COMPLETED",200L,date));var settled=inventory.purchase(admin,id(o));
        var edited=inventory.editItem(staff,id(item),new ItemEdit(n(item,"revision"),null,null,"new","",null,"","",null,null));
        assertThatThrownBy(()->inventory.deletePurchase(admin,id(o),request(),deletion(settled))).hasMessageContaining("변경");
        inventory.deletePurchase(admin,id(o),request(),deletion(inventory.purchase(admin,id(o))));balances(current(edited),0,0);ledger(edited);
        assertThat(jdbc.queryForMap("SELECT refund_status,refund_amount FROM inventory_movement WHERE id=?",id(m))).containsEntry("refund_status","COMPLETED").containsEntry("refund_amount",200L);
        assertThatThrownBy(()->inventory.refund(admin,id(m),new RefundInput(2L,"NONE",null,null))).hasMessageContaining("삭제된");
        assertThat(inventory.movements(admin,"","",0,100).items()).hasSize(3);
    }
    @Test void deletionAndReceiptSerializeAndOnlyOneMayWin() throws Exception {
        var o=create(line(product,2,null,0));var item=first(o);var input=deletion(o);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var deleted=pool.submit(()->{gate.await();try{inventory.deletePurchase(admin,id(o),request(),input);return true;}catch(cc.ataglace.molebutter.exception.OperationFailure ex){return false;}});
            var received=pool.submit(()->{gate.await();try{move(item,Kind.RECEIPT,1,null);return true;}catch(cc.ataglace.molebutter.exception.OperationFailure ex){return false;}});
            gate.countDown();boolean deletionWon=deleted.get();assertThat(deletionWon^received.get()).isTrue();balances(current(item),deletionWon?0:1,deletionWon?0:1);ledger(item);
        }
    }
    @Test void deletionHttpRequiresAdminCsrfFreshAccountAndWritesAudit() throws Exception {
        var o=create(line(product,1,null,0));var input=deletion(o);String path="/api/inventory/purchases/"+o.get("id")+"/delete";
        var owner=new Browser(admin);assertThat(new Browser(staff).send("POST",path,input,true).statusCode()).isEqualTo(403);assertThat(new Browser(viewer).send("POST",path,input,true).statusCode()).isEqualTo(403);
        assertThat(owner.send("POST",path,input,false).statusCode()).isEqualTo(403);assertThat(inventory.purchase(admin,id(o)).get("deletedAt")).isNull();
        assertThat(owner.send("POST",path,input,true).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log WHERE event_type='INVENTORY_PURCHASE_DELETE' AND target_id=? AND success=1",Long.class,o.get("id"))).isEqualTo(1);
        jdbc.update("UPDATE `user` SET user_status='SUSPENDED' WHERE id=?",staff);assertThat(new Browser(staff).send("POST",path,input,true).statusCode()).isEqualTo(401);
    }
    @Test void everyStaffResponseRedactsPrivateInformationAndHttpChecksRoleCsrfAndCurrentAccount() throws Exception {
        var o=create(line(product,2,100L,2));var item=move(first(o),Kind.SUPPLIER_RETURN,1,null);long returnId=Long.parseLong(latest(item).get("id").toString());inventory.refund(admin,returnId,new RefundInput(0L,"COMPLETED",100L,date));
        for(Object response:List.of(inventory.item(staff,id(item)),inventory.items(staff,"",product,"ALL",0,20),inventory.purchase(staff,id(o)),inventory.purchases(staff,"private-order",0,20),inventory.movements(staff,item.get("id").toString(),"",0,20))) {
            String body=json.writeValueAsString(response);assertThat(body).doesNotContain("unitPrice","remainingAmount","paymentMethod","paymentMethodId","paymentAmount","paymentAlias","orderNumber","orderUrl","privateNote","refundStatus","refundAmount","refundedOn","private-card","private-note","private-order");
        }
        var staffBrowser=new Browser(staff);var viewerBrowser=new Browser(viewer);var ownerBrowser=new Browser(admin);
        assertThat(viewerBrowser.get("/api/inventory/items").statusCode()).isEqualTo(403);assertThat(viewerBrowser.get("/inventory").statusCode()).isEqualTo(403);
        assertThat(staffBrowser.get("/inventory").body()).doesNotContain("name=\"unitPrice\"","name=\"paymentAlias\"","id=\"purchase-create\"");
        assertThat(staffBrowser.get("/api/inventory/purchases/"+o.get("id")).body()).doesNotContain("private-note","orderUrl");
        assertThat(ownerBrowser.send("POST","/api/inventory/purchases",order(line(product,1,null,0)),false).statusCode()).isEqualTo(403);
        assertThat(staffBrowser.send("POST","/api/inventory/purchases",order(line(product,1,null,0)),true).statusCode()).isEqualTo(403);
        assertThat(staffBrowser.send("POST","/api/inventory/items/"+item.get("id")+"/movements",Map.of("revision",n(current(item),"revision"),"kind","ADJUST_OUT","quantity",0.9,"occurredAt",at.toString(),"reason","실사"),true).statusCode()).isEqualTo(400);
        balances(current(item),1,0);
        var x=new MovementInput(n(current(item),"revision"),Kind.ADJUST_OUT,1L,at,"실사","",null);
        assertThat(staffBrowser.send("POST","/api/inventory/items/"+item.get("id")+"/movements",x,true).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_audit_log WHERE event_type='INVENTORY_MOVEMENT'",Long.class)).isGreaterThan(0);
        jdbc.update("UPDATE `user` SET user_status='SUSPENDED' WHERE id=?",staff);assertThat(staffBrowser.get("/api/inventory/items").statusCode()).isEqualTo(401);
    }
    @Test void orderCreationRequiresSeparateReceiptAndBlankOptionsUseCurrentProduct() {
        assertThatThrownBy(()->inventory.create(admin,request(),order(line(product,2,100L,1)))).hasMessageContaining("주문 저장 후");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_purchase",Long.class)).isZero();
        var o=inventory.create(admin,request(),order(new ItemInput(product,null,null,null,null,null,null,null,2L,null,null,null,null,null)));
        var item=first(o);balances(item,0,2);assertThat(inventory.items(staff,"",product,"ON_HAND",0,20).items()).isEmpty();
        assertThat(item).doesNotContainKeys("purchasedCode","purchasedName").containsEntry("color","").containsEntry("size","").doesNotContainKey("location");
        item=move(item,Kind.RECEIPT,1,null);assertThat(inventory.items(staff,"",product,"ON_HAND",0,20).items()).hasSize(1);
        var updated=inventory.editItem(admin,id(item),new ItemEdit(n(item,"revision"),null,null,"","",null,"","상품 메모",2L,null));
        assertThat(updated).containsEntry("productCode",item.get("productCode")).doesNotContainKeys("purchasedCode","purchasedName");ledger(updated);
    }
    @Test void paymentMethodsPreserveHistoricalNamesAndDeletedSelectionsAndAmountsArePrivate() throws Exception {
        var method=payments.save(admin,null,new cc.ataglace.molebutter.service.product.SharedSettingsService.NameInput("업무 카드-"+request(),null));
        var input=new PurchaseInput(date,"매장","","","","",date,"결제 메모",List.of(line(product,2,100L,0)),method.id(),0L);
        var o=inventory.create(admin,request(),input);assertThat(o).containsEntry("paymentAmount",0L).containsEntry("paymentMethod",method.name());
        var renamed=payments.save(admin,Long.parseLong(method.id()),new cc.ataglace.molebutter.service.product.SharedSettingsService.NameInput("수정 카드-"+request(),method.revision()));
        assertThat(inventory.purchase(admin,id(o))).containsEntry("paymentMethod",method.name());
        assertThatThrownBy(()->payments.save(admin,Long.parseLong(method.id()),new cc.ataglace.molebutter.service.product.SharedSettingsService.NameInput("충돌",method.revision()))).isInstanceOf(cc.ataglace.molebutter.exception.OperationFailure.class);
        payments.delete(admin,Long.parseLong(method.id()),renamed.revision());assertThat(payments.list(admin)).noneMatch(m->m.id().equals(method.id()));
        o=inventory.editPurchase(admin,id(o),new PurchaseEdit(0L,date,"변경 매장","","","",null,date,"메모",method.id(),null));
        assertThat(o).containsEntry("paymentMethod",method.name()).containsEntry("paymentAmount",null);
        assertThatThrownBy(()->inventory.create(admin,request(),input)).hasMessageContaining("사용 가능한");
        assertThat(inventory.purchase(staff,id(o))).doesNotContainKeys("paymentAmount","paymentMethodId","paymentMethod","privateNote");
        var staffBrowser=new Browser(staff);assertThat(staffBrowser.get("/api/settings/payment-methods").statusCode()).isEqualTo(403);
        assertThat(staffBrowser.get("/settings?tab=payment-methods").body()).doesNotContain("id=\"payment-create-form\"");
        var owner=new Browser(admin);assertThat(owner.get("/api/settings/payment-methods").statusCode()).isEqualTo(200);
        assertThat(owner.send("POST","/api/settings/payment-methods",Map.of("name","HTTP 카드-"+request()),false).statusCode()).isEqualTo(403);
        assertThat(owner.send("POST","/api/settings/payment-methods",Map.of("name","HTTP 카드-"+request()),true).statusCode()).isEqualTo(200);
        var invalid=new PurchaseInput(date,"","","","","",null,"",List.of(line(product,1,null,0)),null,-1L);
        assertThatThrownBy(()->inventory.create(admin,request(),invalid)).hasMessageContaining("결제 금액");
    }
    class Browser {
        final HttpClient client=HttpClient.newHttpClient();final String token;String csrf;String cookie;
        Browser(long actor){token=tokens.issueOnSignin(users.findById(actor).orElseThrow()).accessToken();}
        HttpResponse<String> get(String path)throws Exception{return send("GET",path,null,false);}
        HttpResponse<String> send(String method,String path,Object body,boolean secure)throws Exception {
            if(secure&&csrf==null){var r=get("/api/auth/csrf");csrf=json.readTree(r.body()).path("data").path("token").asText();cookie=r.headers().allValues("set-cookie").stream().map(s->s.split(";",2)[0]).collect(java.util.stream.Collectors.joining("; "));}
            var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("X-Operation-Id",UUID.randomUUID().toString());
            if(secure){request.header("X-XSRF-TOKEN",csrf);request.header("Cookie",cookie);}
            return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
