package cc.ataglace.molebutter.marketplace.internal;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.media.api.ImageAssets;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditing.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.Mapping;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.OptionMapping;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway.EditIntent;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceEditingFailure.Kind.*;

@Service
public final class DefaultMarketplaceProductEditor implements MarketplaceProductEditor {
    private final BusinessAccess access;
    private final DraftStore drafts;
    private final SubmissionStore listings;
    private final EditingStore sessions;
    private final TargetEditStore pending;
    private final DefaultMarketplaceEditing editing;
    private final CoupangEditor coupang;
    private final CoupangProductDocuments cp;
    private final NaverCatalog naver;
    private final NaverProductDocuments nv;
    private final ImageAssets assets;
    private final cc.ataglace.molebutter.media.api.ImageInspection inspection;
    private final Map<String,MarketplaceProductAdapter> adapters;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final Map<String,Observation> observations=new LinkedHashMap<>();
    private static final class Observation {
        final Long actor;final String market,account,product;final Instant expires;
        Document document;final Mapping mapping;final Object source;
        String savedId;Entry connected;final Map<String,Resolve> comparisonRequests=new LinkedHashMap<>();
        Observation(Long actor,String market,String account,String product,Document document,Mapping mapping,Object source){
            this.actor=actor;this.market=market;this.account=account;this.product=product;this.document=document;this.mapping=mapping;this.source=source;expires=Instant.now().plusSeconds(600);
        }
    }
    public DefaultMarketplaceProductEditor(BusinessAccess access,DraftStore drafts,SubmissionStore listings,EditingStore sessions,
            TargetEditStore pending,DefaultMarketplaceEditing editing,CoupangEditor coupang,CoupangProductDocuments cp,
            NaverCatalog naver,NaverProductDocuments nv,
            ImageAssets assets,List<MarketplaceProductAdapter> adapters,ObjectMapper json,PlatformTransactionManager manager){
        this(access,drafts,listings,sessions,pending,editing,coupang,cp,naver,nv,assets,adapters,json,manager,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DefaultMarketplaceProductEditor(BusinessAccess access,DraftStore drafts,SubmissionStore listings,EditingStore sessions,TargetEditStore pending,DefaultMarketplaceEditing editing,CoupangEditor coupang,CoupangProductDocuments cp,NaverCatalog naver,NaverProductDocuments nv,ImageAssets assets,List<MarketplaceProductAdapter> adapters,ObjectMapper json,PlatformTransactionManager manager,cc.ataglace.molebutter.media.api.ImageInspection inspection){
        this.inspection=inspection;
        this.access=access;this.drafts=drafts;this.listings=listings;this.sessions=sessions;this.pending=pending;this.editing=editing;
        this.coupang=coupang;this.cp=cp;this.naver=naver;this.nv=nv;
        this.assets=assets;this.json=json;tx=new TransactionTemplate(manager);var providers=new HashMap<String,MarketplaceProductAdapter>();
        for(var a:adapters)providers.put(a.market(),a);this.adapters=Map.copyOf(providers);
    }
    @Override public MarketplaceProductEditor.Validation validate(Long actor,Validate request){
        access.productActor(actor,true);if(request==null||request.reference()==null)throw invalid("검사할 입력을 확인해 주세요.");
        var reference=MarketplaceDocuments.normalize(request.reference());var markets=request.markets()==null?reference.selectedMarkets().stream().map(Enum::name).toList():request.markets();
        if(markets.isEmpty()||markets.size()>6||new HashSet<>(markets).size()!=markets.size())throw invalid("검사할 마켓을 선택해 주세요.");
        var changes=new HashMap<String,List<Change>>();for(var t:request.targets()==null?List.<TargetChanges>of():request.targets())if(t==null||t.changes()==null||t.changes().size()>500||changes.putIfAbsent(t.market(),t.changes())!=null)throw invalid("마켓별 검사 입력을 확인해 주세요.");
        EditingStore.Stored stored=request.sessionId()==null?null:editing.require(actor,request.sessionId(),false);
        Observation observation=request.token()==null?null:require(actor,request.token());
        if(stored!=null&&(!Objects.equals(reference.id(),stored.session().draftId())||!Objects.equals(reference.revision(),stored.session().revision())))throw new MarketplaceEditingFailure(CONFLICT);
        if(stored==null&&observation==null&&reference.id()!=null)throw invalid("저장된 상품은 최신 편집 세션으로 검사해 주세요.");
        var errors=new ArrayList<Issue>();var unknown=new ArrayList<Issue>();var warnings=new ArrayList<Issue>();var images=new ArrayList<cc.ataglace.molebutter.media.api.ImageInspection.Input>();
        for(String market:markets){
            if(!Set.of("COUPANG","NAVER","ELEVENST","LOTTEON").contains(market)){unknown.add(new Issue(market,"images","검증 미지원 마켓입니다."));continue;}
            try{
                var adapter=provider(market);Document baseline;boolean create;
                if(observation!=null){if(!market.equals(observation.market))throw invalid("검사할 마켓을 확인해 주세요.");baseline=observation.document;create=false;}
                else if(stored!=null){var target=stored.session().targets().stream().filter(v->market.equals(v.market())).findFirst().orElseThrow(()->invalid("검사할 마켓을 확인해 주세요."));if(!"READY".equals(target.status())){unknown.add(new Issue(market,"images","최신 상품을 조회한 뒤 검사해 주세요."));continue;}create="CREATE".equals(target.mode());baseline=create?adapter.newDocument(reference):target.document();}
                else {cp.protectNew(reference);create=true;baseline=adapter.newDocument(reference);}
                var desired=adapter.apply(baseline,changes.getOrDefault(market,List.of()));
                if(create&&market.equals("COUPANG")){var d=new Document(desired.id(),desired.revision(),desired.common(),desired.options(),desired.stockMode(),desired.productQuantity(),desired.services(),desired.media(),desired.delivery(),List.of(Market.COUPANG),desired.markets());var v=new DraftValidation(cp,nv,assets).validate(actor,d,null,false);errors.addAll(v.errors());unknown.addAll(v.unverified());}
                if(market.equals("NAVER")){nv.validateInput(nv.input(desired),create);}
                adapter.validateSettings(actor,desired,create,changes.getOrDefault(market,List.of()));
                if(create)warnings.addAll(MarketplaceCommonSettings.unapplied(desired,market));
                if(adapter instanceof StandaloneProductDocuments standalone)standalone.validate(actor,desired,create);
                collectImages(desired,market,create?Set.of():imageUrls(baseline,market),images,errors,unknown);
            }catch(InputValidationFailure e){errors.add(new Issue(market,e instanceof MarketplaceSettingsFailure setting?setting.path():"images",e.getMessage()));}catch(MarketplaceFailure e){unknown.add(new Issue(market,"delivery",e.getMessage()));}
        }
        var checked=inspection.inspect(actor,images);
        for(var check:checked){String path=check.role().equals("DESCRIPTION")?"media.contents":"media.images";String location=(check.role().equals("DESCRIPTION")?"상세설명":"REPRESENTATION".equals(check.role())?"대표":"추가")+" 이미지";
            for(String reason:check.reasons()){var issue=new Issue(check.market(),path,location+": "+reason+(check.width()==null?"":" ("+check.width()+"×"+check.height()+"px, "+check.bytes()+"바이트)"));if(check.status().equals("UNVERIFIED"))unknown.add(issue);else errors.add(issue);}
            for(String warning:check.warnings())warnings.add(new Issue(check.market(),path,warning));
        }
        return new MarketplaceProductEditor.Validation(errors.isEmpty()&&unknown.isEmpty(),List.copyOf(new LinkedHashSet<>(errors)),List.copyOf(new LinkedHashSet<>(unknown)),List.copyOf(new LinkedHashSet<>(warnings)),checked);
    }
    private Set<String> imageUrls(Document d,String market){var list=new ArrayList<cc.ataglace.molebutter.media.api.ImageInspection.Input>();collectImages(d,market,Set.of(),list,new ArrayList<>(),new ArrayList<>());return list.stream().map(v->v.url()).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());}
    private void collectImages(Document d,String market,Set<String> existing,List<cc.ataglace.molebutter.media.api.ImageInspection.Input> out,List<Issue> errors,List<Issue> unknown){
        if(adapters.get(market) instanceof StandaloneProductDocuments standalone){var selected=standalone.images(d,existing);out.addAll(selected);if(selected.stream().noneMatch(i->i.role().startsWith("REPRESENTATION")))unknown.add(new Issue(market,"media.images","최신 대표이미지를 확인할 수 없습니다. 현재 이미지 규격 검증은 미확인입니다."));return;}
        if(market.equals("NAVER")){var input=nv.input(d);for(var i:input.images())out.add(new cc.ataglace.molebutter.media.api.ImageInspection.Input(market,i.representative()?"REPRESENTATION":"DETAIL",null,i.id(),i.url(),i.assetId(),existing.contains(Objects.toString(i.url(),""))));descriptionImages(market,null,"description",input.description(),existing,out,unknown);return;}
        var m=d.markets().get(Market.COUPANG);
        for(var o:d.options()){
            var selected=MarketplaceDocuments.imagesFor(d,o.id()).stream().filter(i->m.overrides()==null||m.overrides().imageIds()==null||m.overrides().imageIds().contains(i.id())).toList();
            if(selected.stream().filter(Image::representative).count()!=1)errors.add(new Issue(market,"media.images","옵션의 대표 이미지를 한 개 지정해 주세요."));
            if(selected.stream().filter(i->i.imageType().equals("USED_PRODUCT")).count()>4)errors.add(new Issue(market,"media.images","중고상태 이미지는 4개 이하로 선택해 주세요."));
            if(selected.stream().filter(i->i.imageType().equals("DETAIL")).count()>9)errors.add(new Issue(market,"media.images","추가 이미지는 9개 이하로 선택해 주세요."));
            for(var i:selected)out.add(new cc.ataglace.molebutter.media.api.ImageInspection.Input(market,i.imageType(),o.id(),i.id(),i.url(),i.assetId(),existing.contains(Objects.toString(i.url(),""))));
            String override=m.overrides()==null?null:m.overrides().description();if(override!=null)descriptionImages(market,o.id(),"override",override,existing,out,unknown);
            else for(var c:MarketplaceDocuments.contentsFor(d,o.id())){if(c.type().equals("IMAGE"))out.add(new cc.ataglace.molebutter.media.api.ImageInspection.Input(market,"DESCRIPTION",o.id(),c.id(),c.value(),c.assetId(),existing.contains(c.value())));else descriptionImages(market,o.id(),c.id(),c.value(),existing,out,unknown);}
        }
    }
    private static void descriptionImages(String market,String option,String id,String html,Set<String> existing,List<cc.ataglace.molebutter.media.api.ImageInspection.Input> out,List<Issue> unknown){
        if(html==null)return;var pattern=java.util.regex.Pattern.compile("<img\\b[^>]*?\\bsrc\\s*=\\s*(?:[\"']([^\"']*)[\"']|([^\\s>]+))",java.util.regex.Pattern.CASE_INSENSITIVE);var matcher=pattern.matcher(html);int n=0;
        while(matcher.find()){String url=Objects.toString(matcher.group(1),matcher.group(2)).replace("&amp;","&");var asset=cc.ataglace.molebutter.media.api.EditedImages.ASSET.matcher(url);String assetId=asset.matches()?asset.group(1):null;out.add(new cc.ataglace.molebutter.media.api.ImageInspection.Input(market,"DESCRIPTION",option,id+":"+n++,url,assetId,existing.contains(url)));}
        if(java.util.regex.Pattern.compile("\\bsrcset\\s*=|background(?:-image)?\\s*:\\s*[^;>]*url\\s*\\(|\\bbackground\\s*=|<svg\\b|<picture\\b",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html).find())unknown.add(new Issue(market,"media.contents","설명 이미지의 srcset·배경 이미지 참조는 검사할 수 없습니다. 이미지 블록 또는 img src로 입력해 주세요."));
    }
    @Override public Entry open(Long actor,String mode,String market,String productId,String draftId){
        access.productActor(actor,true);
        if(!Set.of("COMMON_NEW","COMMON_EDIT","MARKET_NEW","MARKET_EDIT").contains(Objects.toString(mode,"")))throw invalid("상품 편집 진입 경로를 확인해 주세요.");
        if(mode.startsWith("MARKET"))provider(market);
        if("COMMON_NEW".equals(mode))return new Entry(mode,"COMMON",null,null,allTabs(),"",null,null,null);
        if("COMMON_EDIT".equals(mode)||"MARKET_NEW".equals(mode)&&draftId!=null){
            var d=drafts.find(id(draftId),false);var kind=drafts.registration(id(draftId)).kind();
            if("MARKET_NEW".equals(mode)&&!(market+"_REGISTRATION").equals(kind)&&!("MARKET_ONLY".equals(kind)&&market.equals(listings.importMarket(id(d.id())))&&listings.listingVersion(id(d.id()),market,provider(market).accountKey())>1))throw new MarketplaceEditingFailure(NOT_FOUND);
            if("COMMON_EDIT".equals(mode)&&!"COMMON".equals(kind)){
                if(kind.endsWith("_REGISTRATION"))market=kind.replace("_REGISTRATION","");
                else if("MARKET_ONLY".equals(kind))market=listings.importMarket(id(d.id()));
                else throw new MarketplaceEditingFailure(NOT_FOUND);
            }
            return stored(actor,d,market,"COMMON".equals(kind)?"COMMON_EDIT":"MARKET_NEW",kind,null);
        }
        if("MARKET_NEW".equals(mode))return new Entry(mode,market+"_REGISTRATION",market,null,List.of(market),market,null,null,null);
        if(productId==null||!productId.matches("LOTTEON".equals(market)?"[A-Za-z0-9_-]{1,30}":"[0-9]{1,30}"))throw invalid("마켓 상품 ID를 확인해 주세요.");
        var adapter=provider(market);Long mapped=listings.mappedDraft(market,adapter.accountKey(),productId);
        if(mapped!=null){var d=drafts.find(mapped,false);String kind=drafts.registration(mapped).kind();return stored(actor,d,market,mode,kind,productId);}
        var observed=observe(actor,market,productId);var token=remember(observed);
        var context=observed.source instanceof NaverEditor.EditorDocument n?new NaverEditor.Context(n.limits(),n.optionIdentities()):null;
        var reason=adapter.readOnlyReason(observed.document);var target=new Target(market,"UPDATE",reason==null?"READY":"UNSUPPORTED",Instant.now().toString(),reason,observed.document,List.of(),List.of(),context);target=target.withComparison(TargetComparisons.compare(adapter,target,null,0,1));
        return new Entry(mode,"MARKET_ONLY",market,null,List.of(market),market,observed.document,
            new Session(token,null,0,observed.expires.toString(),List.of(target)),token,observed.mapping.options().stream().map(v->new NaverEditor.OptionIdentity(v.optionId(),v.sellerProductItemId())).toList());
    }
    private Entry stored(Long actor,Document d,String market,String mode,String kind,String product){
        var session=editing.startLazy(actor,d.id(),market);
        boolean common="COMMON".equals(kind);var tabs=common?market==null?allTabs():linkedTabs(d):List.of(market);
        String token=null;
        if(!common&&product!=null){
            var target=session.targets().stream().filter(t->t.market().equals(market)).findFirst().orElseThrow();
            if("READY".equals(target.status())){
                var mapping=listings.mapping(id(d.id()),market,provider(market).accountKey(),false);
                // The session observation already contains the trusted provider projection.
                Object source="COUPANG".equals(market)?target.document().markets().get(Market.COUPANG).coupang().source():null;
                var o=new Observation(actor,market,provider(market).accountKey(),product,target.document(),mapping,source);o.savedId=d.id();token=remember(o);
            }
        }
        return new Entry(mode,kind,market,common?d.id():null,tabs,market==null?"":market,d,session,token,token==null?List.of():require(actor,token).mapping.options().stream().map(v->new NaverEditor.OptionIdentity(v.optionId(),v.sellerProductItemId())).toList());
    }
    @Override public DraftSaveResult save(Long actor,Save request){
        access.productActor(actor,true);if(request==null||request.reference()==null)throw invalid("편집 입력을 확인해 주세요.");
        var market=request.market();var adapter=provider(market);
        if(request.token()!=null){var o=require(actor,request.token());synchronized(o){
            if(!market.equals(o.market))throw new MarketplaceEditingFailure(CONFLICT);
            var d=tx.execute(t->{
                if(o.savedId!=null)return drafts.find(id(o.savedId),true);
                Long owner=listings.mappedDraft(market,o.account,o.product);if(owner!=null)throw new MarketplaceEditingFailure(CONFLICT);
                var saved=drafts.insert(actor,BusinessIds.next(),o.document,market,o.account,o.product);
                drafts.markRegistration(id(saved.id()),o.account,"MARKET_ONLY");listings.saveMapping(id(saved.id()),saved.revision(),market,o.mapping,false);
                pending.saveTarget(actor,id(saved.id()),market,new EditingStore.Binding(o.account,o.mapping),new TargetEditStore.SavedTarget(o.mapping,o.document,List.of(),List.of(),null));
                assets.replaceReferences(actor,saved.id(),MarketplaceDocuments.assetIds(saved));return saved;
            });o.savedId=d.id();d=drafts.find(id(d.id()),false);
            var session=editing.startLazy(actor,d.id(),market);
            for(var target:request.targets()==null?List.<TargetChanges>of():request.targets())if(!market.equals(target.market()))throw invalid("편집 대상 마켓을 확인해 주세요.");
            // Rebase edits made before the first internal save against the new observation.
            var current=session.targets().stream().filter(t->t.market().equals(market)).findFirst().orElseThrow();
            if(!"READY".equals(current.status()))throw invalid("최신 상품 조회에 실패했습니다. 입력을 유지한 채 다시 시도해 주세요.");
            var supplied=request.targets()==null||request.targets().isEmpty()?List.<Change>of():request.targets().getFirst().changes();
            var rebased=adapter.rebase(o.document,current.document(),supplied);
            if(!rebased.conflicts().isEmpty())throw invalid("마켓 값이 변경되었습니다. 입력을 유지한 채 최신값을 다시 확인해 주세요.");
            var saved=editing.saveDraft(actor,session.id(),new DraftSaveRequest(d,List.of(new TargetChanges(market,rebased.changes()))));
            // Further edits are based on the observation returned by this save, not the original open.
            o.document=current.document();
            return saved;
        }}
        if(request.reference().id()!=null)throw invalid("저장된 초안은 편집 세션으로 저장해 주세요.");
        Document desired=adapter.newDocument(request.reference());
        for(var target:request.targets()==null?List.<TargetChanges>of():request.targets()){
            if(!market.equals(target.market()))throw invalid("전용 등록의 마켓을 확인해 주세요.");desired=adapter.apply(desired,target.changes());
        }
        // Preserve per-option values; the legacy native converter intentionally broadcasts shared fields.
        desired=adapter.normalize(MarketplaceDocuments.normalize(desired));cp.protectNew(desired);
        if("COUPANG".equals(market)){
            var nativeInput=cp.registrationInput(desired);var options=new ArrayList<Option>();
            for(var option:desired.options()){
                var attributes=nativeInput.options().stream().filter(v->v.id().equals(option.id())).findFirst().orElseThrow().attributes();
                var purchase=attributes.stream().filter(v->"EXPOSED".equals(v.exposed())&&!Set.of("Manufacturer Part Number","Global Trade Item Number").contains(v.name())).sorted(java.util.Comparator.comparingInt(v->v.name().equals("색상")?0:v.name().equals("사이즈")?1:2)).toList();
                String name=purchase.isEmpty()?"단일 상품":String.join(" ",purchase.stream().map(v->Objects.toString(v.value(),"").trim()).filter(v->!v.isEmpty()).toList());
                options.add(new Option(option.id(),name,option.sku(),option.price(),option.quantity(),option.attributes(),option.originalPrice()));
            }
            desired=new Document(desired.id(),desired.revision(),desired.common(),List.copyOf(options),desired.stockMode(),desired.productQuantity(),desired.services(),desired.media(),desired.delivery(),desired.selectedMarkets(),desired.markets());
        }else if("NAVER".equals(market))desired=nv.document(null,null,nv.input(desired));
        else if(adapter instanceof StandaloneProductDocuments standalone)desired=standalone.commonReference(desired);
        if(!desired.selectedMarkets().equals(List.of(Market.valueOf(market))))throw invalid("전용 등록의 마켓을 확인해 주세요.");
        if(json.writeValueAsBytes(desired).length>MarketplaceDocuments.MAX_BYTES)throw invalid("초안은 5MiB 이하로 입력해 주세요.");
        final var document=desired;
        var saved=tx.execute(t->{access.productActor(actor,true);var value=drafts.insert(actor,BusinessIds.next(),document,null,null);drafts.markRegistration(id(value.id()),adapter.accountKey(),market+"_REGISTRATION");assets.replaceReferences(actor,value.id(),MarketplaceDocuments.assetIds(value));return value;});
        return new DraftSaveResult(saved,editing.startLazy(actor,saved.id(),market));
    }
    @Override public PageResponse<Summary> candidates(Long actor,String query,int page,int size){
        access.productActor(actor,true);if(page<0||page>1_000_000||!Set.of(10,20,50,100).contains(size))throw invalid("페이지를 확인해 주세요.");
        return drafts.list(MarketplaceDocuments.text(query,200).trim(),page,size,true);
    }
    @Override public Entry refresh(Long actor,String token){
        var o=require(actor,token,true);synchronized(o){
            Long owner=listings.mappedDraft(o.market,o.account,o.product);
            if(owner!=null&&!Objects.equals(Long.toString(owner),o.savedId))throw new MarketplaceEditingFailure(CONFLICT);
            Entry result;
            if(o.savedId!=null){var d=drafts.find(id(o.savedId),false);String kind=drafts.registration(id(o.savedId)).kind();if("COMMON".equals(kind))throw new MarketplaceEditingFailure(CONFLICT);result=stored(actor,d,o.market,"MARKET_EDIT",kind,o.product);}
            else{
                var fresh=observe(actor,o.market,o.product);if(!remoteOptions(o.mapping).equals(remoteOptions(fresh.mapping)))throw invalid("마켓 옵션 구성이 변경되었습니다. 작성한 입력을 확인해 주세요.");
                var latest=latest(o,fresh);var next=new Observation(actor,o.market,o.account,o.product,latest,o.mapping,fresh.source);String nextToken=remember(next);
                NaverEditor.Context context=null;if(fresh.source instanceof NaverEditor.EditorDocument n)context=new NaverEditor.Context(n.limits(),n.optionIdentities().stream().map(v->new NaverEditor.OptionIdentity(o.mapping.options().stream().filter(m->Objects.equals(m.sellerProductItemId(),v.remoteId())).findFirst().orElseThrow().optionId(),v.remoteId())).toList());
                var target=new Target(o.market,"UPDATE","READY",Instant.now().toString(),null,latest,List.of(),List.of(),context);
                result=new Entry("MARKET_EDIT","MARKET_ONLY",o.market,null,List.of(o.market),o.market,latest,new Session(nextToken,null,0,next.expires.toString(),List.of(target)),nextToken,next.mapping.options().stream().map(v->new NaverEditor.OptionIdentity(v.optionId(),v.sellerProductItemId())).toList());
            }
            synchronized(observations){observations.remove(token);}return result;
        }
    }
    @Override public ResolveResult resolve(Long actor,Resolve request){
        if(request!=null&&request.token()!=null){var o=require(actor,request.token());synchronized(o){
            if(!o.market.equals(request.market()))throw new MarketplaceEditingFailure(CONFLICT);
            var adapter=provider(o.market);var repeated=o.comparisonRequests.get(request.requestId());if(repeated!=null){if(!Objects.equals(repeated.choices(),request.choices()))throw new MarketplaceEditingFailure(CONFLICT);return editing.resolve(actor,repeated);}
            if(o.savedId!=null||!Objects.equals(request.revision(),0L)||!Objects.equals(request.listingVersion(),1L))throw new MarketplaceEditingFailure(CONFLICT);
            var target=new Target(o.market,"UPDATE","READY",null,null,o.document);
            var comparison=TargetComparisons.compare(adapter,target,null,0,1);if(!comparison.token().equals(request.comparisonToken()))throw new MarketplaceEditingFailure(CONFLICT);
            if(request.choices()==null||request.choices().stream().anyMatch(c->c==null||!"MARKET".equals(c.action())))throw invalid("아직 앱 저장값이 없습니다. 마켓 조회값을 먼저 앱에 저장해 주세요.");
            var fresh=observe(actor,o.market,o.product);if(!remoteOptions(o.mapping).equals(remoteOptions(fresh.mapping)))throw invalid("마켓 옵션 구성이 변경되었습니다. 입력을 유지한 채 다시 조회해 주세요.");
            var latest=latest(o,fresh);var oldFields=TargetComparisons.observedFields(adapter,o.document);var newFields=TargetComparisons.observedFields(adapter,latest);
            for(var choice:request.choices()){var row=comparison.rows().stream().filter(r->r.key().equals(choice.key())).findFirst().orElseThrow(()->new MarketplaceEditingFailure(CONFLICT));if(!row.adoptAllowed())throw new MarketplaceEditingFailure(CONFLICT);if(row.fields().stream().anyMatch(k->!TargetComparisons.equal(oldFields.get(k),newFields.get(k)))){var next=new Observation(actor,o.market,o.account,o.product,latest,o.mapping,fresh.source);String token=remember(next);var t=new Target(o.market,"UPDATE","READY",Instant.now().toString(),null,latest);t=t.withComparison(TargetComparisons.compare(adapter,t,null,0,1));return new ResolveResult(false,"선택한 마켓 값이 변경되었습니다. 입력을 유지한 채 다시 비교해 주세요.",latest,new Session(token,null,0,next.expires.toString(),List.of(t)),token);}}
            var saved=save(actor,new Save(request.token(),o.market,o.document,List.of()));
            var t=saved.session().targets().stream().filter(v->v.market().equals(o.market)).findFirst().orElseThrow();
            var next=new Resolve(saved.session().id(),null,saved.reference().revision(),o.market,t.listing().version(),t.comparison().token(),request.requestId(),request.choices());
            if(o.comparisonRequests.size()>=500)throw invalid("비교 요청이 너무 많습니다. 상품을 다시 열어 주세요.");o.comparisonRequests.put(request.requestId(),next);return editing.resolve(actor,next);
        }}
        return editing.resolve(actor,request);
    }
    @Override public Entry recreate(Long actor,Recreate request){
        access.productActor(actor,true);
        if(request==null||request.reference()==null||request.revision()==null||request.listingVersion()==null||request.requestId()==null||!request.requestId().matches("[0-9a-fA-F-]{36}")||request.targets()==null)throw invalid("재등록 준비 입력을 확인해 주세요.");
        try{UUID.fromString(request.requestId());}catch(RuntimeException bad){throw invalid("재등록 요청 번호를 확인해 주세요.");}
        String requestHash=MarketplaceHashes.sha256(json.writeValueAsString(request));var adapter=provider(request.market());var repeated=listings.recreateRequest(actor,request.requestId());
        if(repeated!=null){if(!Long.toString(repeated.draft()).equals(request.reference().id())||!request.market().equals(repeated.market())||!requestHash.equals(repeated.hash()))throw new MarketplaceEditingFailure(CONFLICT);var d=drafts.find(repeated.draft(),false);return stored(actor,d,request.market(),"COMMON".equals(drafts.registration(repeated.draft()).kind())?"COMMON_EDIT":"MARKET_NEW",drafts.registration(repeated.draft()).kind(),null);}
        var original=tx.execute(t->{var stored=editing.require(actor,request.sessionId(),false);var binding=editing.requireTarget(stored,request.market(),false);if(binding.mapping()==null||!Objects.equals(binding.listingVersion(),request.listingVersion())||stored.session().revision()!=request.revision()||!Objects.equals(stored.session().draftId(),request.reference().id()))throw new MarketplaceEditingFailure(CONFLICT);return stored;});
        var binding=original.binding(request.market());var basis=drafts.find(id(original.session().draftId()),false);
        // A fresh authenticated observation, outside the database transaction, must establish absence again.
        try{adapter.observeTarget(actor,basis,binding.mapping());throw invalid("연결 상품을 다시 조회할 수 있습니다. 최신값을 사용해 주세요.");}
        catch(MarketplaceListingMissing confirmed){ /* exact provider evidence only */ }
        String market=request.market();
        var saved=tx.execute(t->{
            var current=editing.require(actor,request.sessionId(),true);editing.requireTarget(current,market,true);if(!current.equals(original))throw new MarketplaceEditingFailure(CONFLICT);
            if(listings.active(id(basis.id()),binding.account(),-1))throw invalid("진행 중이거나 결과 미확인인 전송을 먼저 확인해 주세요.");
            var state=listings.listing(id(basis.id()),market,binding.account(),true);
            if(state.version()!=request.listingVersion()||!"DELETED_CONFIRMED".equals(state.state()))throw new MarketplaceEditingFailure(CONFLICT);
            var prior=pending.find(id(basis.id()),market,binding.account(),true);var observed=state.observed()==null?basis:state.observed();
            var own=request.targets().stream().filter(v->market.equals(v.market())).toList();if(own.size()>1)throw invalid("재등록 대상 변경이 중복되었습니다.");
            var changes=own.isEmpty()?prior==null?List.<Change>of():prior.intent().changes():own.getFirst().changes();var desired=adapter.apply(observed,changes);
            var others=request.targets().stream().filter(v->!market.equals(v.market())).toList();var result=editing.saveDraft(actor,current.session().id(),new DraftSaveRequest(request.reference(),others));
            var clean=RecreationDocuments.clean(desired,market,json);var merged=RecreationDocuments.reference(result.reference(),clean,market);
            listings.archiveListing(actor,id(basis.id()),market,binding,request.requestId(),requestHash,desired,new EditIntent(observed,changes));
            pending.delete(id(basis.id()),market,binding.account());
            // The shadow input is separate from common values and from unrelated provider inputs.
            listings.observedListing(id(basis.id()),market,binding.account(),binding.listingVersion()+1,"RECREATE_READY",null,clean);
            var d=drafts.update(actor,id(basis.id()),result.reference().revision(),merged);pending.revision(id(d.id()),d.revision());sessions.revokeDraft(id(d.id()));
            var refs=new HashSet<>(MarketplaceDocuments.assetIds(d));refs.addAll(pending.assetIds(id(d.id()),List.copyOf(adapters.values())));refs.addAll(listings.recreationAssetIds(id(d.id())));assets.replaceReferences(actor,d.id(),refs);return d;
        });
        return stored(actor,saved,market,"COMMON".equals(drafts.registration(id(saved.id())).kind())?"COMMON_EDIT":"MARKET_NEW",drafts.registration(id(saved.id())).kind(),null);
    }
    @Override public Entry connect(Long actor,Link request){
        access.productActor(actor,true);if(request==null)throw invalid("연결할 상품을 확인해 주세요.");
        var o=require(actor,request.token());synchronized(o){
            if(o.connected!=null)return open(actor,"MARKET_EDIT",o.market,o.product,null);
            // HTTP reads happen outside the linking transaction. Preserve the original UUIDs.
            var fresh=observe(actor,o.market,o.product);
            if(!remoteOptions(o.mapping).equals(remoteOptions(fresh.mapping)))throw invalid("마켓 옵션 구성이 변경되었습니다. 상품을 다시 조회해 주세요.");
            if(o.mapping.channelProductId()!=null&&fresh.mapping.channelProductId()!=null&&!Objects.equals(o.mapping.channelProductId(),fresh.mapping.channelProductId()))throw invalid("마켓 채널 연결이 변경되었습니다. 상품을 다시 조회해 주세요.");
            var latest=latest(o,fresh);
            var adapter=provider(o.market);var changes=request.changes()==null?List.<Change>of():request.changes();
            var rebased=adapter.rebase(o.document,latest,changes);
            if(!rebased.conflicts().isEmpty())throw invalid("마켓 값이 변경되었습니다. 입력을 유지한 채 최신값을 조회한 후 다시 연결해 주세요.");
            Entry result;
            try{result=tx.execute(t->connect(actor,request,o,latest,rebased.changes()));}
            catch(org.springframework.dao.DataIntegrityViolationException|org.springframework.dao.CannotAcquireLockException conflict){throw new MarketplaceEditingFailure(CONFLICT);}
            o.connected=result;return open(actor,"MARKET_EDIT",o.market,o.product,null);
        }
    }
    private Entry connect(Long actor,Link request,Observation o,Document latest,List<Change> changes){
        access.productActor(actor,true);var owner=listings.mappedDraft(o.market,o.account,o.product);
        if(owner!=null&&!Objects.equals(Long.toString(owner),o.savedId))throw new MarketplaceEditingFailure(CONFLICT);
        Document previous=owner==null?null:drafts.find(owner,true);
        if(previous!=null&&"COMMON".equals(drafts.registration(owner).kind()))throw invalid("이미 공통 상품에 연결되어 있습니다.");
        if(previous!=null&&listings.active(owner,o.account,-1))throw invalid("진행 중이거나 결과 미확인인 전송을 먼저 확인해 주세요.");
        Document reference;boolean existing=request.commonId()!=null;
        if(existing){
            reference=drafts.find(id(request.commonId()),true);
            if(!"COMMON".equals(drafts.registration(id(reference.id())).kind())||!Objects.equals(request.revision(),reference.revision()))throw new MarketplaceEditingFailure(CONFLICT);
            if(listings.mapping(id(reference.id()),o.market,o.account,true)!=null||listings.linkedMarkets(id(reference.id())).contains(o.market))throw invalid("이 공통 상품에는 해당 마켓 상품이 이미 연결되어 있습니다.");
            // Any unsettled target may depend on the current shared option identities.
            for(String m:listings.linkedMarkets(id(reference.id())))if(listings.active(id(reference.id()),provider(m).accountKey(),-1))throw invalid("공통 상품의 진행 중인 전송을 먼저 확인해 주세요.");
        }else{
            String code=Objects.toString(request.productCode(),"").trim();if(code.isEmpty()||code.length()>200)throw invalid("공통 상품코드를 입력해 주세요.");
            reference=commonReference(latest,o.market,code);
            if(previous!=null)reference=DraftStore.version(reference,previous.id(),previous.revision());
        }
        var ids=new HashMap<String,String>();var options=new ArrayList<>(reference.options());var commonSource=commonReference(latest,o.market,latest.common().productCode());
        if(existing){
            if(request.options()==null||request.options().size()!=o.mapping.options().size())throw invalid("모든 마켓 옵션의 연결을 직접 지정해 주세요.");
            var used=new HashSet<String>();for(var link:request.options()){
                if(link==null||ids.containsKey(link.sourceOptionId())||o.mapping.options().stream().noneMatch(v->v.optionId().equals(link.sourceOptionId())))throw invalid("옵션 연결을 확인해 주세요.");
                String target=link.commonOptionId();
                if(link.add()){
                    if(target!=null&&!target.isBlank())throw invalid("새 공통 옵션의 연결을 확인해 주세요.");
                    var source=commonSource.options().stream().filter(v->v.id().equals(link.sourceOptionId())).findFirst().orElseThrow();target=UUID.randomUUID().toString();
                    options.add(new Option(target,source.name(),source.sku(),source.price(),source.quantity(),source.attributes(),source.originalPrice()));
                }else if(options.stream().noneMatch(v->v.id().equals(link.commonOptionId())))throw invalid("공통 옵션을 직접 선택해 주세요.");
                if(!used.add(target))throw invalid("같은 공통 옵션을 중복 연결할 수 없습니다.");ids.put(link.sourceOptionId(),target);
            }
        }else for(var option:o.mapping.options())ids.put(option.optionId(),option.optionId());
        var mapping=new Mapping(o.account,o.product,o.mapping.options().stream().map(v->new OptionMapping(ids.get(v.optionId()),v.sellerProductItemId(),v.vendorItemId())).toList(),o.mapping.channelProductId());
        var projected=rekey(latest,ids,Document.class);var configs=new EnumMap<Market,MarketConfig>(Market.class);configs.putAll(reference.markets());configs.put(Market.valueOf(o.market),projected.markets().get(Market.valueOf(o.market)));
        var selected=new LinkedHashSet<>(reference.selectedMarkets());selected.add(Market.valueOf(o.market));
        reference=new Document(reference.id(),reference.revision(),reference.common(),List.copyOf(options),reference.stockMode(),reference.productQuantity(),reference.services(),reference.media(),reference.delivery(),List.copyOf(selected),Map.copyOf(configs));
        reference=MarketplaceDocuments.normalize(reference);
        var saved=reference.id()==null?drafts.insert(actor,BusinessIds.next(),reference,null,null):drafts.update(actor,id(reference.id()),reference.revision(),reference);
        if(previous!=null&&id(previous.id())!=id(saved.id())){
            listings.moveMapping(id(previous.id()),id(saved.id()),o.market,o.account);drafts.detachImport(id(previous.id()));sessions.revokeDraft(id(previous.id()));
        }
        drafts.markRegistration(id(saved.id()),null,"COMMON");listings.saveMapping(id(saved.id()),saved.revision(),o.market,mapping,false);
        if(previous!=null){var priorBinding=new EditingStore.Binding(o.account,o.mapping,listings.listingVersion(id(previous.id()),o.market,o.account));var oldSaved=pending.saved(id(previous.id()),o.market,priorBinding,false);if(oldSaved!=null){var moved=rekey(oldSaved.document(),ids,Document.class);var known=rekey(oldSaved.known(),ids,ConflictKey[].class);pending.saveTarget(actor,id(saved.id()),o.market,new EditingStore.Binding(o.account,mapping),new TargetEditStore.SavedTarget(mapping,moved,List.of(known),List.of(),null));}}
        var transferred=rekey(changes,ids,Change[].class);
        // Coupang keeps all per-option media and common projections in the observation, not the reference.
        var baseline=new Document(saved.id(),saved.revision(),projected.common(),projected.options(),projected.stockMode(),projected.productQuantity(),projected.services(),projected.media(),projected.delivery(),saved.selectedMarkets(),projected.markets());
        pending.save(actor,id(saved.id()),saved.revision(),o.market,new EditingStore.Binding(o.account,mapping),new EditIntent(baseline,List.of(transferred)));
        if(previous!=null&&id(previous.id())!=id(saved.id()))pending.delete(id(previous.id()),o.market,o.account);
        pending.revision(id(saved.id()),saved.revision());sessions.revokeDraft(id(saved.id()));
        var assetIds=new HashSet<>(MarketplaceDocuments.assetIds(saved));assetIds.addAll(pending.assetIds(id(saved.id()),new ArrayList<>(adapters.values())));assetIds.addAll(pending.savedAssetIds(id(saved.id())));assets.replaceReferences(actor,saved.id(),assetIds);
        return new Entry("MARKET_EDIT","COMMON",o.market,saved.id(),linkedTabs(saved),o.market,saved,null,null);
    }
    private Observation observe(Long actor,String market,String product){
        var a=provider(market);
        if("COUPANG".equals(market)){
            var source=coupang.edit(actor,product);if(source==null||!product.equals(source.basic().sellerProductId()))throw new MarketplaceFailure(MarketplaceFailure.Kind.RESPONSE);
            var d=cp.importObserved(source);var mapping=cp.legacyMapping(d,a.accountKey());return new Observation(actor,market,a.accountKey(),product,cp.project(d,mapping,source),mapping,source);
        }
        if(a instanceof StandaloneProductDocuments standalone){var source=standalone.standalone(actor,product);return new Observation(actor,market,a.accountKey(),product,source.document(),source.mapping(),source.document());}
        var source=naver.editor(actor,product);if(source==null||!product.equals(source.originProductNo()))throw new MarketplaceFailure(MarketplaceFailure.Kind.RESPONSE,"NAVER");
        var d=nv.document(null,null,source.input());var mapping=new Mapping(a.accountKey(),product,source.optionIdentities().stream().map(v->new OptionMapping(v.id(),v.remoteId(),null)).toList(),source.channelProductNo());
        return new Observation(actor,market,a.accountKey(),product,d,mapping,source);
    }
    private Document latest(Observation old,Observation fresh){
        if(old.mapping.channelProductId()!=null&&fresh.mapping.channelProductId()!=null&&!Objects.equals(old.mapping.channelProductId(),fresh.mapping.channelProductId()))throw invalid("마켓 채널 연결이 변경되었습니다. 상품을 다시 조회해 주세요.");
        if(fresh.source instanceof CoupangEditor.EditorDocument c)return cp.project(old.document,old.mapping,c);
        if(provider(old.market) instanceof StandaloneProductDocuments)return provider(old.market).observe(old.actor,old.document,old.mapping);
        var source=(NaverEditor.EditorDocument)fresh.source;var projected=nv.project(old.document,source,old.mapping);
        var ids=new HashMap<String,String>();for(var a:old.mapping.options())for(var b:fresh.mapping.options())if(Objects.equals(a.sellerProductItemId(),b.sellerProductItemId()))ids.put(b.optionId(),a.optionId());
        var actual=rekey(fresh.document,ids,Document.class);
        return new Document(projected.id(),projected.revision(),actual.common(),actual.options(),actual.stockMode(),actual.productQuantity(),actual.services(),actual.media(),actual.delivery(),projected.selectedMarkets(),projected.markets());
    }
    private String remember(Observation observation){synchronized(observations){observations.values().removeIf(v->Instant.now().isAfter(v.expires.plusSeconds(86400)));if(observations.size()>=2000)throw invalid("열린 상품 편집 화면이 너무 많습니다. 잠시 후 다시 시도해 주세요.");String token=UUID.randomUUID().toString()+UUID.randomUUID();observations.put(token,observation);return token;}}
    private Observation require(Long actor,String token){return require(actor,token,false);}
    private Observation require(Long actor,String token,boolean refresh){access.productActor(actor,true);Observation o;synchronized(observations){o=observations.get(token);}if(o==null||!Objects.equals(actor,o.actor))throw new MarketplaceEditingFailure(NOT_FOUND);if(!Instant.now().isBefore(refresh?o.expires.plusSeconds(86400):o.expires))throw new MarketplaceEditingFailure(EXPIRED);if(!Objects.equals(provider(o.market).accountKey(),o.account))throw new MarketplaceEditingFailure(CONFLICT);return o;}
    private MarketplaceProductAdapter provider(String market){if(!Set.of("COUPANG","NAVER","ELEVENST","LOTTEON").contains(Objects.toString(market,""))||!adapters.containsKey(market))throw invalid("지원하는 마켓을 확인해 주세요.");return adapters.get(market);}
    private List<String> allTabs(){return List.of("","COUPANG","NAVER","GMARKET","AUCTION","LOTTEON","ELEVENST");}
    private List<String> linkedTabs(Document d){var tabs=new ArrayList<String>();tabs.add("");for(var market:List.of("COUPANG","NAVER","ELEVENST","LOTTEON"))if(adapters.containsKey(market)&&listings.mapping(id(d.id()),market,provider(market).accountKey(),false)!=null)tabs.add(market);return List.copyOf(tabs);}
    private static Set<String> remoteOptions(Mapping mapping){var ids=new HashSet<String>();for(var o:mapping.options())if(o.sellerProductItemId()==null||!ids.add(o.sellerProductItemId()+":"+Objects.toString(o.vendorItemId(),"")))throw invalid("마켓 옵션 식별자를 확인해 주세요.");return ids;}
    private static Document withCode(Document d,String code){var c=d.common();return new Document(d.id(),d.revision(),new Common(code,c.name(),c.productName(),c.brand(),c.manufacturer(),c.origin(),c.material(),c.model(),c.afterService(),c.taxType(),c.adultOnly(),c.afterServiceManager(),c.afterServiceTelephone(),c.saleStartedAt(),c.saleEndedAt()),d.options(),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),d.markets());}
    /** Naver option prices are deltas. A common option stores the complete price. */
    private Document commonReference(Document d,String market,String code){
        if(provider(market) instanceof StandaloneProductDocuments standalone)d=standalone.commonReference(d);
        d=withCode(d,code);if(!"NAVER".equals(market))return d;
        var input=d.markets().get(Market.NAVER).naver().editorInput();var fields=input.fields();
        String normal="",sale="";
        try{
            var base=new java.math.BigDecimal(Objects.toString(fields.get("originProduct.salePrice"),""));var discounted=base;
            String prefix="originProduct.customerBenefit.immediateDiscountPolicy.discountMethod.";Object value=fields.get(prefix+"value");
            if(value!=null&&!value.toString().isBlank()){
                var discount=new java.math.BigDecimal(value.toString());var unit=Objects.toString(fields.get(prefix+"unitType"),"");
                if(!Set.of("WON","PERCENT").contains(unit))throw new NumberFormatException();
                discounted=base.subtract(unit.equals("WON")?discount:base.multiply(discount).divide(java.math.BigDecimal.valueOf(100)));
            }
            normal=base.toBigIntegerExact().toString();sale=discounted.toBigIntegerExact().toString();
        }catch(ArithmeticException|NumberFormatException ignored){/* Leave uncertain prices empty for explicit common input. */}
        var options=new ArrayList<Option>();for(var option:d.options()){
            String price="",original="";try{original=new java.math.BigInteger(normal).add(new java.math.BigInteger(option.price())).toString();price=new java.math.BigInteger(sale).add(new java.math.BigInteger(option.price())).toString();}catch(NumberFormatException ignored){}
            options.add(new Option(option.id(),option.name(),option.sku(),price,option.quantity(),option.attributes(),original));
        }
        if(options.isEmpty())options.add(new Option(UUID.randomUUID().toString(),"기본 옵션",d.common().productCode(),sale,d.productQuantity(),List.of(),normal));
        return new Document(d.id(),d.revision(),d.common(),List.copyOf(options),d.stockMode(),d.productQuantity(),d.services(),d.media(),d.delivery(),d.selectedMarkets(),d.markets());
    }
    /** Rekey only identity fields. Text, URL, price and attribute values are never replaced. */
    private <T>T rekey(Object value,Map<String,String> ids,Class<T> type){return json.readValue(json.writeValueAsString(rekeyValue(json.readValue(json.writeValueAsString(value),Object.class),ids,null)),type);}
    private Object rekeyValue(Object value,Map<String,String> ids,String key){
        if(value instanceof Map<?,?> map){var out=new LinkedHashMap<String,Object>();map.forEach((k,v)->out.put(Set.of("items","imageOverrides","contentOverrides").contains(Objects.toString(key,""))?ids.getOrDefault(k.toString(),k.toString()):k.toString(),rekeyValue(v,ids,k.toString())));return out;}
        if("overrides".equals(key)&&value instanceof List<?> paths)return paths.stream().map(v->{if(v instanceof String path){var parts=path.split("\\.",3);if(parts.length==3&&parts[0].equals("options")&&ids.containsKey(parts[1]))return "options."+ids.get(parts[1])+"."+parts[2];}return v instanceof String path?rekeyPath(path,ids):rekeyValue(v,ids,null);}).toList();
        if(value instanceof List<?> list)return list.stream().map(v->rekeyValue(v,ids,null)).toList();
        if("path".equals(key)&&value instanceof String path)return rekeyPath(path,ids);
        return value instanceof String s&&Set.of("id","optionId","imageOptionId","descriptionOptionId","representativeOptionId").contains(Objects.toString(key,""))?ids.getOrDefault(s,s):value;
    }
    private String rekeyPath(String path,Map<String,String> ids){for(String prefix:List.of("items.","lotteon.items.","product.options.","lotteon.product.options."))if(path.startsWith(prefix)){String tail=path.substring(prefix.length());int dot=tail.indexOf('.');if(dot>0)return prefix+ids.getOrDefault(tail.substring(0,dot),tail.substring(0,dot))+tail.substring(dot);}return path;}
    private static long id(String value){return DefaultMarketplaceSubmissions.id(value);}
    private static InputValidationFailure invalid(String message){return new InputValidationFailure(message);}
}
