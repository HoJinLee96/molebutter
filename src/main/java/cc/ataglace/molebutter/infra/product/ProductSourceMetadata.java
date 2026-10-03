package cc.ataglace.molebutter.infra.product;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.Mall;

/** 자동 옵션 조회는 실제 상품 URL의 호스트와 확인된 경로 규칙을 사용한다. */
public final class ProductSourceMetadata {
    private ProductSourceMetadata() {}
    static final Map<Mall,List<String>> HOSTS=Map.of(
        Mall.LFMALL,List.of("lfmall.co.kr"),Mall.HAZZYS,List.of("hazzys.com"),
        Mall.NAVER_SMART_STORE,List.of("smartstore.naver.com","brand.naver.com","shopping.naver.com"),
        Mall.LOTTE_ON,List.of("lotteon.com"),Mall.LOTTE_IMALL,List.of("lotteimall.com"),
        Mall.HI_THEHYUNDAI,List.of("hi.thehyundai.com","www.thehyundai.com"),Mall.HMALL,List.of("hmall.com"));

    public static Mall mall(String url) {
        try {
            URI uri=URI.create(url);
            if(!isWebUrl(uri)||uri.getPort()!=-1)return null;
            String host=uri.getHost().toLowerCase(Locale.ROOT);
            // cr/search.shopping.naver.com 같은 공통 중계 주소는 스마트스토어 상품 주소가 아니다.
            if(host.equals("shopping.naver.com")||host.endsWith(".shopping.naver.com")) {
                return host.equals("shopping.naver.com")&&!productId(Mall.NAVER_SMART_STORE,url,"").isBlank()
                    ? Mall.NAVER_SMART_STORE : null;
            }
            return HOSTS.entrySet().stream().filter(e->e.getValue().stream().anyMatch(h->host.equals(h)||host.endsWith("."+h)))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        } catch(Exception e) {return null;}
    }

    public static boolean isWebUrl(URI uri) {
        return ("https".equalsIgnoreCase(uri.getScheme())||"http".equalsIgnoreCase(uri.getScheme()))
            && uri.getHost()!=null && uri.getRawUserInfo()==null;
    }

    public static List<Mall> supportedMalls() {
        return Arrays.stream(Mall.values()).filter(HOSTS::containsKey).toList();
    }

    /** 이름은 확인 필요 후보를 보존하는 용도일 뿐, URL 검증이나 자동 연결의 근거로 쓰지 않는다. */
    public static boolean isSupportedName(String name) {
        if(name==null)return false;
        String normalized=name.replaceAll("\\s+","").toLowerCase(Locale.ROOT);
        if(supportedMalls().stream().anyMatch(m->m.getDisplayName().toLowerCase(Locale.ROOT).equals(normalized)))return true;
        return Set.of("lfmall","hazzys","스마트스토어","네이버스마트스토어","롯데on","lotteon","lotteimall","더현대닷컴","현대h몰","hmall").contains(normalized);
    }

    public static String productId(Mall mall,String url,String supplied) {
        // 롯데온의 판매 옵션 API는 pdNo가 아닌 링크에 명시된 sitmNo를 사용한다.
        if(mall==Mall.LOTTE_ON)try {String sku=query(URI.create(url),"sitmNo");if(sku.matches("[A-Za-z0-9_-]{1,100}"))return sku;}catch(Exception ignored){}
        String provided=supplied!=null&&supplied.matches("[A-Za-z0-9_-]{1,100}")?supplied:"";
        if(mall==null)return provided;
        String linked="";
        try {
            URI uri=URI.create(url);
            String value=switch(mall) {
                case LFMALL -> first(query(uri,"PROD_CD"),after(uri,"app/product"));
                case HAZZYS -> query(uri,"PROD_CD");
                case LOTTE_ON -> query(uri,"sitmNo");
                case LOTTE_IMALL -> query(uri,"goods_no");
                case HMALL -> query(uri,"slitmCd");
                case HI_THEHYUNDAI -> after(uri,"product");
                case NAVER_SMART_STORE -> first(after(uri,"products"),after(uri,"outlink/itemdetail"),
                    after(uri,"window-products/department"),after(uri,"window-products/brandfashion"));
            };
            linked=value.matches("[A-Za-z0-9_-]{1,100}")?value:"";
        } catch(Exception ignored) {}
        // 같은 종류의 상품 번호가 서로 다르면 URL·가격과 다른 상품을 연결하지 않는다.
        if(!linked.isBlank()&&!provided.isBlank()&&!linked.equals(provided))return "";
        return linked.isBlank()?provided:linked;
    }
    private static String query(URI uri,String key) {
        if(uri.getRawQuery()==null)return "";
        for(String part:uri.getRawQuery().split("&")) {
            String[] pair=part.split("=",2);
            if(pair.length==2&&pair[0].equalsIgnoreCase(key))return URLDecoder.decode(pair[1],StandardCharsets.UTF_8);
        }
        return "";
    }
    private static String after(URI uri,String marker) {
        String path=uri.getPath(),prefix="/"+marker+"/";
        if(path==null)return "";
        int i=path.indexOf(prefix);return i<0?"":path.substring(i+prefix.length()).split("/",2)[0];
    }
    private static String first(String... values) {return Arrays.stream(values).filter(s->!s.isBlank()).findFirst().orElse("");}
    public static String imageUrl(String value) {
        if(value==null||value.isBlank())return null;
        try {
            URI uri=URI.create(value.startsWith("//")?"https:"+value:value);
            if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getPort()!=-1||value.length()>2000)return null;
            String normalized=uri.toASCIIString();
            return normalized.length()<=2000?normalized:null;
        } catch(Exception e) {return null;}
    }
}
