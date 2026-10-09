package cc.ataglace.molebutter.marketplace.internal;

import cc.ataglace.molebutter.media.api.ImageAssets;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Component;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;


@Component
final class DraftValidation {
    private final CoupangProductDocuments coupang;
    private final NaverProductDocuments naver;
    private final ImageAssets assets;
    DraftValidation(CoupangProductDocuments coupang,NaverProductDocuments naver,ImageAssets assets){this.coupang=coupang;this.naver=naver;this.assets=assets;}
    Validation validate(Long actor,Document d,String requestId){
        var errors=new ArrayList<Issue>();var unverified=new ArrayList<Issue>();
        var nativeConfig=d.markets().get(Market.NAVER);
        if(d.selectedMarkets().equals(List.of(Market.NAVER))&&nativeConfig!=null&&nativeConfig.naver()!=null&&nativeConfig.naver().editorInput()!=null){
            // Native options hold additional prices and NONE has no option rows. The common draft
            // rules do not describe this form; publish validation belongs to its prepared wire body.
            naver.normalize(nativeConfig.naver().editorInput());
            return new Validation(false,List.of(),List.of(new Issue("NAVER","markets.NAVER.naver.editorInput","스마트스토어 전용 저장 확인에서 필수값·카테고리·이미지 규격을 검증합니다.")));
        }
        require(errors,null,"common.productCode",d.common().productCode(),"상품코드");require(errors,null,"common.name",d.common().name(),"상품명");
        if(d.options().isEmpty())issue(errors,null,"options","옵션을 한 개 이상 입력해 주세요.");
        if(d.selectedMarkets().isEmpty())issue(errors,null,"selectedMarkets","마켓을 한 개 이상 선택해 주세요.");
        var names=new HashSet<String>();
        for(int i=0;i<d.options().size();i++){
            var o=d.options().get(i);String p="options."+i;
            require(errors,null,p+".name",o.name(),"옵션명");if(!o.name().isBlank()&&!names.add(o.name().trim()))issue(errors,null,p+".name","옵션명이 중복되었습니다.");
            number(errors,null,p+".price",o.price(),"판매가",9_007_199_254_740_991L);
            if(d.stockMode()==StockMode.OPTION)number(errors,null,p+".quantity",o.quantity(),"판매 수량",9_007_199_254_740_991L);
        }
        if(d.stockMode()==StockMode.PRODUCT)number(errors,null,"productQuantity",d.productQuantity(),"상품 판매 수량",9_007_199_254_740_991L);
        if(d.media().images().isEmpty())issue(errors,null,"media.images","대표 이미지를 추가해 주세요.");
        if(d.media().contents().stream().noneMatch(c->!c.value().isBlank()))issue(errors,null,"media.contents","상품 설명을 입력해 주세요.");
        Map<String,ImageAssets.Asset> metadata=new HashMap<>();
        for(var id:MarketplaceDocuments.assetIds(d))try{metadata.put(id,assets.metadata(actor,id));}catch(cc.ataglace.molebutter.common.api.InputValidationFailure e){issue(errors,null,"media.images","저장된 이미지를 확인해 주세요.");}
        for(var market:d.selectedMarkets()){
            var m=d.markets().get(market);String prefix="markets."+market;
            if(m==null){issue(errors,market,prefix,"마켓 설정을 입력해 주세요.");continue;}
            if(d.stockMode()==StockMode.PRODUCT&&d.options().size()>1)issue(unverified,market,"stockMode","상품 전체 수량을 옵션별 수량으로 배분하는 규칙 확인이 필요합니다.");
            require(errors,market,prefix+".categoryCode",m.categoryCode(),"카테고리");
            int max=100;
            if(name(d,m).isBlank()||name(d,m).codePointCount(0,name(d,m).length())>max)issue(errors,market,prefix+".overrides.name","상품명은 1~"+max+"자로 입력해 주세요.");
            for(int n=0;n<d.options().size();n++){
                var o=d.options().get(n);var override=override(m,o);String path=prefix+".overrides.options."+n;
                if(override!=null){number(errors,market,path+".price",inherited(override.price(),o.price()),"판매가",9_007_199_254_740_991L);if(d.stockMode()==StockMode.OPTION)number(errors,market,path+".quantity",inherited(override.quantity(),o.quantity()),"판매 수량",9_007_199_254_740_991L);}
                var images=images(d,m,o);
                if(images.stream().filter(Image::representative).count()!=1)issue(errors,market,"media.images","옵션 "+(n+1)+"의 대표 이미지를 한 개 지정해 주세요.");
                int imageLimit=market==Market.GMARKET||market==Market.AUCTION?15:10;
                if((market==Market.COUPANG?images.stream().filter(i->!i.imageType().equals("USED_PRODUCT")).count():images.size())>imageLimit)issue(errors,market,"media.images","옵션 이미지는 "+imageLimit+"개 이하로 지정해 주세요.");
            }
            switch(market){
                case COUPANG->{marketNumbers(d,m,market,9_007_199_254_740_991L,99999,errors);coupang.validate(actor,d,m,requestId,metadata,errors,unverified);}
                case NAVER->{
                    marketNumbers(d,m,market,999_999_990L,99_999_999L,errors);
                    var n=m.naver();
                    require(errors,market,prefix+".naver.status",n==null?null:n.status(),"원상품 판매 상태");
                    if(n!=null){choice(errors,market,prefix+".naver.status",n.status(),Set.of("SALE","SUSPENSION"));choice(errors,market,prefix+".naver.saleType",n.saleType(),Set.of("NEW","OLD"));}
                    require(errors,market,prefix+".naver.originCode",n==null?null:n.originCode(),"원산지 코드");
                    require(errors,market,prefix+".naver.afterServiceTelephone",n==null?null:n.afterServiceTelephone(),"A/S 전화번호");
                    require(errors,market,"common.afterService",d.common().afterService(),"A/S 안내");
                    if(n==null||n.naverShoppingRegistration()==null)issue(errors,market,prefix+".naver.naverShoppingRegistration","네이버 쇼핑 등록 여부를 선택해 주세요.");
                    require(errors,market,prefix+".naver.channelProductDisplayStatusType",n==null?null:n.channelProductDisplayStatusType(),"채널 전시 상태");
                    if(n!=null)choice(errors,market,prefix+".naver.channelProductDisplayStatusType",n.channelProductDisplayStatusType(),Set.of("ON","SUSPENSION"));
                    if(!m.categoryCode().isBlank()&&!m.categoryCode().matches("[0-9]+"))issue(errors,market,prefix+".categoryCode","카테고리 코드를 숫자로 입력해 주세요.");
                    issue(unverified,market,prefix+".categoryCode","네이버 카테고리 규격·계정 정책 확인이 필요합니다.");
                    issue(unverified,market,"media.images","네이버 이미지 업로드 API 반환 URL 확인이 필요합니다.");
                }
                case LOTTEON,ELEVENST->issue(unverified,market,prefix,"마켓 상품 API 연결 준비 중입니다.");
                case GMARKET,AUCTION->{
                    esmNumbers(d,m,market,errors,unverified);
                    if(m.esm()!=null&&!MarketplaceDocuments.blank(m.esm().siteId())&&!m.esm().siteId().equals(market==Market.GMARKET?"2":"1"))issue(errors,market,prefix+".esm.siteId","마켓 사이트 구분을 확인해 주세요.");
                    var seen=new HashSet<String>();for(var o:d.options())for(var image:images(d,m,o))if(seen.add(image.id())){
                        if(image.assetId()==null)issue(unverified,market,"media.images","ESM URL 이미지의 크기·용량 규격 확인이 필요합니다.");
                        else if(image.representative()&&metadata.containsKey(image.assetId())){var a=metadata.get(image.assetId());if(a.width()<600||a.height()<600||a.bytes()>2_000_000||!Set.of("image/png","image/jpeg").contains(a.mimeType()))issue(errors,market,"media.images","ESM 대표 이미지는 600×600px 이상, 2MB 이하 JPG·PNG여야 합니다.");}
                    }
                    issue(unverified,market,prefix+".categoryCode","ESM 카테고리 규격·계정 정책 확인이 필요합니다.");
                    issue(unverified,market,prefix+".overrides.name","ESM 상품명 100byte 제한의 인코딩 확인이 필요합니다.");
                }
            }
        }
        return new Validation(errors.isEmpty()&&unverified.isEmpty(),List.copyOf(errors),List.copyOf(unverified));
    }
    private static String inherited(String override,String common){return override==null?common:override;}
    private static OptionOverride override(MarketConfig market,Option option){return market.overrides()==null?null:market.overrides().options().stream().filter(o->o.optionId().equals(option.id())).findFirst().orElse(null);}
    private static String name(Document d,MarketConfig m){return m.overrides()==null?d.common().name():inherited(m.overrides().name(),d.common().name());}
    private static List<Image> images(Document d,MarketConfig m,Option o){return d.media().images().stream().filter(i->i.optionId()==null||i.optionId().equals(o.id())).filter(i->m.overrides()==null||m.overrides().imageIds()==null||m.overrides().imageIds().contains(i.id())).toList();}
    private static void marketNumbers(Document d,MarketConfig m,Market market,long maxPrice,long maxStock,List<Issue> errors){
        for(int n=0;n<d.options().size();n++){
            var o=d.options().get(n);var override=override(m,o);String path="markets."+market+".overrides.options."+n;
            number(errors,market,path+".price",override==null?o.price():inherited(override.price(),o.price()),"판매가",maxPrice);
            if(d.stockMode()==StockMode.OPTION)number(errors,market,path+".quantity",override==null?o.quantity():inherited(override.quantity(),o.quantity()),"판매 수량",maxStock);
        }
        if(d.stockMode()==StockMode.PRODUCT)number(errors,market,"productQuantity",d.productQuantity(),"상품 판매 수량",maxStock);
    }
    private static void esmNumbers(Document d,MarketConfig m,Market market,List<Issue> errors,List<Issue> unverified){
        BigInteger total=BigInteger.ZERO;boolean complete=true;
        for(int n=0;n<d.options().size();n++){
            var o=d.options().get(n);var override=override(m,o);String path="markets."+market+".overrides.options."+n;String price=override==null?o.price():inherited(override.price(),o.price());
            if(!integer(price,999_999_999L)||Long.parseLong(price)<10||Long.parseLong(price)%10!=0)issue(errors,market,path+".price","ESM 판매가는 10원 이상 10억 미만의 10원 단위로 입력해 주세요.");
            if(d.stockMode()==StockMode.OPTION){String quantity=override==null?o.quantity():inherited(override.quantity(),o.quantity());if(!integer(quantity,99999)){issue(errors,market,path+".quantity","ESM 옵션 수량은 0~99999의 정수로 입력해 주세요.");complete=false;}else{long count=Long.parseLong(quantity);total=total.add(BigInteger.valueOf(count));if(count==0)issue(unverified,market,path+".quantity","ESM 0개 옵션의 품절 상태 연결 확인이 필요합니다.");}}
        }
        if(d.stockMode()==StockMode.PRODUCT){if(!integer(d.productQuantity(),99999)||Long.parseLong(d.productQuantity())<1)issue(errors,market,"productQuantity","ESM 상품 수량은 1~99999로 입력해 주세요.");}
        else if(complete&&(total.compareTo(BigInteger.ONE)<0||total.compareTo(BigInteger.valueOf(99999))>0))issue(errors,market,"options","ESM 옵션 재고 합산 상품 수량은 1~99999여야 합니다.");
    }
    private static LocalDateTime date(List<Issue> e,Market m,String path,String v){require(e,m,path,v,"판매 일시");if(MarketplaceDocuments.blank(v))return null;try{var d=LocalDateTime.parse(v);if(d.getYear()>2099)throw new IllegalArgumentException();return d;}catch(RuntimeException x){issue(e,m,path,"2099년 이하의 유효한 판매 일시를 입력해 주세요.");return null;}}
    private static void choice(List<Issue> e,Market m,String path,String value,Set<String> allowed){if(!MarketplaceDocuments.blank(value)&&!allowed.contains(value))issue(e,m,path,"선택값을 확인해 주세요.");}
    private static boolean integer(String v,long max){if(v==null||!v.matches("[0-9]{1,16}"))return false;return new BigInteger(v).compareTo(BigInteger.valueOf(max))<=0;}
    private static void number(List<Issue> e,Market m,String path,String v,String name,long max){if(!integer(v,max))issue(e,m,path,name+"은 0~"+max+"의 정수로 입력해 주세요.");}
    private static void require(List<Issue> e,Market m,String path,String v,String name){if(MarketplaceDocuments.blank(v))issue(e,m,path,name+"을 입력해 주세요.");}
    private static void issue(List<Issue> e,Market m,String path,String message){e.add(new Issue(m==null?null:m.name(),path,message));}
}
