package cc.ataglace.molebutter.marketplaceelevenst.internal;

import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import cc.ataglace.molebutter.marketplace.api.ElevenstProducts.*;
import static cc.ataglace.molebutter.marketplace.api.PartnerFormSupport.invalid;

/** The seller API publishes managed brands as a downloadable file, not a search endpoint. */
final class ElevenstReferenceData {
    private Path cachedPath;
    private long cachedSize=-1, cachedModified=-1;
    private List<Choice> cached=List.of();

    synchronized BrandPage brands(String file,String name,int page) {
        if(name==null||name.isBlank()||name.length()>200||name.codePoints().anyMatch(Character::isISOControl)||page<1||page>5000)
            throw invalid("브랜드 검색어와 페이지를 확인해 주세요.");
        String missing="11번가 관리 브랜드는 공식 CSV 파일로 제공됩니다. 서버에 코드 파일을 설정하면 검색할 수 있습니다. 현재는 브랜드명을 텍스트로 사용할 수 있습니다.";
        if(file==null||file.isBlank())return new BrandPage(List.of(),page,false,false,missing);
        try {
            Path path=Path.of(file).toRealPath();
            for(Path part:path)if(part.toString().equals("application-secret"))throw invalid("브랜드 코드 파일은 비밀 설정 디렉터리 밖에 보관해 주세요.");
            long size=Files.size(path),modified=Files.getLastModifiedTime(path).toMillis();
            if(size>40*1024*1024)throw invalid("브랜드 코드 파일은 40MiB 이하로 설정해 주세요.");
            if(!path.equals(cachedPath)||size!=cachedSize||modified!=cachedModified){
                byte[] bytes;try(var in=Files.newInputStream(path)){bytes=in.readNBytes(40*1024*1024+1);}if(bytes.length>40*1024*1024)throw invalid("브랜드 코드 파일은 40MiB 이하로 설정해 주세요.");var parsed=parse(bytes);
                cached=parsed;cachedPath=path;cachedSize=size;cachedModified=modified;
            }
            String query=name.strip().toLowerCase(Locale.ROOT);
            var found=cached.stream().filter(c->c.name().toLowerCase(Locale.ROOT).contains(query)||c.id().equals(query)).toList();
            int start=Math.min(found.size(),(page-1)*100),end=Math.min(found.size(),start+100);
            return new BrandPage(found.subList(start,end),page,end<found.size(),true,"공식 관리 브랜드 CSV 조회값입니다. 최신 파일 갱신 여부를 확인해 주세요.");
        }catch(NoSuchFileException absent){return new BrandPage(List.of(),page,false,false,missing);}
        catch(java.io.IOException|InvalidPathException failure){throw invalid("11번가 브랜드 코드 파일을 읽을 수 없습니다. 서버 설정과 파일 형식을 확인해 주세요.");}
    }

    static List<Choice> parse(byte[] bytes) {
        String text;
        try{text=decode(bytes,StandardCharsets.UTF_8);}catch(CharacterCodingException utf8){
            try{text=decode(bytes,Charset.forName("MS949"));}catch(CharacterCodingException bad){throw invalid("브랜드 CSV는 UTF-8 또는 CP949로 저장해 주세요.");}
        }
        if(text.startsWith("\ufeff"))text=text.substring(1);
        var rows=csv(text);if(rows.isEmpty())throw invalid("브랜드 CSV의 머리글을 확인해 주세요.");
        var header=rows.getFirst();int code=-1,name=-1;
        for(int n=0;n<header.size();n++){
            String h=header.get(n).strip().replace(" ","").toLowerCase(Locale.ROOT);
            if(Set.of("apiprdattrbrandcd","brandcode","브랜드코드").contains(h)){if(code>=0)throw invalid("브랜드 CSV 코드 열이 중복되었습니다.");code=n;}
            if(Set.of("brand","brandname","브랜드명").contains(h)){if(name>=0)throw invalid("브랜드 CSV 이름 열이 중복되었습니다.");name=n;}
        }
        if(code<0||name<0)throw invalid("브랜드 CSV에 apiPrdAttrBrandCd(브랜드코드), brand(브랜드명) 열이 필요합니다.");
        var out=new LinkedHashMap<String,Choice>();
        for(var row:rows.subList(1,rows.size())){
            if(row.stream().allMatch(String::isBlank))continue;
            if(row.size()!=header.size())throw invalid("브랜드 CSV 열 개수를 확인해 주세요.");
            String id=row.get(code).strip(),label=row.get(name).strip();
            if(!id.matches("[1-9][0-9]{0,29}")||label.isBlank()||label.length()>200||label.codePoints().anyMatch(Character::isISOControl))throw invalid("브랜드 CSV의 코드·이름을 확인해 주세요.");
            var choice=new Choice(id,label,null,true);var prior=out.putIfAbsent(id,choice);
            if(prior!=null&&!prior.equals(choice))throw invalid("브랜드 CSV에 서로 다른 이름의 중복 코드가 있습니다.");
        }
        return List.copyOf(out.values());
    }
    private static String decode(byte[] b,Charset c)throws CharacterCodingException{return c.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();}
    private static List<List<String>> csv(String text){
        var rows=new ArrayList<List<String>>();var row=new ArrayList<String>();var field=new StringBuilder();boolean quoted=false,closed=false;
        for(int n=0;n<text.length();n++){
            char c=text.charAt(n);
            if(quoted){if(c=='"'){if(n+1<text.length()&&text.charAt(n+1)=='"'){field.append('"');n++;}else{quoted=false;closed=true;}}else field.append(c);}
            else if(c==','||c=='\r'||c=='\n'){
                row.add(field.toString());field.setLength(0);closed=false;
                if(c!=','){if(c=='\r'&&n+1<text.length()&&text.charAt(n+1)=='\n')n++;rows.add(List.copyOf(row));row.clear();}
            }else if(c=='"'&&field.isEmpty()&&!closed)quoted=true;
            else{if(closed||c=='"')throw invalid("브랜드 CSV 따옴표 형식을 확인해 주세요.");field.append(c);}
            if(field.length()>10000||row.size()>100||rows.size()>500000)throw invalid("브랜드 CSV 처리 한도를 초과했습니다.");
        }
        if(quoted)throw invalid("브랜드 CSV 따옴표 형식을 확인해 주세요.");
        if(!field.isEmpty()||!row.isEmpty()||closed){row.add(field.toString());rows.add(List.copyOf(row));}
        return rows;
    }
    List<Choice> deliveryCompanies(){return reference("delivery-companies");}
    List<Choice> origins(){return reference("origins");}
    private List<Choice> reference(String kind){
        try(var in=getClass().getResourceAsStream("/elevenst-"+kind+".json")){
            if(in==null)throw new IllegalStateException("Missing delivery reference");
            var root=new tools.jackson.databind.ObjectMapper().readTree(in.readAllBytes());var out=new ArrayList<Choice>();
            for(var row:root)out.add(new Choice(row.path("id").asString(),row.path("name").asString(),null,true));return List.copyOf(out);
        }catch(java.io.IOException failure){throw new IllegalStateException("Invalid delivery reference",failure);}
    }
}
