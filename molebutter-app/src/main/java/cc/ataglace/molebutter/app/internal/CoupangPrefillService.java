package cc.ataglace.molebutter.app.internal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.coupang.api.CoupangWorkingCopies.*;
import cc.ataglace.molebutter.imaging.api.*;
import cc.ataglace.molebutter.media.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
/** Read-only composition of owned observations, supplier evidence, catalog and public R2 bytes. */
@Service
public final class CoupangPrefillService {
    public record Request(String productCode,String brandCode,String storageProductCode,List<DownloadImageItemDto> images) {}
    public record Note(String field,String source,String message,String checkedAt) {}
    public record Result(String token,String editorUrl,String expiresAt,Entry entry,List<Note> notes,String ownerId,CoupangSearchTags.Context searchTagContext) {}
    private record Stored(Long actor,String account,Instant expires,Result result) {}
    private final Map<String,Stored> tokens=new ConcurrentHashMap<>();
    private final BusinessAccess access; private final ProductImageWorkspace workspace; private final ProductImageUploadService uploads;
    private final CoupangWorkingCopies copies; private final CoupangEditor editor; private final CoupangBrands brands; private final CoupangPricing pricing;
    private final CoupangRecommendationSources sources; private final CoupangRegistrationDefaults defaults; private final CoupangWriteGateway gateway; private final R2Images r2;
    @org.springframework.beans.factory.annotation.Autowired private CoupangQuickEditSources quickSources;
    private final Set<Long> preparing=ConcurrentHashMap.newKeySet();
    private final Clock clock; private final CoupangSearchTags searchTags;
    @org.springframework.beans.factory.annotation.Autowired
    public CoupangPrefillService(BusinessAccess access,ProductImageWorkspace workspace,ProductImageUploadService uploads,CoupangWorkingCopies copies,CoupangEditor editor,CoupangBrands brands,CoupangPricing pricing,CoupangRecommendationSources sources,CoupangRegistrationDefaults defaults,CoupangWriteGateway gateway,R2Images r2,CoupangSearchTags searchTags){this(access,workspace,uploads,copies,editor,brands,pricing,sources,defaults,gateway,r2,Clock.systemUTC(),searchTags);}
    CoupangPrefillService(BusinessAccess access,ProductImageWorkspace workspace,ProductImageUploadService uploads,CoupangWorkingCopies copies,CoupangEditor editor,CoupangBrands brands,CoupangPricing pricing,CoupangRecommendationSources sources,CoupangRegistrationDefaults defaults,CoupangWriteGateway gateway,R2Images r2, Clock clock,CoupangSearchTags searchTags){this.access=access;this.workspace=workspace;this.uploads=uploads;this.copies=copies;this.editor=editor;this.brands=brands;this.pricing=pricing;this.sources=sources;this.defaults=defaults;this.gateway=gateway;this.r2=r2;this.clock=clock;this.searchTags=searchTags;}
    public Result prepare(Long actor,Request request){
        access.productActor(actor,true);
        if(!preparing.add(actor))throw new InputValidationFailure("이미 등록 준비 중입니다. 완료 후 다시 시도해 주세요.");
        try{return prepareOwned(actor,request);}finally{preparing.remove(actor);}
    }
    private Result prepareOwned(Long actor,Request request){
        if(request==null||request.storageProductCode()==null||request.images()==null||request.images().size()>64)throw invalid();
        String code=request.storageProductCode().replaceAll("\\s+","").toUpperCase(Locale.ROOT);
        if(!code.matches("[A-Z0-9_-]{4,40}"))throw invalid();
        var product=workspace.observed(actor,request.productCode(),request.brandCode());
        // Validate the actual owned selection (indexes/source URLs/generated ownership) without rendering or uploading.
        if(!request.images().isEmpty())workspace.prepareExport(actor,new DownloadRequestDto(request.productCode(),request.brandCode(),null,false,false,null,request.images()));
        cleanup();if(tokens.size()>=256)throw new InputValidationFailure("등록 준비가 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        String account=gateway.accountKey();if(account==null||!account.matches("[a-f0-9]{64}"))throw new CoupangFailure(CoupangFailure.Kind.CONFIGURATION);var notes=new ArrayList<Note>();
        var basic=new LinkedHashMap<String,String>();var registration=new LinkedHashMap<String,String>();var settings=new LinkedHashMap<String,String>();var delivery=new LinkedHashMap<String,String>();
        String name=Objects.toString(product.productName(),"");
        var title=CoupangRegistrationText.title(product);
        basic.put("sellerProductName",(title.display()+" "+code).trim());basic.put("displayProductName",title.display());
        notes.add(new Note("상품명","상품 정보 도구 서버 원문·고시·분류","브랜드 → 성별 → 소재 → 특징 → 카테고리 · "+title.display(),null));
        if(!title.missing().isEmpty())notes.add(new Note("상품명","생략 항목",String.join(" · ",title.missing())+" 근거가 없어 생략했습니다. 필요하면 직접 수정해 주세요.",null));
        registration.put("externalVendorSku",code);if(product.originalPrice()!=null)registration.put("originalPrice",product.originalPrice().toString());
        notes.add(new Note("상품 정보","상품 정보 도구 서버 조회","원래 상품코드 "+product.productCode()+" · 저장용 "+code,null));
        notes.add(new Note("옵션","확인 필요","단일 옵션으로 준비했습니다. 색상·사이즈와 다중 옵션 구성은 직접 확인해 주세요.",null));
        var configured=attempt(notes,"등록 기본설정",()->defaults.get(actor));
        if(configured!=null){delivery.putAll(configured.delivery());registration.putAll(configured.registration());settings.putAll(configured.settings());}
        if("FREE".equals(delivery.get("deliveryChargeType"))){delivery.put("deliveryCharge","0");delivery.put("freeShipOverAmount","0");}
        if(configured==null||configured.revision()==0)notes.add(new Note("등록 기본설정","누락","공통 설정의 쿠팡 등록 기본설정을 확인해 주세요. 주소록 첫 항목을 자동 선택하지 않았습니다.",null));
        settings.put("saleStartedAt",LocalDateTime.ofInstant(clock.instant(),ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));settings.put("saleEndedAt","2099-12-31T23:59:00");
        var manufacturers=CoupangRegistrationText.manufacturers(product);
        if(manufacturers.size()==1){settings.put("manufacture",manufacturers.getFirst());notes.add(new Note("제조사","상품 정보 도구 고시",manufacturers.getFirst(),null));}
        else {settings.remove("manufacture");notes.add(new Note("제조사","확인 필요",manufacturers.isEmpty()?"조회 제조사 값이 없습니다. 직접 확인해 주세요.":"조회 제조사 항목의 값이 서로 다릅니다. 직접 확인해 주세요.",null));}
        exactBrand(actor,product.brandName(),basic,settings,notes);
        CoupangEditor.CategoryRules rules=null;
        var predicted=attempt(notes,"카테고리 추천",()->editor.predict(actor,name,product.brandName(),product.productCode()));
        if(predicted!=null&&"SUCCESS".equals(predicted.status())){
            Boolean valid=attempt(notes,"카테고리 유효성",()->editor.validCategory(actor,predicted.categoryCode()));
            if(Boolean.TRUE.equals(valid)){rules=attempt(notes,"카테고리 규격",()->editor.category(actor,predicted.categoryCode()));if(rules!=null){basic.put("displayCategoryCode",rules.categoryCode());notes.add(new Note("카테고리","쿠팡 추천·유효성·규격",Objects.toString(predicted.categoryName(),rules.categoryCode()),null));}}
        }
        if(rules==null)notes.add(new Note("카테고리","확인 필요","추천·유효성·규격이 모두 확인되지 않았습니다. 직접 선택해 주세요.",null));
        var certifications=new ArrayList<CoupangCatalog.Certification>();var attrs=new ArrayList<CoupangCatalog.Attribute>();var notices=new ArrayList<CoupangCatalog.Notice>();String noticeCategory=null;
        if(rules!=null){
            if(configured!=null)for(var cert:configured.certifications()){
                boolean optOut=Set.of("NOT_REQUIRED","PRESENTED_IN_DETAIL_PAGE").contains(cert.type());
                boolean mandatory=rules.certifications().stream().anyMatch(rule->"MANDATORY".equals(rule.required()));
                boolean compatible=rules.certifications().stream().anyMatch(rule->rule.type().equals(cert.type())&&("NONE".equals(rule.dataType())&&cert.code().isBlank()||"CODE".equals(rule.dataType())&&!cert.code().isBlank()));
                if(compatible&&!(optOut&&mandatory))certifications.add(cert);
                else notes.add(new Note("인증 기본값","확인 필요","이 카테고리 규격에 맞지 않아 적용하지 않았습니다.",null));
            }
            for(var rule:rules.attributes()){
                String value=Set.of("Manufacturer Part Number","모델번호").contains(rule.name())?product.productCode():sourceValue(product,rule.name());
                if(CoupangColorTone.attribute(rule.name(),rule.exposed())){
                    if(value==null||!CoupangColorTone.VALUES.contains(value))value=CoupangColorTone.suggest(title.display(),product.sourceProductName());
                    if(value!=null)notes.add(new Note("검색필터 색상계열","상품 제목",value+" · 전송 전 확인해 주세요.",null));
                }
                if("모델번호".equals(rule.name()))registration.put("modelNo",product.productCode());
                // No fallback FREE size or guessed option matching.
                if(value!=null)attrs.add(new CoupangCatalog.Attribute(rule.name(),value,rule.exposed()));
            }
            var matching=rules.notices().stream().filter(n->n.name().equals(sourceValue(product,"고시유형"))).toList();
            var category=matching.size()==1?matching.getFirst():rules.notices().size()==1?rules.notices().getFirst():null;
            if(category!=null){noticeCategory=category.name();for(var field:category.fields()){String value=sourceValue(product,field.name());notices.add(new CoupangCatalog.Notice(category.name(),field.name(),Objects.toString(value,"")));}}
            else notes.add(new Note("고시유형","확인 필요","고시유형이 여러 개이거나 대응 근거가 없습니다. 직접 선택해 주세요.",null));
            if(rules.certifications().stream().anyMatch(c->"MANDATORY".equals(c.required()))||rules.documents().stream().anyMatch(c->c.required()!=null&&c.required().startsWith("MANDATORY")))notes.add(new Note("인증·서류","확인 필요","카테고리의 필수 인증·서류를 확인해 주세요. 자동 생략하지 않았습니다.",null));
            if(registration.containsKey("offerCondition")&&!rules.allowedOfferConditions().contains(registration.get("offerCondition"))){registration.remove("offerCondition");notes.add(new Note("상품상태","확인 필요","카테고리에서 지원하는 상태를 선택해 주세요.",null));}
        }
        var searchContext=CoupangRegistrationText.searchContext(product);
        var generated=searchTags.generate(new CoupangSearchTags.Input(title.display(),product.brandName(),searchContext));
        registration.put("searchTags",String.join(", ",generated.tags()));
        notes.add(new Note("검색어","노출상품명·색상·상품 근거",String.join(", ",generated.tags()),null));
        for(var skipped:generated.excluded())notes.add(new Note("검색어","자동 입력 제외",skipped.value()+" · "+("OVER_COUNT".equals(skipped.reason())?"20개 한도 초과":"20자 초과"),null));
        supplier(actor,code,registration,notes);
        var images=new ArrayList<Image>();var contents=new ArrayList<Content>();
        if(configured!=null)for(String url:configured.descriptionPrefixImages()) {
            // Keep the configured row even on an outage/deletion so submission inspection blocks it, never silently omit it.
            contents.add(new Content(UUID.randomUUID().toString(),"IMAGE","IMAGE",url,null));
            var checked=attempt(notes,"공통 상세설명 이미지",()->r2.resolve(actor,url));
            if(checked!=null){var errors=ImageRules.errors("DESCRIPTION",checked.mimeType(),checked.width(),checked.height(),checked.bytes());if(!errors.isEmpty())notes.add(new Note("공통 상세설명 이미지","전송 전 수정 필요",String.join(" ",errors),null));}
        }
        if(configured!=null&&!configured.descriptionPrefixImages().isEmpty())notes.add(new Note("상세설명","공통 설정",configured.descriptionPrefixImages().size()+"개 공통 이미지를 상품 사진 앞에 배치했습니다.",null));
        if(configured!=null){
            var logo=CoupangBrandLogos.match(title.display(),configured);
            if(logo.logo()!=null){String url=logo.logo().imageUrl();contents.add(new Content(UUID.randomUUID().toString(),"IMAGE","IMAGE",url,null,"BRAND_LOGO"));
                var checked=attempt(notes,"브랜드 로고",()->r2.resolve(actor,url));if(checked!=null){var errors=ImageRules.errors("DESCRIPTION",checked.mimeType(),checked.width(),checked.height(),checked.bytes());if(!errors.isEmpty())notes.add(new Note("브랜드 로고","전송 전 수정 필요",String.join(" ",errors),null));}
                notes.add(new Note("브랜드 로고","노출상품명·공통 설정",logo.logo().brandName()+" · 공통 안내 이미지 뒤에 배치했습니다.",null));
            }else notes.add(new Note("브랜드 로고","직접 선택 필요",logo.status().equals("AMBIGUOUS")?"여러 브랜드가 일치합니다. 직접 선택해 주세요.":"제목과 일치하는 로고 설정이 없습니다.",null));
        }
        media(actor,request,code,images,contents,notes);
        var option=new Option(UUID.randomUUID().toString(),"단일 상품",Map.copyOf(registration),List.copyOf(attrs),List.copyOf(images),List.copyOf(contents),List.copyOf(notices),List.copyOf(certifications),noticeCategory);
        for(String field:List.of("originalPrice","salePrice","maximumBuyCount","outboundShippingTimeDay","taxType","adultOnly","parallelImported","overseasPurchased","pccNeeded","unitCount","maximumBuyForPerson","maximumBuyForPersonPeriod"))if(!registration.containsKey(field))notes.add(new Note(field,"누락","자동 입력 근거가 없습니다. 전송 전 확인해 주세요.",null));
        for(String field:List.of("outboundShippingPlaceCode","returnCenterCode","deliveryCompanyCode","deliveryMethod","deliveryChargeType","deliveryCharge","deliveryChargeOnReturn","returnCharge"))if(!delivery.containsKey(field))notes.add(new Note(field,"누락","등록 기본설정 또는 배송 설정을 확인해 주세요.",null));
        var entry=copies.prefill(actor,new Document(Map.copyOf(basic),List.of(option),Map.copyOf(delivery),Map.copyOf(settings),List.of()));
        if(!Objects.equals(account,entry.accountKey())||!Objects.equals(account,gateway.accountKey()))throw invalid();
        String token=UUID.randomUUID().toString();Instant expiry=clock.instant().plusSeconds(600);var result=new Result(token,"/coupang/products/new?prefill="+token,expiry.toString(),entry,List.copyOf(notes),actor.toString(),searchContext);synchronized(tokens){cleanup();if(tokens.size()>=256)throw new InputValidationFailure("등록 준비 보관 공간이 가득 찼습니다.");tokens.put(token,new Stored(actor,account,expiry,result));}return result;
    }
    public Result get(Long actor,String token){access.productActor(actor,true);var s=tokens.get(token);if(s==null||!Objects.equals(s.actor(),actor)||!s.expires().isAfter(clock.instant())||!Objects.equals(s.account(),gateway.accountKey()))throw new InputValidationFailure("등록 준비 정보가 만료되었거나 현재 계정과 다릅니다. 상품 정보 도구에서 다시 준비해 주세요. 입력은 브라우저에 유지됩니다.");return s.result();}
    private void cleanup(){tokens.values().removeIf(s->!s.expires().isAfter(clock.instant()));}
    private void exactBrand(Long actor,String name,Map<String,String> basic,Map<String,String> settings,List<Note> notes){
        var matches=new ArrayList<CoupangBrands.Brand>();boolean complete=false;
        for(int page=1;page<=20;page++){int n=page;var result=attempt(notes,"브랜드 조회",()->brands.search(actor,name,n,null));if(result==null)break;result.items().stream().filter(b->Objects.equals(b.brandName(),name)).forEach(matches::add);if(!result.hasNext()){complete=true;break;}}
        if(complete&&matches.size()==1){basic.put("brand",matches.getFirst().brandName());settings.put("brandId",matches.getFirst().brandId());if(matches.getFirst().isUIDRequired())notes.add(new Note("브랜드 식별번호","확인 필요","브랜드의 필수 식별번호를 입력해 주세요.",null));}
        else notes.add(new Note("브랜드","확인 필요","정확히 일치하는 유일한 쿠팡 브랜드를 확인하지 못했습니다.",null));
    }
    private void supplier(Long actor,String code,Map<String,String> registration,List<Note> notes){
        var found=attempt(notes,"내부 상품·매입 근거",()->sources.byCodes(actor,Set.of(code)));var source=found==null?null:found.get(code);
        if(source==null){notes.add(new Note("판매가·재고","확인 필요","활성 내부 상품의 정확한 단일 코드와 선정 매입 근거를 확인하지 못했습니다. 추정하지 않았습니다.",null));return;}
        if("AVAILABLE".equals(source.availability())&&source.purchaseCost()!=null){var calculation=attempt(notes,"추천 판매가",()->pricing.calculate(pricing.get(actor),source.purchaseCost(),null));if(calculation!=null){registration.put("salePrice",Long.toString(calculation.suggestedPrice()));notes.add(new Note("판매가",source.previousPrice()?"이전 정상 확인값·공통 계산":"선정 매입처·공통 계산","배송비·수수료·마진·가격 단위 조정 적용",source.priceCheckedAt()));}}
        var stock=CoupangRecommendationSources.stockProposal(source.availability(),source.ownedQuantity(),source.ownedOptionCount(),1,source.stocks(),source.stockOptionCount());
        if(stock!=null&&stock.quantity()!=null&&!stock.selectionRequired()){registration.put("maximumBuyCount",Long.toString(stock.quantity()));notes.add(new Note("재고",source.previousAvailability()?"이전 정상 근거":"기존 재고 추천",Objects.toString(source.availabilityReason(),source.availability()),source.availabilityCheckedAt()));}
        else {
            if(quickSources!=null&&source.ownedOptionCount()>1){var detail=attempt(notes,"보유 옵션 후보",()->quickSources.get(actor,source.productId()));if(detail!=null)notes.add(new Note("보유 재고","직접 선택 필요",detail.ownedOptions().stream().map(o->Objects.toString(o.color(),"")+" "+Objects.toString(o.size(),"")+" "+o.quantity()+"개").reduce((a,b)->a+" · "+b).orElse("옵션 확인 필요"),source.availabilityCheckedAt()));}
            String candidates=source.stocks().stream().map(s->s.label()+" "+s.quantity()+"개").reduce((a,b)->a+" · "+b).orElse("재고 미확인");notes.add(new Note("재고","직접 선택 필요",candidates+" · 옵션을 합산하거나 자동 대응하지 않았습니다.",source.availabilityCheckedAt()));}
    }
    private void media(Long actor,Request request,String code,List<Image> images,List<Content> contents,List<Note> notes){
        var entries=new ArrayList<R2Images.Entry>();String cursor=null;boolean complete=false;
        try{for(int page=0;page<100;page++){var result=r2.list(actor,code,cursor);for(var e:result.images()){if(e.key().startsWith("products/"+code+"/"))entries.add(e);else notes.add(new Note("이미지","경로 오류","다른 상품 경로를 제외했습니다.",null));}cursor=result.nextCursor();if(cursor==null||cursor.isBlank()){complete=true;break;}}}
        catch(RuntimeException error){notes.add(new Note("R2 이미지","조회 실패","목록을 읽지 못했습니다. 파일 없음으로 판단하지 않았습니다.",null));}
        if(!complete)notes.add(new Note("R2 이미지","확인 필요","일부 목록만 확인됐습니다.",null));
        var unknown=entries.stream().filter(e->kind(e.key())>=3).toList();
        for(var e:unknown.stream().limit(20).toList())notes.add(new Note("R2 파일 "+e.key(),"자동 배치 제외","숫자 상품 사진·사이즈·상품정보 파일명으로 확인할 수 없습니다.",null));
        if(unknown.size()>20)notes.add(new Note("R2 파일","자동 배치 제외", "그 외 이름 확인이 필요한 파일 "+(unknown.size()-20)+"개",null));
        var recent=uploads.recent(actor,request.productCode(),request.brandCode(),code);var ordered=new ArrayList<R2Images.Entry>();
        if(recent.isPresent()){
            for(var selected:request.images()){var file=recent.get().get(selected);var e=file==null?null:entries.stream().filter(v->v.key().equals(file.key())).findFirst().orElse(null);if(e!=null&&!ordered.contains(e))ordered.add(e);else notes.add(new Note("이미지","미업로드·삭제","현재 선택 이미지의 업로드 파일을 확인하지 못했습니다.",null));}
            notes.add(new Note("이미지 순서","최근 업로드 연결","현재 선택 순서 사용 · 실제 R2 파일 재확인",null));
        }else{entries.stream().filter(e->kind(e.key())<3).sorted(Comparator.comparingInt((R2Images.Entry e)->kind(e.key())).thenComparingInt(e->number(e.key())).thenComparing(R2Images.Entry::key)).forEach(ordered::add);notes.add(new Note("이미지 순서","기존 R2 파일 순서","숫자 사진 → 사이즈 → 상품정보 · 현재 편집 순서 연결 없음",null));}
        var numbers=new HashSet<Integer>();var ambiguous=new HashSet<Integer>();for(var e:ordered)if(kind(e.key())==0&&!numbers.add(number(e.key())))ambiguous.add(number(e.key()));
        if(!ambiguous.isEmpty())notes.add(new Note("사진","중복 번호","중복 번호 "+ambiguous+" · 여러 확장자가 있어 해당 사진은 자동 배치에서 제외했습니다.",null));
        int photos=0;var used=new HashSet<String>();
        for(var e:ordered){if(!used.add(e.key())||kind(e.key())==0&&ambiguous.contains(number(e.key())))continue;
            R2Images.Image resolved;
            if(recent.isPresent()){
                var content=attempt(notes,"이미지 파일 확인 "+e.key(),()->r2.read(actor,e.url()));if(content==null)continue;
                if(!uploads.matchesRecent(actor,request.productCode(),request.brandCode(),code,e.key(),content.bytes())){notes.add(new Note("이미지 연결","확인 필요","현재 선택과 R2 파일의 바이트가 일치하지 않아 자동 배치에서 제외했습니다. 덮어쓰거나 업로드하지 않았습니다.",null));continue;}
                resolved=content.image();
            }else resolved=attempt(notes,"이미지 파일 확인 "+e.key(),()->r2.resolve(actor,e.url()));
            if(resolved==null)continue;
            if(!resolved.key().equals(e.key())){notes.add(new Note("이미지","경로 오류","확인된 파일 경로가 달라 제외했습니다.",null));continue;}
            var errors=ImageRules.errors("DESCRIPTION",resolved.mimeType(),resolved.width(),resolved.height(),resolved.bytes());if(!errors.isEmpty()){notes.add(new Note("이미지 "+e.key(),"사용 불가",String.join(" ",errors),null));continue;}
            contents.add(new Content(UUID.randomUUID().toString(),"IMAGE","IMAGE",e.url(),null));
            if(kind(e.key())==0){photos++;if(photos<=10){String role=photos==1?"REPRESENTATION":"DETAIL";images.add(new Image(UUID.randomUUID().toString(),null,e.url(),role,photos-1));for(var reason:ImageRules.errors(role,resolved.mimeType(),resolved.width(),resolved.height(),resolved.bytes()))notes.add(new Note("상품 사진 "+e.key(),"전송 전 수정 필요",reason,null));for(var warning:ImageRules.warnings(role,resolved.width(),resolved.height()))notes.add(new Note("이미지 비율","경고",warning,null));}}
        }
        if(photos==0)notes.add(new Note("대표 이미지","누락","확인된 상품 사진이 없습니다. 사이즈·상품정보 이미지를 대표로 대신 선택하지 않았습니다.",null));
        if(photos>10)notes.add(new Note("추가 이미지","한도 안내",(photos-10)+"개 사진은 상세설명에만 포함했습니다.",null));
    }
    private static int kind(String key){String[] p=key.split("/");if(p.length!=4)return 3;String f=p[3];if(p[2].equals("official")&&f.matches("[0-9]+\\.(?i:jpg|jpeg|png)"))return 0;if(p[2].equals("processed")&&f.matches("사이즈(?:_[0-9]+)?\\.png"))return 1;if(p[2].equals("processed")&&f.matches("상품정보(?:_[0-9]+)?\\.png"))return 2;return 3;}
    private static int number(String key){var m=java.util.regex.Pattern.compile("([0-9]+)(?=\\.[^.]+$)").matcher(key);try{return m.find()?Integer.parseInt(m.group(1)):0;}catch(NumberFormatException e){return Integer.MAX_VALUE;}}
    private static String sourceValue(ProductLookupDto product,String... names){for(String name:names){
        if(CoupangRegistrationText.manufacturerField(name)){var values=CoupangRegistrationText.manufacturers(product);if(values.size()==1)return values.getFirst();continue;}
        String v=product.notificationFields().get(name);
        if(v==null){var aliases=Map.of("제조자(수입자)","제조자(공식수입/병행수입)","소재","제품 소재(제품 주소재)","A/S 책임자와 전화번호","A/S 책임자와 전화번호");v=product.notificationFields().get(aliases.getOrDefault(name,name));}if(v!=null&&!v.isBlank())return v;}return null;}
    private static <T>T attempt(List<Note> notes,String field,Supplier<T> action){try{return action.get();}catch(RuntimeException error){notes.add(new Note(field,"조회 실패","확인하지 못했습니다. 입력은 유지하며 직접 확인해 주세요.",null));return null;}}
    private static InputValidationFailure invalid(){return new InputValidationFailure("상품 정보와 저장용 상품코드를 확인해 주세요.");}
}
