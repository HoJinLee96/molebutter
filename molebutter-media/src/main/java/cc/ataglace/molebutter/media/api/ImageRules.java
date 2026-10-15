package cc.ataglace.molebutter.media.api;

import java.util.*;

/** File rules shared by validation and provider payload construction; recommendations are separate. */
public final class ImageRules {
    private ImageRules() {}
    public static List<String> errors(String market,String role,String mime,int width,int height,long bytes){
        return errors(market,role,mime,width,height,bytes,false);
    }
    public static List<String> errors(String market,String role,String mime,int width,int height,long bytes,boolean retained){
        var errors=new ArrayList<String>();
        if(width<1||height<1||bytes<1)errors.add("이미지 파일을 읽지 못했습니다.");
        if(bytes>10*1024*1024||(long)width*height>25_000_000L)errors.add("앱 처리 제한: 10MiB·2,500만 픽셀 이하 이미지를 선택해 주세요.");
        if(!Set.of("image/jpeg","image/png").contains(mime)&&!(retained&&market.equals("ELEVENST")&&mime.equals("image/webp")))errors.add("앱 처리 형식: JPG·PNG 이미지를 선택해 주세요.");
        if("COUPANG".equals(market)&&!"DESCRIPTION".equals(role)&&
                (bytes>3*1024*1024||width!=height||width<500||width>5000))
            errors.add("쿠팡 상품 이미지는 3MiB 이하, 500~5000px 정사각형이어야 합니다.");
        if(market.equals("ELEVENST")&&!role.equals("DESCRIPTION")&&(width<600||height<600))errors.add("11번가 상품 이미지는 가로·세로 600px 이상이어야 합니다.");
        if(market.equals("LOTTEON")&&!role.equals("DESCRIPTION")&&!role.endsWith("_PORTRAIT")&&width!=height)errors.add("롯데ON 정사각형 유형(IMG_SQRE)은 가로·세로가 같아야 합니다. 세로형은 IMG_LNTH를 선택해 주세요.");
        if(market.equals("LOTTEON")&&role.endsWith("_PORTRAIT")&&height<width)errors.add("롯데ON 세로형(IMG_LNTH)은 높이가 너비 이상이어야 합니다.");
        return List.copyOf(errors);
    }
}
