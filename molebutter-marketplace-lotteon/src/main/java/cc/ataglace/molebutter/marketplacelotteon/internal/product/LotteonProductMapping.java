package cc.ataglace.molebutter.marketplacelotteon.internal.product;

import java.util.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.PartnerFormSupport.*;

/** SL V1 product mapping; identifiers are taken only from a server observation. */
final class LotteonProductMapping {
    private final ObjectMapper json;
    LotteonProductMapping(ObjectMapper json){this.json=json;}
    static final Map<String,String> COMMON=Map.of("name","spdNm","code","epdNo","manufacturer","mfcrNm","model","mdlNo");
    private static PartnerEditor.Field field(String key,String label,String section,boolean required){return new PartnerEditor.Field(key,label,section,"text",required,List.of());}
    private static List<String> choices(String key){return switch(key){case "slTypCd"->List.of("GNRL");case "pdTypCd"->List.of("GNRL_GNRL");case "pdStatCd"->List.of("NEW");case "dvPdTypCd"->List.of("GNRL");case "tdfDvsCd"->List.of("01","02","03","04");case "ageLmtCd"->List.of("0","15","19");default->List.of();};}
    static PartnerEditor.Schema schema(){
        var fields=new ArrayList<PartnerEditor.Field>();
        String[][] groups={
            {"상품 정보","spdNm:상품명","epdNo:판매자 상품코드","scatNo:표준 카테고리","lfDcatNo:전시 카테고리","slTypCd:판매 유형","pdTypCd:상품 유형","oplcCd:원산지","tdfDvsCd:과세 구분","slStrtDttm:판매 시작일시","slEndDttm:판매 종료일시","ageLmtCd:연령 제한","pdStatCd:상품 상태"},
            {"배송·반품","dvProcTypCd:배송 처리 유형","dvPdTypCd:배송 상품 유형","dvRgsprGrpCd:배송 지역 그룹","dvMnsCd:배송 수단","owhpNo:출고지","hdcCd:택배사","dvCstPolNo:배송비 정책","rtrpNo:반품지"}};
        for(var group:groups)for(int n=1;n<group.length;n++){var a=group[n].split(":",2);fields.add(new PartnerEditor.Field(a[0],a[1],group[0],"text",!Set.of("hdcCd","epdNo").contains(a[0]),choices(a[0])));}
        String[][] optional={{"brdNo","브랜드 코드"},{"mfcrNm","제조사"},{"mdlNo","모델명"},{"barCd","바코드"},{"lrtrNo","하위 거래처 번호"},{"sndBgtNday","출고 소요일"},{"rtngHdcCd","반품 택배사"},{"rtrvTypCd","회수 유형"}};
        for(var a:optional)fields.add(field(a[0],a[1],"추가 정보",false));
        for(String key:List.of("prstPckPsbYn","prstMsgPsbYn","cmbnDvPsbYn","rtngPsbYn","xchgPsbYn","cmbnRtngPsbYn","rtngRtrvPsbYn","stkMgtYn","sitmYn","adtnPdYn"))fields.add(new PartnerEditor.Field(key,switch(key){case "prstPckPsbYn"->"선물 포장";case "prstMsgPsbYn"->"선물 메시지";case "cmbnDvPsbYn"->"묶음 배송";case "rtngPsbYn"->"반품 가능";case "xchgPsbYn"->"교환 가능";case "cmbnRtngPsbYn"->"묶음 반품";case "rtngRtrvPsbYn"->"반품 회수";case "stkMgtYn"->"재고 관리";case "adtnPdYn"->"추가 상품";default->"판매자 단품";},"배송·반품","select",true,List.of("Y","N")));
        for(String key:List.of("itmByMinPurYn","itmByMinPurQty","itmByMinPurMtpYn","itmByMaxPurPsbQtyYn","maxPurQty","maxPurLmtTypCd","maxPurLmtPrd","maxPurLmtStrtDttm","maxPurLmtEndDttm"))fields.add(field("purPsbQtyInfo."+key,key,"구매 수량 제한",false));
        fields.add(field("impPrxCd","수입대행 구분","추가 정보",false));fields.add(field("slStatCd","상품 판매 상태 (SALE/SOUT/END)","상품 정보",false));
        fields.add(new PartnerEditor.Field("asContents","A/S 안내","상품 정보","textarea",false,List.of()));
        return new PartnerEditor.Schema(List.copyOf(fields),500,10,"기존 단품의 옵션명과 옵션값은 바꿀 수 없습니다. 상품번호 발급 후에도 승인이 필요합니다.");
    }
    PartnerEditor.Observation read(JsonNode source){
        if(!source.isObject()||!source.path("spdNo").isString()||!source.path("itmLst").isArray())throw invalid("롯데ON 상품 응답 형식을 확인할 수 없습니다.");
        String product=source.path("spdNo").asString();var fields=new LinkedHashMap<String,Object>();
        for(var spec:schema().fields()){var value=source.path(spec.key());if(value.isValueNode()&&!value.isNull())fields.put(spec.key(),value.asString());}
        var displays=source.path("dcatLst");if(displays.isArray())for(var v:displays)if("LTON".equals(v.path("mallCd").asString(""))){fields.put("lfDcatNo",v.path("lfDcatNo").asString(""));break;}
        for(String key:List.of("itmByMinPurYn","itmByMinPurQty","itmByMinPurMtpYn","itmByMaxPurPsbQtyYn","maxPurQty","maxPurLmtTypCd","maxPurLmtPrd","maxPurLmtStrtDttm","maxPurLmtEndDttm"))if(source.path("purPsbQtyInfo").has(key))fields.put("purPsbQtyInfo."+key,source.path("purPsbQtyInfo").path(key).asString(""));
        var options=new ArrayList<PartnerEditor.Option>();var identities=new ArrayList<PartnerEditor.Identity>();var seen=new HashSet<String>();
        for(var item:source.path("itmLst")){
            String remote=item.path("sitmNo").asString("");if(remote.isBlank()||!seen.add(remote))throw invalid("롯데ON 단품 식별자를 확인해 주세요.");
            String uuid=id("LOTTEON:"+product+":"+remote);var attrs=new ArrayList<MarketplaceDrafts.Attribute>();
            for(var a:item.path("itmOptLst"))attrs.add(new MarketplaceDrafts.Attribute(a.path("optNm").asString(""),a.path("optVal").asString("")));
            String name=attrs.stream().map(MarketplaceDrafts.Attribute::value).reduce((a,b)->a+" / "+b).orElse("기본 상품");
            options.add(new PartnerEditor.Option(uuid,name,item.path("eitmNo").asString(""),item.path("slPrc").asString(""),item.path("stkQty").asString(""),List.copyOf(attrs),"SALE".equals(item.path("slStatCd").asString(""))));identities.add(new PartnerEditor.Identity(uuid,remote));
        }
        var images=new ArrayList<PartnerEditor.Image>();var first=source.path("itmLst").path(0).path("itmImgLst");int n=0;
        for(var image:first){String url=image.path("origImgFileNm").asString("");images.add(new PartnerEditor.Image(id("LOTTEON:"+product+":image:"+n),null,url,"Y".equals(image.path("rprtImgYn").asString("N")),n++));}
        for(var e:source.path("epnLst"))if("AS_CNTS".equals(e.path("pdEpnTypCd").asString("")))fields.put("asContents",e.path("cnts").asString(""));
        String description="";for(var e:source.path("epnLst"))if("DSCRP".equals(e.path("pdEpnTypCd").asString("")))description=e.path("cnts").asString("");
        var notices=new ArrayList<MarketplaceDrafts.Attribute>();for(var a:source.path("pdItmsInfo").path("pdItmsArtlLst"))notices.add(new MarketplaceDrafts.Attribute(a.path("pdArtlCd").asString(""),a.path("pdArtlCnts").asString("")));
        var certs=new ArrayList<PartnerEditor.Certification>();for(var c:source.path("sftyAthnLst"))certs.add(new PartnerEditor.Certification(c.path("sftyAthnTypCd").asString(""),c.path("sftyAthnNo").asString(""),c.path("sftyAthnOrgnNm").asString("")));
        var input=PartnerFormSupport.structure(new PartnerEditor.Input(fields,options,images,description,source.path("pdItmsInfo").path("pdItmsCd").asString(""),notices,certs));
        return new PartnerEditor.Observation(input,product,Map.of("LOTTEON",product),identities,List.of("options.name","options.attributes"),schema().warning());
    }

    LotteonInput nativeRead(JsonNode source,PartnerEditor.Observation observation){
        var items=new LinkedHashMap<String,LotteonInput.Item>();String representative=null;
        for(var row:source.path("itmLst")){String remote=row.path("sitmNo").asString("");String uuid=observation.identities().stream().filter(v->v.remoteId().equals(remote)).findFirst().orElseThrow().optionId();var images=new ArrayList<LotteonInput.Picture>();int n=0;for(var image:row.path("itmImgLst")){String type=image.path("epsrTypDtlCd").asString("IMG_SQRE");images.add(new LotteonInput.Picture(new PartnerEditor.Image(id("LOTTEON:"+source.path("spdNo").asString()+":"+remote+":image:"+n),null,image.path("origImgFileNm").asString(""),"Y".equals(image.path("rprtImgYn").asString("N")),n++),type));}items.put(uuid,new LotteonInput.Item(readAttributes(row.path("itmOptLst")),List.copyOf(images)));if("Y".equals(row.path("rprtSitmYn").asString("N")))representative=uuid;}
        var status=new LinkedHashMap<String,String>();for(String key:List.of("slStatCd","slStatRsnCd"))if(source.has(key))status.put(key,source.path(key).asString(""));for(var entry:source.path("pdAprvStatInfo").properties())if(entry.getValue().isValueNode())status.put(entry.getKey(),entry.getValue().asString(""));return LotteonInput.structure(new LotteonInput(observation.input(),readAttributes(source.path("scatAttrLst")),items,representative,status));
    }
    private List<LotteonInput.Attribute> readAttributes(JsonNode rows){var out=new ArrayList<LotteonInput.Attribute>();for(var a:rows){String code=a.path("optCd").asString("");if(code.isBlank())continue;out.add(new LotteonInput.Attribute(code,a.path("optNm").asString(""),a.path("optValCd").asString(""),a.path("optVal").asString("")));}return List.copyOf(out);}
    static String field(PartnerEditor.Input i,String key){return Objects.toString(i.fields().get(key),"");}
    static final Map<String,Object> DEFAULTS=Map.ofEntries(Map.entry("slTypCd","GNRL"),Map.entry("pdTypCd","GNRL_GNRL"),Map.entry("pdStatCd","NEW"),Map.entry("adtnPdYn","N"),Map.entry("dvPdTypCd","GNRL"),Map.entry("tdfDvsCd","01"),Map.entry("ageLmtCd","0"),Map.entry("prstPckPsbYn","N"),Map.entry("prstMsgPsbYn","N"),Map.entry("stkMgtYn","Y"),Map.entry("sitmYn","Y"),Map.entry("rtngPsbYn","Y"),Map.entry("xchgPsbYn","Y"),Map.entry("rtngRtrvPsbYn","Y"),Map.entry("cmbnDvPsbYn","N"),Map.entry("cmbnRtngPsbYn","N"),Map.entry("purPsbQtyInfo.itmByMinPurYn","N"),Map.entry("purPsbQtyInfo.itmByMaxPurPsbQtyYn","N"),Map.entry("purPsbQtyInfo.maxPurLmtTypCd","ONCE"));
    static void validateNative(LotteonInput nativeInput,boolean create){
        if(nativeInput==null)throw invalid("롯데ON 입력값을 확인해 주세요.");var i=normalize(nativeInput.product(),schema());if(i.options().isEmpty()||i.options().size()>200)throw invalid("앱 편집·연결 제한: 공통 옵션은 200개 이하입니다. 201~500개 상품은 조회만 지원합니다.");
        var seen=new HashSet<String>();for(var row:i.options()){if(row.name().isBlank()||!seen.add(row.name()))throw invalid("중복되지 않는 옵션명을 입력해 주세요.");number(row.price(),1,999999999,"판매가");number(row.quantity(),0,999999999,"재고");if(row.sku().length()>30)throw invalid("판매자 단품번호는 30자 이하입니다.");}
        if(field(i,"spdNm").isBlank()||field(i,"spdNm").length()>150)throw invalid("상품명은 1~150자로 입력해 주세요.");if(!create)return;
        for(var f:schema().fields())if(f.required()&&field(i,f.key()).isBlank())throw invalid(f.label()+"을 입력해 주세요.");
        if(!"GNRL".equals(field(i,"slTypCd"))||!"GNRL_GNRL".equals(field(i,"pdTypCd"))||!"NEW".equals(field(i,"pdStatCd"))||!"GNRL".equals(field(i,"dvPdTypCd"))||!"N".equals(field(i,"adtnPdYn")))throw invalid("국내 일반 새 상품만 등록·수정할 수 있습니다.");
        if(!i.certifications().isEmpty()&&!Set.of("PUR_PRX","PRL_IMP","NONE").contains(field(i,"impPrxCd")))throw invalid("안전인증의 수입대행 구분을 선택해 주세요.");
        if(field(i,"brdNo").length()>7)throw invalid("브랜드 조회 ID와 상품 브랜드 코드 길이가 일치하지 않습니다. 코드를 자르지 않고 재선택을 요구합니다.");
        if(field(i,"spdNm").length()>150||field(i,"epdNo").length()>30)throw invalid("상품명·판매자 상품코드 길이를 확인해 주세요.");
        for(String k:List.of("slStrtDttm","slEndDttm")){try{java.time.LocalDateTime.parse(field(i,k),java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(java.time.format.ResolverStyle.STRICT));}catch(Exception e){throw invalid("판매일시는 YYYYMMDDHHmmss로 입력해 주세요.");}}
        if(field(i,"slStrtDttm").compareTo(field(i,"slEndDttm"))>=0)throw invalid("판매 종료일시를 시작 이후로 입력해 주세요.");
        for(String k:List.of("itmByMinPurYn","itmByMaxPurPsbQtyYn","maxPurLmtTypCd"))if(field(i,"purPsbQtyInfo."+k).isBlank())throw invalid("구매 수량 제한을 확인해 주세요.");
        if("Y".equals(field(i,"purPsbQtyInfo.itmByMinPurYn")))number(field(i,"purPsbQtyInfo.itmByMinPurQty"),1,999999,"최소 구매수량");if("Y".equals(field(i,"purPsbQtyInfo.itmByMaxPurPsbQtyYn")))number(field(i,"purPsbQtyInfo.maxPurQty"),1,999999,"최대 구매수량");
        String limit=field(i,"purPsbQtyInfo.maxPurLmtTypCd");if(!Set.of("ONCE","PERIOD","FIXED").contains(limit))throw invalid("구매 제한 구분을 선택해 주세요.");if(limit.equals("PERIOD"))number(field(i,"purPsbQtyInfo.maxPurLmtPrd"),1,99999,"구매 제한 기간");if(limit.equals("FIXED")&&(!field(i,"purPsbQtyInfo.maxPurLmtStrtDttm").matches("[0-9]{14}")||!field(i,"purPsbQtyInfo.maxPurLmtEndDttm").matches("[0-9]{14}")))throw invalid("구매 제한 일시를 입력해 주세요.");
        if(i.noticeType().isBlank()||i.notices().isEmpty()||i.description().isBlank())throw invalid("상품고시·상세설명을 입력해 주세요.");
        var tags=java.util.regex.Pattern.compile("(?is)<img\\b[^>]*>").matcher(i.description());while(tags.find())if(!java.util.regex.Pattern.compile("(?i)\\balt\\s*=").matcher(tags.group()).find())throw invalid("롯데ON 상세설명 이미지에는 alt 속성이 필요합니다.");
        if(nativeInput.representativeOptionId()==null||i.options().stream().noneMatch(o->o.id().equals(nativeInput.representativeOptionId())))throw invalid("대표 단품을 선택해 주세요.");
        if("N".equals(field(i,"sitmYn"))&&i.options().size()!=1)throw invalid("옵션 없는 상품은 단품 한 개만 입력해 주세요.");
        for(var row:i.options()){var item=nativeInput.items().get(row.id());if("Y".equals(field(i,"sitmYn"))&&(item==null||item.attributes().isEmpty()))throw invalid("옵션별 속성 코드·값을 조회하여 선택해 주세요.");var images=images(nativeInput,row.id());if(images.isEmpty()||images.size()>10||images.stream().filter(v->v.image().representative()).count()!=1)throw invalid("단품마다 1~10개 이미지와 대표 이미지 한 개가 필요합니다.");}
    }
    static void validateMedia(LotteonInput input){var p=input.product();if(p.description().isBlank())throw invalid("상세설명을 입력해 주세요.");var tags=java.util.regex.Pattern.compile("(?is)<img\\b[^>]*>").matcher(p.description());while(tags.find())if(!java.util.regex.Pattern.compile("(?i)\\balt\\s*=").matcher(tags.group()).find())throw invalid("롯데ON 상세설명 이미지에는 alt 속성이 필요합니다.");for(var o:p.options()){var pictures=images(input,o.id());if(pictures.isEmpty()||pictures.size()>10||pictures.stream().filter(v->v.image().representative()).count()!=1)throw invalid("단품마다 1~10개 이미지와 대표 이미지 한 개가 필요합니다.");}}
    static List<LotteonInput.Picture> images(LotteonInput i,String option){var own=i.items().get(option);return own!=null&&!own.images().isEmpty()?own.images():i.product().images().stream().map(v->new LotteonInput.Picture(v,"IMG_SQRE")).toList();}
    ObjectNode nativeWrite(LotteonInput input,JsonNode source,Map<String,String> identities,String group,String trader,java.util.function.Function<PartnerEditor.Image,String> imageUrl,java.util.function.Function<String,String> html){
        boolean create=source==null;validateNative(input,create);if(!create&&"N".equals(source.path("stkMgtYn").asString(""))&&"Y".equals(field(input.product(),"stkMgtYn")))throw invalid("등록된 재고 미관리 상품은 재고 관리로 전환할 수 없습니다.");var p=json.createObjectNode();p.put("trGrpCd",group);p.put("trNo",trader);if(!create)p.put("spdNo",source.path("spdNo").asString());
        var old=create?null:remap(nativeRead(source,read(source)),read(source).identities().stream().collect(java.util.stream.Collectors.toMap(PartnerEditor.Identity::optionId,v->identities.entrySet().stream().filter(e->e.getValue().equals(v.remoteId())).map(Map.Entry::getKey).findFirst().orElse(v.optionId()))));var changes=create?LotteonInput.parts(input).keySet():LotteonInput.diff(old,input,json).stream().map(c->c.path().substring(8)).collect(java.util.stream.Collectors.toSet());
        var fields=input.product().fields();var purchase=json.createObjectNode();
        for(var spec:schema().fields()){String key=spec.key();if(!create&&!changes.contains("product.fields."+key))continue;String v=Objects.toString(fields.get(key),"");if(key.equals("brdNo")&&v.length()>7)throw invalid("브랜드 코드 길이를 확인해 주세요. 조회 ID를 잘라 전송하지 않습니다.");if(key.equals("lfDcatNo")||key.equals("slStatCd")||key.equals("asContents"))continue;if(!create&&Set.of("epdNo","scatNo","slTypCd","pdTypCd","ageLmtCd","pdStatCd","dvProcTypCd","sitmYn").contains(key))throw invalid("승인 상품 수정 계약에 없는 항목을 변경할 수 없습니다: "+spec.label());if(v.isBlank())continue;if(key.startsWith("purPsbQtyInfo.")){String leaf=key.substring(14);if(Set.of("itmByMinPurQty","maxPurQty","maxPurLmtPrd").contains(leaf))purchase.put(leaf,number(v,1,999999,leaf));else purchase.put(leaf,v);}else if(key.equals("sndBgtNday"))p.put(key,number(v,0,3,"발송 예정일"));else p.put(key,v);}
        if(!purchase.isEmpty()){for(var e:fields.entrySet())if(e.getKey().startsWith("purPsbQtyInfo.")&&!Objects.toString(e.getValue(),"").isBlank()){String leaf=e.getKey().substring(14),value=Objects.toString(e.getValue(),"");if(Set.of("itmByMinPurQty","maxPurQty","maxPurLmtPrd").contains(leaf))purchase.put(leaf,number(value,1,999999,leaf));else purchase.put(leaf,value);}p.set("purPsbQtyInfo",purchase);}
        if(create||changes.contains("product.fields.lfDcatNo"))p.set("dcatLst",json.valueToTree(List.of(Map.of("mallCd","LTON","lfDcatNo",field(input.product(),"lfDcatNo")))));
        if(create||changes.contains("attributes")){if(!create)throw invalid("기존 상품 속성 변경은 승인 수정 계약 확인이 필요합니다.");p.set("scatAttrLst",wireAttributes(input.attributes()));}
        if(create||changes.contains("product.noticeType")||changes.contains("product.notices"))p.set("pdItmsInfo",json.valueToTree(Map.of("pdItmsCd",input.product().noticeType(),"pdItmsArtlLst",input.product().notices().stream().map(a->Map.of("pdArtlCd",a.name(),"pdArtlCnts",a.value())).toList())));
        if(create&&!input.product().certifications().isEmpty()||changes.contains("product.certifications"))p.set("sftyAthnLst",json.valueToTree(input.product().certifications().stream().map(c->Map.of("sftyAthnTypCd",c.type(),"sftyAthnNo",c.number(),"sftyAthnOrgnNm",c.agency())).toList()));
        if(create||changes.contains("product.description")||changes.contains("product.fields.asContents")){
            var epn=json.createArrayNode();if(!create)for(var e:source.path("epnLst")){String type=e.path("pdEpnTypCd").asString("");if((!type.equals("DSCRP")||!changes.contains("product.description"))&&(!type.equals("AS_CNTS")||!changes.contains("product.fields.asContents"))){if(!e.path("pdEpnTypCd").isValueNode()||!e.path("cnts").isValueNode())throw invalid("설명 유형 보존에 필요한 값이 미확인입니다.");epn.add(json.valueToTree(Map.of("pdEpnTypCd",type,"cnts",e.path("cnts").asString())));}}
            if(create||changes.contains("product.description"))epn.add(json.valueToTree(Map.of("pdEpnTypCd","DSCRP","cnts",html.apply(input.product().description()))));
            if(!field(input.product(),"asContents").isBlank()&&(create||changes.contains("product.fields.asContents")))epn.add(json.valueToTree(Map.of("pdEpnTypCd","AS_CNTS","cnts",field(input.product(),"asContents"))));p.set("epnLst",epn);
        }
        var rows=json.createArrayNode();var retained=new HashSet<String>();int seq=1;for(var row:input.product().options()){
            String remote=identities.get(row.id());var before=old==null?null:old.product().options().stream().filter(v->v.id().equals(row.id())).findFirst().orElse(null);var nativeRow=input.items().get(row.id());
            if(before!=null){retained.add(row.id());if(!before.name().equals(row.name())||!before.attributes().equals(row.attributes())||!Objects.equals(old.items().get(row.id()).attributes(),nativeRow==null?null:nativeRow.attributes()))throw invalid("등록된 옵션명·옵션값은 변경할 수 없습니다.");}
            var item=json.createObjectNode();if(remote!=null)item.put("sitmNo",remote);else{if(nativeRow==null&&"Y".equals(field(input.product(),"sitmYn")))throw invalid("새 단품의 속성 코드를 선택해 주세요.");if(nativeRow!=null)item.set("itmOptLst",wireAttributes(nativeRow.attributes()));item.put("sortSeq",seq);item.put("slPrc",number(row.price(),1,999999999,"판매가"));item.put("stkQty",number(row.quantity(),0,999999999,"재고"));item.put("eitmNo",row.sku());}
            if(before!=null&&!before.sku().equals(row.sku()))item.put("eitmNo",row.sku());
            if(create||!Objects.equals(input.representativeOptionId(),old.representativeOptionId()))item.put("rprtSitmYn",row.id().equals(input.representativeOptionId())?"Y":"N");
            var pics=images(input,row.id());if(create||before==null||!pics.equals(images(old,row.id()))){var files=json.createArrayNode();for(var pic:pics){String url=imageUrl.apply(pic.image());if(!MarketplaceDocuments.https(url)||url.length()>200)throw invalid("롯데ON 이미지 URL은 200자 이하 공개 HTTPS 주소여야 합니다.");files.add(json.valueToTree(Map.of("epsrTypCd","IMG","epsrTypDtlCd",pic.type(),"origImgFileNm",url,"rprtImgYn",pic.image().representative()?"Y":"N")));}item.set("itmImgLst",files);}
            if(create||item.size()>1)rows.add(item);seq++;
        }
        if(old!=null&&!retained.containsAll(old.product().options().stream().map(PartnerEditor.Option::id).toList()))throw invalid("기존 단품 삭제는 지원하지 않습니다.");
        if(!rows.isEmpty())p.set("itmLst",rows);return json.createObjectNode().set("spdLst",json.createArrayNode().add(p));
    }
    static LotteonInput remap(LotteonInput i,Map<String,String> ids){var p=i.product();var rows=p.options().stream().map(o->new PartnerEditor.Option(ids.getOrDefault(o.id(),o.id()),o.name(),o.sku(),o.price(),o.quantity(),o.attributes(),o.usable())).toList();var items=new LinkedHashMap<String,LotteonInput.Item>();i.items().forEach((k,v)->items.put(ids.getOrDefault(k,k),v));return new LotteonInput(new PartnerEditor.Input(p.fields(),rows,p.images(),p.description(),p.noticeType(),p.notices(),p.certifications()),i.attributes(),Map.copyOf(items),i.representativeOptionId()==null?null:ids.getOrDefault(i.representativeOptionId(),i.representativeOptionId()),i.status(),i.deliveryIntent());}
    private tools.jackson.databind.node.ArrayNode wireAttributes(List<LotteonInput.Attribute> attrs){var out=json.createArrayNode();for(var a:attrs){var row=json.createObjectNode().put("optCd",a.code()).put("optNm",a.name()).put("optVal",a.value());if(!a.valueCode().isBlank())row.put("optValCd",a.valueCode());out.add(row);}return out;}
}
