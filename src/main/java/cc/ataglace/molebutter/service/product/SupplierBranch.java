package cc.ataglace.molebutter.service.product;
import java.util.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.infra.product.SupplierBranchText;

public final class SupplierBranch {
    private SupplierBranch(){}
    public static BranchInfo resolve(Offer offer,SourceDetails detail){
        var names=new LinkedHashMap<String,String>();var retailers=new LinkedHashSet<String>();
        StoreEvidence structured=detail==null?null:detail.storeEvidence();
        if(structured!=null&&"CONFLICT".equals(structured.kind()))return new BranchInfo(null,"CONFLICT","매장 정보 불일치",structured.name(),structured);
        if(structured!=null&&"BRANCH".equals(structured.kind()))add(names,retailers,"매입처 매장 정보",structured.name());
        if(detail!=null){add(names,retailers,"매입처 상품 정보",detail.storeName());add(names,retailers,"매입처 상품명",detail.title());}
        add(names,retailers,"네이버 상품명",offer.title());add(names,retailers,"쇼핑몰 이름",offer.mallName());
        if(structured!=null)retailers.addAll(SupplierBranchText.retailers(structured.retailer()));
        if(names.size()>1||retailers.size()>1)return new BranchInfo(null,"CONFLICT","지점 표기 불일치",String.join(" / ",names.keySet())+" "+String.join(" / ",retailers),structured);
        if(structured!=null&&Set.of("SELLER","COMPANY").contains(structured.kind()))return new BranchInfo(structured.name(),"CONFIRMED","판매자 정보",structured.name(),structured);
        if(names.isEmpty())return new BranchInfo(null,"UNKNOWN",null,null);
        var e=names.entrySet().iterator().next();var proof=e.getValue().split("\n",2);
        String retailer=retailers.isEmpty()?(offer.mall()==Mall.HI_THEHYUNDAI?"현대백화점":null):retailers.iterator().next();
        if(structured==null)structured=new StoreEvidence("BRANCH",retailer,e.getKey(),null,null);
        return new BranchInfo(e.getKey(),"CONFIRMED",proof[0],proof[1],structured);
    }
    private static void add(Map<String,String> found,Set<String> retailers,String source,String raw){
        if(raw==null||raw.isBlank())return;
        retailers.addAll(SupplierBranchText.retailers(raw));for(String name:SupplierBranchText.names(raw))found.putIfAbsent(name,source+"\n"+raw);
    }
}
