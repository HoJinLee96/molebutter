package cc.ataglace.molebutter.media.api;
import java.util.List;

/** Read-only checks of bytes actually used by the editor. */
public interface ImageInspection {
    record Input(String role,String optionId,String imageId,String url,String assetId,boolean existing) {}
    record Check(String role,String optionId,String imageId,String url,String status,Integer width,Integer height,Long bytes,List<String> reasons,List<String> warnings) {}
    default void require(Long actor,List<Input> images){for(var check:inspect(actor,images))if(!"PASS".equals(check.status()))throw new cc.ataglace.molebutter.common.api.InputValidationFailure(String.join(" ",check.reasons()));}
    static List<Input> descriptionInputs(String html,String previous){
        var all=java.util.regex.Pattern.compile("<img\\b[^>]*?\\bsrc\\s*=\\s*(?:[\"']([^\"']*)[\"']|([^\\s>]+))",java.util.regex.Pattern.CASE_INSENSITIVE);var old=new java.util.HashSet<String>();var prior=all.matcher(previous==null?"":previous);while(prior.find())old.add(java.util.Objects.toString(prior.group(1),prior.group(2)).replace("&amp;","&"));
        var list=new java.util.ArrayList<Input>();String current=html==null?"":html;
        // Alternate browser sources cannot be silently skipped by the transmission guard.
        if(!current.equals(previous)&&java.util.regex.Pattern.compile("(?i)\\bsrcset\\s*=|background(?:-image)?\\s*:\\s*[^;>]*url\\s*\\(|\\bbackground\\s*=|<svg\\b|<picture\\b").matcher(current).find())throw new cc.ataglace.molebutter.common.api.InputValidationFailure("지원하지 않는 설명 이미지 참조가 있습니다. 이미지 블록 또는 img src로 입력해 주세요.");
        var matcher=all.matcher(html==null?"":html);int n=0;while(matcher.find()){String url=java.util.Objects.toString(matcher.group(1),matcher.group(2)).replace("&amp;","&");if(old.contains(url))continue;var asset=EditedImages.ASSET.matcher(url);list.add(new Input("DESCRIPTION",null,"description:"+n++,url,asset.matches()?asset.group(1):null,false));}return List.copyOf(list);
    }
    List<Check> inspect(Long actor,List<Input> images);
}
