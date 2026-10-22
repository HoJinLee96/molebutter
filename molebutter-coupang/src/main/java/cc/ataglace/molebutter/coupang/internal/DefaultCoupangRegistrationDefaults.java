package cc.ataglace.molebutter.coupang.internal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.media.api.*;
@Service
public class DefaultCoupangRegistrationDefaults implements CoupangRegistrationDefaults {
    private final JdbcTemplate db; private final BusinessAccess access; private final CoupangWriteGateway gateway; private final ObjectMapper json;
    private final R2Images images;
    public DefaultCoupangRegistrationDefaults(JdbcTemplate db,BusinessAccess access,CoupangWriteGateway gateway,ObjectMapper json,R2Images images){this.db=db;this.access=access;this.gateway=gateway;this.json=json;this.images=images;}
    public Settings get(Long actor){access.productActor(actor,false);String account=account();var found=db.query("SELECT revision,defaults_json FROM coupang_registration_defaults WHERE account_key=?",(r,n)->{var v=json.readTree(r.getString(2));return new Settings(account,r.getLong(1),map(v.path("delivery")),map(v.path("registration")),map(v.path("settings")),certificates(v.path("certifications")),prefixes(v.path("descriptionPrefixImages")),logos(v.path("brandLogos")));},account);return found.isEmpty()?new Settings(account,0,Map.of(),Map.of(),Map.of()):found.getFirst();}
    private List<BrandLogo> logos(tools.jackson.databind.JsonNode node){if(!node.isArray())return List.of();var result=new ArrayList<BrandLogo>();for(var v:node)result.add(json.treeToValue(v,BrandLogo.class));return List.copyOf(result);}
    private List<BrandLogo> validateLogos(Long actor,List<BrandLogo> input){
        if(input.size()>200)throw new InputValidationFailure("브랜드 로고는 200개 이하로 지정해 주세요.");
        var result=new ArrayList<BrandLogo>();var names=new HashSet<String>();
        for(var logo:input){
            if(logo==null||logo.brandName()==null||logo.brandName().isBlank()||logo.brandName().length()>100||logo.brandName().codePoints().anyMatch(Character::isISOControl)||logo.aliases()!=null&&logo.aliases().size()>30)throw new InputValidationFailure("브랜드명과 별칭을 확인해 주세요.");
            String name=logo.brandName().trim();if(!names.add(CoupangBrandLogos.normalize(name)))throw new InputValidationFailure("브랜드 로고가 중복됩니다. 브랜드당 하나만 지정해 주세요.");
            var aliases=new LinkedHashMap<String,String>();
            for(String alias:Objects.requireNonNullElse(logo.aliases(),List.<String>of())){if(alias==null||alias.isBlank()||alias.length()>100||alias.codePoints().anyMatch(Character::isISOControl))throw new InputValidationFailure("브랜드 별칭을 확인해 주세요.");String key=CoupangBrandLogos.normalize(alias);if(!key.equals(CoupangBrandLogos.normalize(name)))aliases.putIfAbsent(key,alias.trim());}
            result.add(new BrandLogo(name,List.copyOf(aliases.values()),validatePrefixes(actor,Collections.singletonList(logo.imageUrl())).getFirst()));
        }return List.copyOf(result);
    }
    private static List<String> prefixes(tools.jackson.databind.JsonNode node){var out=new ArrayList<String>();if(node.isArray())node.forEach(v->out.add(v.asString()));return List.copyOf(out);}
    private static List<CoupangCatalog.Certification> certificates(tools.jackson.databind.JsonNode node){if(!node.isArray())return List.of();var result=new ArrayList<CoupangCatalog.Certification>();for(var c:node)result.add(new CoupangCatalog.Certification(c.path("type").asString(),c.path("code").asString(""),List.of()));return List.copyOf(result);}
    private String account(){var a=gateway.accountKey();if(a==null||!a.matches("[a-f0-9]{64}"))throw new CoupangFailure(CoupangFailure.Kind.CONFIGURATION);return a;}
    private static Map<String,String> map(tools.jackson.databind.JsonNode node){var result=new LinkedHashMap<String,String>();node.properties().forEach(e->result.put(e.getKey(),e.getValue().asString()));return Map.copyOf(result);}
    @Transactional public Settings save(Long actor,Save input){access.productActor(actor,true);if(input==null||input.revision()==null||input.revision()<0)throw invalid();
        var allowedDelivery=new HashSet<>(CoupangWorkingCopyDocuments.DELIVERY);allowedDelivery.removeAll(Set.of("jeju","notJeju"));
        var delivery=new LinkedHashMap<>(validate(input.delivery(),allowedDelivery));
        if("FREE".equals(delivery.get("deliveryChargeType"))){delivery.put("deliveryCharge","0");delivery.put("freeShipOverAmount","0");}
        var registration=validate(input.registration(),Set.of("outboundShippingTimeDay","maximumBuyForPerson","maximumBuyForPersonPeriod","unitCount","offerCondition","taxType","adultOnly","parallelImported","overseasPurchased","pccNeeded"));
        String count=registration.get("maximumBuyForPerson"),period=registration.get("maximumBuyForPersonPeriod");
        if(period!=null&&(Long.parseLong(period)<1||count!=null&&Long.parseLong(count)==0&&Long.parseLong(period)!=1))
            throw new InputValidationFailure("구매 제한 기간은 1 이상의 정수이며, 설정안함일 때는 1일이어야 합니다.");
        var settings=validate(input.settings(),Set.of("bundleInfo.bundleType"));
        var certs=input.certifications()==null?List.<CoupangCatalog.Certification>of():input.certifications();
        if(certs.size()>1)throw invalid();
        for(var c:certs)if(c==null||c.type()==null||!c.type().matches("[A-Za-z0-9_ -]{1,100}")||c.code()==null||c.code().length()>200||c.code().chars().anyMatch(ch->ch<32)||c.attachments()!=null&&!c.attachments().isEmpty())throw invalid();
        var prior=get(actor);if(prior.revision()!=input.revision())throw new BusinessException(ErrorCode.OPTIMISTIC_LOCKING_FAILURE);
        var prefixes=input.descriptionPrefixImages()==null?prior.descriptionPrefixImages():validatePrefixes(actor,input.descriptionPrefixImages());
        var logos=input.brandLogos()==null?prior.brandLogos():validateLogos(actor,input.brandLogos());
        if(logos.stream().anyMatch(v->prefixes.contains(v.imageUrl())))throw new InputValidationFailure("브랜드 로고와 공통 앞 이미지에 같은 URL을 중복 지정할 수 없습니다.");
        var payload=json.writeValueAsString(Map.of("delivery",delivery,"registration",registration,"settings",settings,"certifications",certs,"descriptionPrefixImages",prefixes,"brandLogos",logos));String account=prior.accountKey();if(!account.equals(account()))throw invalid();
        db.update("INSERT IGNORE INTO coupang_registration_defaults(account_key,revision,defaults_json,updated_by) VALUES(?,0,'{}',?)",account,actor);
        if(db.update("UPDATE coupang_registration_defaults SET revision=revision+1,defaults_json=?,updated_by=?,updated_at=CURRENT_TIMESTAMP(6) WHERE account_key=? AND revision=?",payload,actor,account,input.revision())!=1)throw new BusinessException(ErrorCode.OPTIMISTIC_LOCKING_FAILURE);
        var saved=get(actor);if(!saved.accountKey().equals(account))throw invalid();return saved;
    }
    private static final Map<String,Set<String>> ENUMS=Map.ofEntries(
        Map.entry("deliveryMethod",Set.of("SEQUENCIAL","COLD_FRESH","MAKE_ORDER","AGENT_BUY","VENDOR_DIRECT")),Map.entry("deliveryChargeType",Set.of("FREE","NOT_FREE","CHARGE_RECEIVED","CONDITIONAL_FREE")),Map.entry("remoteAreaDeliverable",Set.of("Y","N")),Map.entry("unionDeliveryType",Set.of("UNION_DELIVERY","NOT_UNION_DELIVERY")),Map.entry("offerCondition",Set.of("NEW","REFURBISHED","USED_BEST","USED_GOOD","USED_NORMAL")),Map.entry("taxType",Set.of("TAX","FREE")),Map.entry("adultOnly",Set.of("EVERYONE","ADULT_ONLY")),Map.entry("parallelImported",Set.of("PARALLEL_IMPORTED","NOT_PARALLEL_IMPORTED")),Map.entry("overseasPurchased",Set.of("OVERSEAS_PURCHASED","NOT_OVERSEAS_PURCHASED")),Map.entry("pccNeeded",Set.of("true","false")),Map.entry("bundleInfo.bundleType",Set.of("SINGLE","AB")));
    private static final Set<String> NUMBERS=Set.of("deliveryCharge","freeShipOverAmount","deliveryChargeOnReturn","returnCharge","outboundShippingTimeDay","maximumBuyForPerson","maximumBuyForPersonPeriod","unitCount");
    private List<String> validatePrefixes(Long actor,List<String> input){
        if(input.size()>20)throw new InputValidationFailure("상세설명 앞 이미지는 20개 이하로 지정해 주세요.");
        var out=new ArrayList<String>();var keys=new HashSet<String>();
        for(String value:input){
            if(value==null||value.isBlank()||value.length()>2048)throw new InputValidationFailure("상세설명 앞 이미지 URL을 확인해 주세요.");
            var image=images.resolve(actor,value.trim());
            if(image==null||!image.key().startsWith("common/"))throw new InputValidationFailure("공통 이미지는 설정된 R2 공개 버킷의 common/ 경로를 지정해 주세요.");
            if(!keys.add(image.key()))throw new InputValidationFailure("상세설명 앞 이미지 URL이 중복됩니다.");
            var errors=ImageRules.errors("DESCRIPTION",image.mimeType(),image.width(),image.height(),image.bytes());
            if(!errors.isEmpty())throw new InputValidationFailure(String.join(" ",errors));out.add(image.url());
        }return List.copyOf(out);
    }
    private static Map<String,String> validate(Map<String,String> input,Set<String> allowed){if(input==null||input.size()>40)throw invalid();var result=new LinkedHashMap<String,String>();input.forEach((k,v)->{if(!allowed.contains(k)||v==null||v.length()>500||v.chars().anyMatch(c->c<32))throw invalid();if(v.isBlank())return;if(ENUMS.containsKey(k)&&!ENUMS.get(k).contains(v))throw invalid();if(NUMBERS.contains(k)&&(!v.matches("[0-9]{1,9}")||Long.parseLong(v)>999999999))throw invalid();result.put(k,v);});return Map.copyOf(result);}
    private static InputValidationFailure invalid(){return new InputValidationFailure("쿠팡 등록 기본설정 값을 확인해 주세요.");}
}
