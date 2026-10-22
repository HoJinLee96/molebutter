package cc.ataglace.molebutter.coupang.api;
import java.util.*;
import java.text.Normalizer;
import java.util.regex.Pattern;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
/** Local selection only. No persistence or seller HTTP calls. */
public interface CoupangBrandLogos {
    record Input(String title) {}
    record Result(String status,CoupangRegistrationDefaults.BrandLogo logo,List<String> matches,String accountKey,long revision) {}
    Result select(Long actor,Input input);
    static String normalize(String value){return Normalizer.normalize(Objects.toString(value,""),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+"," ").trim();}
    static Result match(String title,CoupangRegistrationDefaults.Settings settings){
        if(title==null||title.length()>500||title.codePoints().anyMatch(Character::isISOControl))throw new InputValidationFailure("브랜드 로고를 찾을 노출상품명을 확인해 주세요.");
        var found=new ArrayList<CoupangRegistrationDefaults.BrandLogo>();String text=normalize(title);
        for(var logo:settings.brandLogos()){
            var names=new ArrayList<String>();names.add(logo.brandName());if(logo.aliases()!=null)names.addAll(logo.aliases());
            if(names.stream().map(CoupangBrandLogos::normalize).filter(v->!v.isBlank()).anyMatch(v->Pattern.compile("(?<![\\p{L}\\p{N}\\p{M}])"+Pattern.quote(v)+"(?![\\p{L}\\p{N}\\p{M}])").matcher(text).find()))found.add(logo);
        }
        return new Result(found.size()==1?"MATCHED":found.isEmpty()?"NOT_FOUND":"AMBIGUOUS",found.size()==1?found.getFirst():null,found.stream().map(CoupangRegistrationDefaults.BrandLogo::brandName).toList(),settings.accountKey(),settings.revision());
    }
}
