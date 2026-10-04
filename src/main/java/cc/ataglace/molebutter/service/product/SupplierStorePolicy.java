package cc.ataglace.molebutter.service.product;

import java.util.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;

public final class SupplierStorePolicy {
    private SupplierStorePolicy() {}
    public record Identity(String kind,String key,String name,String retailer,List<ExternalIdentity> identities) {
        public Identity(String kind,String key,String name){this(kind,key,name,null,List.of());}
    }
    public static String normalize(String value){return Objects.toString(value,"").trim().replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}
    public static String listingKey(Offer o){return o.mall()+":"+o.mallProductId()+":"+o.naverProductId();}
    public static Identity identity(Offer offer,BranchInfo branch){
        if(branch!=null&&"CONFLICT".equals(branch.state()))return null;
        if(branch!=null&&"CONFIRMED".equals(branch.state())&&branch.store()!=null){
            var s=branch.store();var external=s.namespace()!=null&&s.externalId()!=null?List.of(new ExternalIdentity(s.namespace(),s.externalId())):List.<ExternalIdentity>of();
            if("COMPANY".equals(s.kind())&&offer.mall()==ProcurementMall.LOTTE_ON&&"주식회사 LF".equals(s.name()))return new Identity("COMPANY","company:lf",s.name(),null,external);
            if("SELLER".equals(s.kind()))return new Identity("SELLER","external:"+s.namespace()+":"+s.externalId(),s.name(),s.retailer(),external);
            if("BRANCH".equals(s.kind())&&!normalize(s.retailer()).isBlank())return new Identity("BRANCH","branch:"+normalize(s.retailer())+":"+normalize(s.name()),s.name(),s.retailer(),external);
        }
        if(offer.mall()==ProcurementMall.LOTTE_ON)return null; // 백화점 구분 없는 지점명만으로 입점 매장을 합치지 않는다.
        // 판매자 URL은 판매글 식별에만 사용한다. 백화점·지점이나 판매자 유형을 증명하지 않는다.
        if(offer.mall()==ProcurementMall.NAVER_SMART_STORE)return null;
        if(branch!=null&&"CONFIRMED".equals(branch.state())&&!normalize(branch.name()).isBlank())return new Identity("BRANCH","branch:"+normalize(branch.name()),branch.name(),offer.mall()==ProcurementMall.HI_THEHYUNDAI?"현대백화점":null,List.of());
        return null;
    }
    public static Store resolve(List<Store> stores,ProcurementMall mall,Identity identity){
        if(identity==null)return null;
        var external=stores.stream().filter(s->s.mall()==mall&&s.identities().stream().anyMatch(identity.identities()::contains)).toList();
        if(external.size()==1)return conflicts(stores,mall,identity)?null:external.getFirst();if(external.size()>1)return null;
        if(conflicts(stores,mall,identity))return null;
        var matches=stores.stream().filter(s->s.mall()==mall&&s.kind().equals(identity.kind())&&
            (s.identityKey().equals(identity.key())||!identity.kind().equals("SELLER")&&
            (normalize(s.retailer()).equals(normalize(identity.retailer()))||mall==ProcurementMall.HI_THEHYUNDAI&&normalize(s.retailer()).isBlank())&&
            s.aliases().stream().anyMatch(a->normalize(a).equals(normalize(identity.name()))))).toList();
        return matches.size()==1?matches.getFirst():null;
    }
    public static boolean conflicts(List<Store> stores,ProcurementMall mall,Identity identity){
        if(identity==null)return false;
        if(identity.kind().equals("COMPANY")&&stores.stream().anyMatch(s->s.mall()==mall&&s.identityKey().equals(identity.key())&&!s.identities().isEmpty()&&Collections.disjoint(s.identities(),identity.identities())))return true;
        return stores.stream().filter(s->s.mall()==mall&&s.identities().stream().anyMatch(identity.identities()::contains)).anyMatch(s->!compatible(s,identity)||!normalize(s.retailer()).isBlank()&&!normalize(identity.retailer()).isBlank()&&!normalize(s.retailer()).equals(normalize(identity.retailer())));
    }
    private static boolean compatible(Store store,Identity identity){
        return store.kind().equals(identity.kind())||store.mall()==ProcurementMall.NAVER_SMART_STORE&&"BRAND_STORE".equals(store.kind())&&"SELLER".equals(identity.kind())&&identity.identities().stream().anyMatch(i->"NAVER_CHANNEL".equals(i.namespace())&&store.identities().contains(i));
    }
    public static boolean storeContradiction(Store target,BranchInfo branch){
        if(target!=null&&"BRAND_STORE".equals(target.kind())){
            // 공식몰은 이름이나 수동 지정만으로 증명할 수 없다. 현재 채널 근거가 필요하다.
            if(branch==null||!"CONFIRMED".equals(branch.state())||branch.store()==null)return true;
            var e=branch.store();return !"SELLER".equals(e.kind())||!"NAVER_CHANNEL".equals(e.namespace())||!target.identities().contains(new ExternalIdentity(e.namespace(),e.externalId()));
        }
        return companyContradiction(target,branch);
    }
    public static boolean companyContradiction(Store target,BranchInfo branch){
        if(target==null||!"COMPANY".equals(target.kind())||branch==null||branch.store()==null)return false;
        var e=branch.store();
        return "CONFLICT".equals(branch.state())||"CONFIRMED".equals(branch.state())&&
            (!"COMPANY".equals(e.kind())||!"주식회사 LF".equals(e.name())||
             !target.identities().isEmpty()&&!target.identities().contains(new ExternalIdentity(e.namespace(),e.externalId())));
    }
    public static boolean include(Preferences p,Map<String,String> manual,Offer offer,BranchInfo branch){
        if(p==null)return true;if(!p.mallAllowed(offer.mall()))return false;if(!p.branchRequired(offer.mall()))return true;
        String id=manual.get(listingKey(offer));if(id!=null){
            var target=p.stores().stream().filter(s->s.id().equals(id)).findFirst().orElse(null);
            if(storeContradiction(target,branch))return true;
            return p.allowed(offer.mall(),id);
        }
        var identity=identity(offer,branch);if(identity==null)return true;
        if(conflicts(p.stores(),offer.mall(),identity))return true;
        var store=resolve(p.stores(),offer.mall(),identity);
        // 기존 이름만 등록된 매장은 관리자 연결을 위해 개별 확인 대상으로 보관한다.
        if(store==null&&!"SELLER".equals(identity.kind())&&p.stores().stream().anyMatch(s->s.mall()==offer.mall()&&normalize(s.retailer()).isBlank()&&s.aliases().stream().anyMatch(a->normalize(a).equals(normalize(identity.name())))))return true;
        return p.allowed(offer.mall(),store==null?null:store.id());
    }
}
