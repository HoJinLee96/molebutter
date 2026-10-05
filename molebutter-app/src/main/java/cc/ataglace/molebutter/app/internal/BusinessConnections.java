package cc.ataglace.molebutter.app.internal;

import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import cc.ataglace.molebutter.inventory.api.SupplierReferencePort;
import cc.ataglace.molebutter.operations.api.NotificationTargets;
import cc.ataglace.molebutter.procurement.api.ProcurementProductQueries;
@Configuration
public class BusinessConnections {
    @Bean SupplierReferencePort supplierReferences(ProcurementProductQueries queries){return queries::validateSupplierReference;}
    @Bean NotificationTargets notificationTargets(JdbcTemplate jdbc){return new NotificationTargets(){
        public boolean ownsCorrection(long user,Long correction){return correction!=null&&jdbc.queryForObject("SELECT COUNT(*) FROM attendance_correction WHERE id=? AND user_id=?",Long.class,correction,user)>0;}
        private boolean exists(String sql,Long id){return id!=null&&jdbc.queryForObject(sql,Long.class,id)>0;}
        public String resolve(String type,Long id,long user){return switch(type){
            case "PRODUCT"->exists("SELECT COUNT(*) FROM catalog_product WHERE id=? AND deleted_at IS NULL AND merged_into IS NULL",id)?"/products?product="+id:null;
            case "REFRESH"->exists("SELECT COUNT(*) FROM product_refresh_run WHERE id=?",id)?"/product-refresh?run="+id:null;
            case "CORRECTION"->exists("SELECT COUNT(*) FROM attendance_correction WHERE id=?",id)?(ownsCorrection(user,id)?"/attendance":"/attendance-manage")+"?correction="+id:null;
            case "IMPORT"->"/products#import-panel";case "PRODUCTS"->"/products";case "INVENTORY"->"/inventory";case "SETTINGS"->"/settings";case "USERS"->"/user-manage";case "ATTENDANCE"->"/attendance";case "PROFILE"->"/my-page";default->null;
        };}
    };}
}
