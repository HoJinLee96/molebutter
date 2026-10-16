package cc.ataglace.molebutter.media.api;

import java.util.regex.Pattern;

/** Private asset references stay in drafts; only verified server edits become public wire URLs. */
public final class EditedImages {
    private EditedImages() {}
    public static final Pattern ASSET=Pattern.compile("/api/coupang/assets/([0-9a-fA-F-]{36})(?![0-9a-fA-F-])");
    public static String description(Long actor,String html,String previous,R2Images r2,ImageAssets assets){
        var matcher=ASSET.matcher(html);var out=new StringBuffer();var preserved=new StringBuilder(previous==null?"":previous);
        while(matcher.find()){
            String id=matcher.group(1);if(assets.editedMetadata(actor,id)==null)throw new cc.ataglace.molebutter.common.api.InputValidationFailure("기존 로컬 이미지를 R2 이미지로 다시 선택해 주세요.");String url=assets.submissionUrl(actor,id);
            matcher.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(url));preserved.append("<img src=\"").append(url).append("\">");
        }
        matcher.appendTail(out);String result=out.toString();r2.validateDescription(actor,result,preserved.toString());return result;
    }
}
