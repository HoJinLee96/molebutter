package cc.ataglace.molebutter.media.internal;

import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.common.api.OperationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.media.api.R2Images;
import cc.ataglace.molebutter.storage.api.*;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

@Service
public final class R2ImageLibrary implements R2Images {
    private final ObjectStorage storage;
    private final BusinessAccess access;
    private static final Pattern IMAGE=Pattern.compile("<img\\b[^>]*?\\bsrc\\s*=\\s*(?:[\"']([^\"']*)[\"']|([^\\s>]+))",Pattern.CASE_INSENSITIVE|Pattern.DOTALL);
    public R2ImageLibrary(ObjectStorage storage,BusinessAccess access){this.storage=storage;this.access=access;}
    public Page list(Long actor,String productCode,String cursor){
        access.productActor(actor,true);String code=code(productCode),prefix="products/"+code+"/";
        try{
            String token=decodeCursor(code,cursor);var page=storage.list(StorageArea.PUBLIC,prefix,token,100);
            var entries=new ArrayList<Entry>();
            var folders=new TreeSet<String>();
            for(var object:page.objects()){
                String key=object.key();if(!key.startsWith(prefix))continue;
                String path=key.substring(prefix.length());
                for(int slash=path.indexOf('/');slash>=0;slash=path.indexOf('/',slash+1))if(slash>0)folders.add(path.substring(0,slash));
                if(!key.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png)$"))continue;
                String relative=key.substring(prefix.length()),folder=relative.contains("/")?relative.substring(0,relative.indexOf('/')):"";
                if(!Set.of("official","processed","original").contains(folder))folder="other";
                entries.add(new Entry(key,url(key),folder,object.size(),object.lastModified()));
            }
            return new Page(code,List.copyOf(entries),encodeCursor(code,page.nextContinuationToken()),List.copyOf(folders));
        }catch(StorageException e){throw failure(e);}
    }
    public Image resolve(Long actor,String value){return read(actor,value).image();}
    public Content read(Long actor,String value){
        access.productActor(actor,true);
        try{
            String key=key(value);var head=storage.head(StorageArea.PUBLIC,key).orElseThrow(()->invalid("이미지가 삭제되었거나 없습니다. R2에서 다시 선택해 주세요."));
            if(head.size()<1||head.size()>LocalImageAssets.MAX_BYTES)throw invalid("이미지는 10MiB 이하로 선택해 주세요.");
            var stored=storage.get(StorageArea.PUBLIC,key).orElseThrow(()->invalid("이미지가 삭제되었거나 없습니다. R2에서 다시 선택해 주세요."));
            byte[] bytes=stored.content();var image=LocalImageAssets.inspect(bytes);
            return new Content(new Image(key,url(key),image.mimeType(),image.width(),image.height(),bytes.length),bytes);
        }catch(StorageException e){throw failure(e);}
    }
    public void validateDescription(Long actor,String html,String previousHtml){
        access.productActor(actor,true);String value=Objects.requireNonNullElse(html,"");
        if(value.equals(Objects.requireNonNullElse(previousHtml,"")))return;
        if(value.contains("/api/marketplaces/assets/")||value.contains("/marketplace-images/")||value.contains("blob:"))throw invalid("기존 로컬 설명 이미지를 R2 이미지로 다시 선택해 주세요.");
        Set<String> previous=urls(previousHtml);for(String image:urls(value))if(!previous.contains(image))resolve(actor,image);
    }
    private static Set<String> urls(String html){var result=new HashSet<String>();var matcher=IMAGE.matcher(Objects.requireNonNullElse(html,""));while(matcher.find())result.add((matcher.group(1)==null?matcher.group(2):matcher.group(1)).replace("&amp;","&"));return result;}
    private String key(String value){
        try{
            URI uri=URI.create(value);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw invalid("설정된 R2 공개 이미지 주소를 입력해 주세요.");
            String path=uri.getPath();int at=path.indexOf("/products/");if(at<0)throw invalid("R2 상품 이미지 경로를 확인해 주세요.");
            String key=path.substring(at+1);String[] parts=key.split("/",-1);if(parts.length<3||!parts[1].equals(code(parts[1])))throw invalid("R2 상품코드를 확인해 주세요.");
            for(String part:parts)if(part.isBlank()||part.equals(".")||part.equals("..")||part.contains("\\")||part.chars().anyMatch(c->c<32||c==127))throw invalid("R2 이미지 경로를 확인해 주세요.");
            URI canonical=URI.create(url(key));if(!uri.equals(canonical)&&!uri.toASCIIString().equals(canonical.toASCIIString()))throw invalid("설정된 R2 공개 이미지 주소를 입력해 주세요.");
            return key;
        }catch(IllegalArgumentException|NullPointerException e){throw invalid("설정된 R2 공개 이미지 주소를 입력해 주세요.");}
    }
    private String url(String key){return storage.publicUrl(StorageArea.PUBLIC,key).orElseThrow(()->invalid("R2 공개 이미지 주소 설정을 확인해 주세요.")).toASCIIString();}
    static String code(String value){String code=Objects.requireNonNullElse(value,"").trim().toUpperCase(Locale.ROOT);if(!code.matches("[A-Z0-9_-]{4,40}"))throw invalid("상품코드는 영문·숫자·밑줄·하이픈 4~40자로 입력해 주세요.");return code;}
    private static String encodeCursor(String code,String token){return token==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString((code+"\n"+token).getBytes(StandardCharsets.UTF_8));}
    private static String decodeCursor(String code,String cursor){if(cursor==null||cursor.isBlank())return null;try{if(cursor.length()>8192)throw new IllegalArgumentException();String value=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8);if(!value.startsWith(code+"\n")||value.length()==code.length()+1)throw new IllegalArgumentException();return value.substring(code.length()+1);}catch(IllegalArgumentException e){throw invalid("조회 상품코드에 맞는 다음 페이지를 다시 조회해 주세요.");}}
    private static RuntimeException failure(StorageException e){return e.code()==StorageException.Code.NOT_CONFIGURED?new OperationFailure("R2 연결 설정이 필요합니다."):new OperationFailure("R2 이미지를 읽지 못했습니다. 잠시 후 다시 조회해 주세요.");}
    private static InputValidationFailure invalid(String message){return new InputValidationFailure(message);}
}
