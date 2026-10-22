package cc.ataglace.molebutter.coupang.internal;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.coupang.api.CoupangSearchTags;

@Service
public final class DefaultCoupangSearchTags implements CoupangSearchTags {
    private static final Pattern MALE=Pattern.compile("남성|남자|남아|\\bMEN(?:'S)?\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern FEMALE=Pattern.compile("여성|여자|여아|\\bWOMEN(?:'S)?\\b",Pattern.CASE_INSENSITIVE);
    private static final Pattern TYPES=Pattern.compile("명함\\s*지갑|카드\\s*지갑|반지갑|중지갑|장지갑|크로스\\s*백|미니\\s*백|토트\\s*백|백팩|호보\\s*백|숄더\\s*백|버킷\\s*백|핸드백|클러치|지갑|가방|벨트");
    private static String clean(String s){return Normalizer.normalize(Objects.toString(s,""),Normalizer.Form.NFKC).replaceAll("\\s+"," ").trim();}
    private static void check(String s,int max){if(s!=null&&(s.length()>max||s.codePoints().anyMatch(c->Character.isISOControl(c)&&!Character.isWhitespace(c))))throw invalid();}
    private static InputValidationFailure invalid(){return new InputValidationFailure("검색어 생성에 사용할 제목·색상·상품 근거를 확인해 주세요.");}
    private static List<String> words(String s){return Arrays.stream(clean(s).replaceAll("[^\\p{L}\\p{M}\\p{N} !@#$%^&*+;:’.'-]"," ").split("\\s+")).filter(v->v.codePoints().anyMatch(Character::isLetterOrDigit)).toList();}
    private static List<String> types(String s){var m=TYPES.matcher(clean(s));var out=new LinkedHashSet<String>();while(m.find())out.add(m.group().replace(" ",""));return List.copyOf(out);}
    @Override public Result generate(Input input){
        if(input==null||input.context()==null)throw invalid();var c=input.context();
        check(input.title(),500);check(input.brand(),100);check(c.gender(),200);check(c.material(),500);check(c.category(),500);
        if(c.colors()==null||c.colors().size()>32)throw invalid();c.colors().forEach(v->{if(v==null)throw invalid();check(v,100);});
        String title=clean(input.title());var candidates=new ArrayList<String>(words(title));
        c.colors().stream().filter(v->!clean(v).matches(".*(?:상세\\s*페이지|참조|확인|해당\\s*없음|선택).*" )).forEach(v->candidates.addAll(words(v)));
        if(Set.of("루이까또즈","LOUISQUATORZE").contains(clean(input.brand()).replace(" ","").toUpperCase(Locale.ROOT)))candidates.addAll(List.of("루이까또쯔","루이까도쯔","루이까도즈"));
        var kinds=types(title);if(kinds.isEmpty())kinds=types(c.category());
        boolean wallet=kinds.stream().anyMatch(k->k.contains("지갑")),bag=kinds.stream().anyMatch(k->k.endsWith("백")||k.equals("가방")||k.equals("백팩")||k.equals("클러치"));
        if(wallet){candidates.addAll(List.of("지갑","카드"));if(kinds.contains("카드지갑")||kinds.contains("명함지갑"))candidates.add("명함");}
        if(bag)candidates.addAll(List.of("가방","핸드백","백"));
        candidates.addAll(kinds);
        String gender=(MALE.matcher(title).find()||FEMALE.matcher(title).find())?title:clean(c.gender());
        boolean male=MALE.matcher(gender).find(),female=FEMALE.matcher(gender).find();
        if(male!=female)candidates.addAll(male?List.of("남자","남성","아버지"):List.of("여자","여성","어머니"));
        String material=Pattern.compile("가죽|우피|나일론|NYLON|캔버스|CANVAS|폴리에스터|POLYESTER|(?:^|[\\s:;,(])면(?:[\\s0-9%/,)]|$)|COTTON",Pattern.CASE_INSENSITIVE).matcher(title).find()?title:clean(c.material());
        if(!Pattern.compile("합성|인조|나일론|NYLON|캔버스|CANVAS|폴리에스터|POLYESTER",Pattern.CASE_INSENSITIVE).matcher(material).find()){
            if(Pattern.compile("소가죽|우피|천연\\s*가죽\\s*\\(소\\)").matcher(material).find())candidates.addAll(List.of("소가죽","가죽"));
            else if(material.contains("가죽"))candidates.add("가죽");
        }
        if(male&&!female&&(wallet||bag))candidates.addAll(List.of("회사원","직장인","정장"));
        if(wallet||bag)candidates.addAll(List.of("선물","캐주얼","데일리","모던","클래식"));
        var seen=new HashSet<String>();var tags=new ArrayList<String>();var excluded=new ArrayList<Excluded>();
        for(String candidate:candidates){String value=clean(candidate);if(value.isBlank()||!seen.add(value.toLowerCase(Locale.ROOT)))continue;
            if(value.codePointCount(0,value.length())>20)excluded.add(new Excluded(value,"OVER_LENGTH"));
            else if(tags.size()==20)excluded.add(new Excluded(value,"OVER_COUNT"));else tags.add(value);
        }
        return new Result(List.copyOf(tags),List.copyOf(excluded));
    }
}
