package cc.ataglace.molebutter.service.product;
import cc.ataglace.molebutter.exception.InputValidationFailure;

import java.net.URI;
import java.util.Objects;
import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.dto.product.ProductDtos.ProcurementMall;
import cc.ataglace.molebutter.dto.product.SupplierDtos.*;
import cc.ataglace.molebutter.infra.product.SupplierProductGateway;
import cc.ataglace.molebutter.infra.product.NaverChannelPolicy;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import lombok.RequiredArgsConstructor;

/** 공식 여부는 관리자가 지정한다. 서버는 본상품의 실제 채널만 검증한다. */
@Service @RequiredArgsConstructor
public class OfficialBrandStoreService {
    private final ProductStore db;
    private final SupplierProductGateway gateway;
    private final SupplierPreferenceService preferences;

    public static String productId(String value){
        try{
            if(value==null||value.length()>2000)throw new IllegalArgumentException();
            var uri=URI.create(value.trim());String host=Objects.toString(uri.getHost(),"").toLowerCase(java.util.Locale.ROOT);
            if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getUserInfo()!=null||uri.getPort()!=-1)throw new IllegalArgumentException();
            String path=uri.getPath();
            String pattern=switch(host){
                case "brand.naver.com"->"/[A-Za-z0-9_-]+/products/([0-9]{1,30})/?";
                case "shopping.naver.com"->"/(?:window-products/(?:department|brandfashion)|outlink/itemdetail)/([0-9]{1,30})/?";
                default->throw new IllegalArgumentException();
            };
            var m=java.util.regex.Pattern.compile(pattern).matcher(path);if(!m.matches())throw new IllegalArgumentException();return m.group(1);
        }catch(IllegalArgumentException ex){throw new InputValidationFailure("지원하는 네이버 공식몰의 상품 페이지 링크를 입력해 주세요.");}
    }
    public ChannelPreview preview(Long actor,String url){
        db.authorize(actor,true);long revision=preferences.get(actor).revision();String id=productId(url);
        var detail=gateway.inspect(ProcurementMall.NAVER_SMART_STORE,id,url.trim());
        var offer=new Offer(null,null,null,id,url,null,null,ProcurementMall.NAVER_SMART_STORE,null);
        if(NaverChannelPolicy.inspected(offer,detail).type()!=NaverChannelType.WINDOW)throw new InputValidationFailure("쇼핑윈도 판매채널을 확인하지 못했습니다.");
        var evidence=detail.storeEvidence();
        // Gateway의 본상품 ID 검사에 통과한 판매채널 근거만 사용한다.
        if(evidence==null||!"SELLER".equals(evidence.kind())||!"NAVER_CHANNEL".equals(evidence.namespace())||evidence.externalId()==null||evidence.externalId().isBlank()||evidence.externalId().length()>200||evidence.name()==null||evidence.name().isBlank())
            throw new InputValidationFailure("본상품의 판매채널을 확인하지 못했습니다. 상품 링크를 확인해 주세요.");
        return new ChannelPreview(id,evidence.externalId(),evidence.name(),revision);
    }
    public Store register(Long actor,StoreInput input){
        db.authorize(actor,true);
        if(input.mall()!=ProcurementMall.NAVER_SMART_STORE||!"BRAND_STORE".equals(input.kind())||input.retailer()!=null&&!input.retailer().isBlank()||input.sellerKey()!=null&&!input.sellerKey().isBlank())throw new InputValidationFailure("네이버 쇼핑윈도 공식몰 정보를 입력해 주세요.");
        var verified=preview(actor,input.productUrl());
        if(!verified.channelUid().equals(input.expectedChannelUid()))throw new InputValidationFailure("판매채널이 변경되었습니다. 다시 확인해 주세요.");
        return preferences.registerBrandStore(actor,input,verified);
    }
}
