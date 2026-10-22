package cc.ataglace.molebutter.app.internal;

import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import cc.ataglace.molebutter.inventory.api.SupplierReferencePort;
import cc.ataglace.molebutter.operations.api.NotificationTargets;
import cc.ataglace.molebutter.procurement.api.ProcurementProductQueries;
@Configuration
public class BusinessConnections {
    @Bean
    cc.ataglace.molebutter.coupang.api.CoupangCollectionNotifications collectionNotifications(
            cc.ataglace.molebutter.operations.api.NotificationService notifications) {
        return result->{
            var collection=result.collection();
            String status=switch(collection.status()) {
                case "SUCCEEDED"->"완료";case "PARTIAL"->"일부 실패";case "FAILED"->"실패";
                case "CANCELLED"->"취소";case "PAUSED"->"일시중지";default->"중단";
            };
            String severity=switch(collection.status()){case "SUCCEEDED","CANCELLED"->"INFO";case "FAILED"->"ERROR";default->"WARNING";};
            String message="대상 "+collection.discovered()+" · 완료 "+collection.completed()+" · 실패 "+collection.failed()
                    +(result.reasons().isEmpty()?"":" · 사유 "+String.join(", ",result.reasons()));
            notifications.publish(result.key(),"COUPANG_COLLECTION_RESULT",severity,
                    "쿠팡 "+(collection.mode().equals("RECENT")?"최근 등록 가져오기":collection.mode().equals("FULL")?"전체 가져오기":"선택 상품 갱신")+" "+status,
                    message,"COUPANG",null,java.util.List.of(result.actor()));
        };
    }
    @Bean
    cc.ataglace.molebutter.coupang.api.CoupangQuickEditSources quickEditSources(cc.ataglace.molebutter.inventory.api.InventoryService inventory,cc.ataglace.molebutter.coupang.api.CoupangOptionCounts optionCounts,ProcurementProductQueries queries,tools.jackson.databind.ObjectMapper json,cc.ataglace.molebutter.identity.api.BusinessAccess access,org.springframework.transaction.PlatformTransactionManager manager){
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);tx.setReadOnly(true);
        return (actor,id)->tx.execute(transaction->{
            access.productActor(actor,true);
            if(id==null||!id.matches("[0-9]{1,19}"))throw new cc.ataglace.molebutter.common.api.InputValidationFailure("내부 상품을 확인해 주세요.");
            long numeric;try{numeric=Long.parseLong(id);}catch(NumberFormatException failure){throw new cc.ataglace.molebutter.common.api.InputValidationFailure("내부 상품을 확인해 주세요.");}
            var p=queries.product(numeric);var supplier=p.selectedSupplier();
            var stock=queries.selectedStock(actor,numeric);
            var stocks=new java.util.ArrayList<cc.ataglace.molebutter.coupang.api.CoupangQuickEditSources.Stock>();
            for(var option:stock.options())if(option.stock()!=null&&option.stock()>=0)stocks.add(new cc.ataglace.molebutter.coupang.api.CoupangQuickEditSources.Stock(option.id(),option.label(),option.stock(),stock.previous(),stock.checkedAt(),option.state()));
            var owned=inventory.ownedStocks(actor,java.util.List.of(p.id())).get(p.id());
            int couponCount=optionCounts.byCode(actor,p.productCode());
            var supply=supply(supplier,stock);
            var proposal=cc.ataglace.molebutter.coupang.api.CoupangRecommendationSources.stockProposal(supply.state(),owned.quantity(),owned.options().size(),couponCount,
                stock.options().stream().filter(o->"AVAILABLE".equals(o.state())&&o.stock()!=null&&o.stock()>0).map(o->new cc.ataglace.molebutter.coupang.api.CoupangRecommendationSources.Stock(o.id(),o.label(),o.stock(),o.state())).toList(),stock.optionCount());
            String evidence=json.writeValueAsString(java.util.Arrays.asList(p.id(),p.revision(),p.lookupRevision(),p.productCode(),supplier,stock,owned,couponCount));
            String fingerprint;try{fingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(evidence.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
            return new cc.ataglace.molebutter.coupang.api.CoupangQuickEditSources.Source(p.id(),p.productCode(),supplier==null||"SELECTED_MISSING".equals(supply.state())?null:supplier.referencePrice(),supplier==null||supplier.priceCheckedAt()==null?null:supplier.priceCheckedAt().toString(),supplier==null||!supplier.current()||!"CONFIRMED".equals(supplier.priceStatus())||supplier.selectedMissing(),java.util.List.copyOf(stocks),fingerprint,stock.optionCount(),stock.options().stream().map(o->new cc.ataglace.molebutter.coupang.api.CoupangQuickEditSources.StockOption(o.id(),o.label(),o.stock()!=null&&o.stock()>=0?o.stock():null,o.state(),stock.previous(),stock.checkedAt())).toList(),supply.state(),supply.previous(),supply.at(),owned.quantity(),owned.options().stream().map(o->new cc.ataglace.molebutter.coupang.api.CoupangQuickEditSources.OwnedOption(o.color(),o.size(),o.quantity())).toList(),couponCount,supply.reason(),proposal);
        });
    }
    @Bean
    cc.ataglace.molebutter.coupang.api.CoupangRecommendationSources recommendationSources(ProcurementProductQueries queries,cc.ataglace.molebutter.inventory.api.InventoryService inventory) {
        return (actor,codes)->{
            var products=queries.byCodes(actor,codes);
            var grouped=products.stream().collect(java.util.stream.Collectors.groupingBy(p->cc.ataglace.molebutter.procurement.api.ProductCodePolicy.normalize(p.productCode())));
            var eligible=products.stream().filter(p->grouped.get(cc.ataglace.molebutter.procurement.api.ProductCodePolicy.normalize(p.productCode())).size()==1&&p.duplicateCount()==0).toList();
            var selectedStocks=queries.selectedStocks(actor,eligible);
            var owned=new java.util.HashMap<String,cc.ataglace.molebutter.inventory.api.InventoryService.OwnedStock>();
            var ids=eligible.stream().map(p->p.id()).toList();
            for(int offset=0;offset<ids.size();offset+=100)owned.putAll(inventory.ownedStocks(actor,ids.subList(offset,Math.min(ids.size(),offset+100))));
            var result=new java.util.HashMap<String,cc.ataglace.molebutter.coupang.api.CoupangRecommendationSources.Source>();
            grouped.forEach((code,matches)->{
                if(matches.size()!=1||matches.getFirst().duplicateCount()!=0)return;
                var p=matches.getFirst();var supplier=p.selectedSupplier();
                if(supplier==null||supplier.conflict()||"UNSUPPORTED_CHANNEL".equals(supplier.priceStatus())||"STALE".equals(supplier.priceStatus()))return;
                Long price=supplier.priceCheckedAt()!=null&&supplier.referencePrice()!=null&&supplier.referencePrice()>0?supplier.referencePrice():null;
                var stocks=new java.util.ArrayList<cc.ataglace.molebutter.coupang.api.CoupangRecommendationSources.Stock>();
                var confirmed=selectedStocks.get(p.id());int count=confirmed.optionCount();var supply=supply(supplier,confirmed);
                boolean latestNormal=java.util.Set.of("SUCCESS","PARTIAL","SOLD_OUT").contains(java.util.Objects.toString(p.latestStatus(),""));
                // The batch port retains only normal observations of this exact selected listing.
                for(var option:confirmed.options())if("AVAILABLE".equals(option.state())&&option.stock()!=null&&option.stock()>0)
                    stocks.add(new cc.ataglace.molebutter.coupang.api.CoupangRecommendationSources.Stock(option.id(),option.label(),option.stock(),option.state()));
                result.put(code,new cc.ataglace.molebutter.coupang.api.CoupangRecommendationSources.Source(p.id(),price,!latestNormal||!supplier.current()||!"CONFIRMED".equals(supplier.priceStatus()),supplier.priceCheckedAt()==null?null:supplier.priceCheckedAt().toString(),java.util.List.copyOf(stocks),count,supply.state(),supply.previous(),supply.at(),owned.get(p.id()).quantity(),owned.get(p.id()).options().size(),supply.reason()));
            });
            return java.util.Map.copyOf(result);
        };
    }
    private record Supply(String state,String reason,boolean previous,String at) {}
    private static Supply supply(cc.ataglace.molebutter.procurement.api.SupplierDtos.Listing supplier,ProcurementProductQueries.SelectedStock stock){
        if(supplier==null||supplier.conflict()||stock==null||java.util.Set.of("STALE","UNSUPPORTED_CHANNEL").contains(java.util.Objects.toString(supplier.priceStatus(),"")))return new Supply("UNKNOWN","UNVERIFIED",false,null);
        var search=stock.search();
        boolean missing=search!=null?search.selectedMissing():supplier.selectedMissing();
        String searchAt=search!=null?search.checkedAt():supplier.selectedSearchAt()==null?null:supplier.selectedSearchAt().toString();
        String missingAt=search!=null?search.lastMissingAt():missing?searchAt:null;
        if(missing&&searchAt==null)return new Supply("UNKNOWN","SEARCH_UNCONFIRMED",false,null);
        boolean positive=stock.options().stream().anyMatch(o->"AVAILABLE".equals(o.state())&&o.stock()!=null&&o.stock()>0);
        boolean newer=after(stock.checkedAt(),missing?searchAt:missingAt);
        if(missing&&searchAt!=null&&!(positive&&newer))return new Supply("SELECTED_MISSING","SELECTED_LISTING_MISSING",search!=null&&search.previous(),searchAt);
        if(missingAt!=null&&!newer)return new Supply("UNKNOWN","STOCK_AFTER_MISSING_UNCONFIRMED",stock.previous(),stock.checkedAt());
        if(stock.previous()&&stock.checkedAt()==null&&search!=null)return new Supply("UNKNOWN","STOCK_UNCONFIRMED",true,null);
        if(stock.options().isEmpty())return new Supply("UNKNOWN","STOCK_UNCONFIRMED",stock.previous(),stock.checkedAt());
        if(positive)return new Supply("AVAILABLE","PURCHASE_AVAILABLE",stock.previous(),stock.checkedAt());
        boolean closed=stock.options().size()==stock.optionCount()&&stock.options().stream().allMatch(o->
            java.util.Set.of("SOLD_OUT","UNAVAILABLE").contains(java.util.Objects.toString(o.state(),""))||
            o.stock()!=null&&o.stock()==0&&!java.util.Set.of("STOCK_UNKNOWN","FAILED","UNCONFIRMED").contains(java.util.Objects.toString(o.state(),"")));
        return new Supply(closed?"UNAVAILABLE":"UNKNOWN",closed?"PURCHASE_UNAVAILABLE":"STOCK_UNCONFIRMED",stock.previous(),stock.checkedAt());
    }
    private static boolean after(String observation,String boundary){
        if(observation==null||boundary==null)return false;
        try{return java.time.LocalDateTime.parse(observation).isAfter(java.time.LocalDateTime.parse(boundary));}catch(java.time.DateTimeException invalid){return false;}
    }
    @Bean
    SupplierReferencePort supplierReferences(ProcurementProductQueries queries) {
        return queries::validateSupplierReference;
    }
    @Bean
    NotificationTargets notificationTargets(JdbcTemplate jdbc) {
        return new NotificationTargets() {
            public boolean ownsCorrection(long user, Long correction) {
                return correction != null
                        && jdbc.queryForObject("SELECT COUNT(*) FROM attendance_correction WHERE id=? AND user_id=?",
                                Long.class, correction, user) > 0;
            }
            private boolean exists(String sql, Long id) {
                return id != null && jdbc.queryForObject(sql, Long.class, id) > 0;
            }
            public String resolve(String type, Long id, long user) {
                return switch (type) {
                    case "PRODUCT" -> exists(
                            "SELECT COUNT(*) FROM catalog_product WHERE id=? AND deleted_at IS NULL AND merged_into IS NULL",
                            id) ? "/products?product=" + id : null;
                    case "REFRESH" -> exists("SELECT COUNT(*) FROM product_refresh_run WHERE id=?", id)
                            ? "/product-refresh?run=" + id
                            : null;
                    case "CORRECTION" -> exists("SELECT COUNT(*) FROM attendance_correction WHERE id=?", id)
                            ? (ownsCorrection(user, id) ? "/attendance" : "/attendance-manage") + "?correction=" + id
                            : null;
                    case "COUPANG" -> "/coupang/products";
                    case "COUPANG_ORDERS" -> "/coupang/orders";
                    case "IMPORT" -> "/products#import-panel";
                    case "PRODUCTS" -> "/products";
                    case "INVENTORY" -> "/inventory";
                    case "SETTINGS" -> "/settings";
                    case "USERS" -> "/user-manage";
                    case "ATTENDANCE" -> "/attendance";
                    case "PROFILE" -> "/my-page";
                    default -> null;
                };
            }
        };
    }
}
