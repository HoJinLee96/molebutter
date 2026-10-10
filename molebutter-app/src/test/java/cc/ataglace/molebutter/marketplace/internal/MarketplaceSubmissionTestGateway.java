package cc.ataglace.molebutter.marketplace.internal;

import java.time.Instant;
import java.util.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.Document;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditing;

/** Test-only transport: it cannot issue network requests. */
public class MarketplaceSubmissionTestGateway implements cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway {
    private String currentAccount;
    public void currentAccount(String vendor){currentAccount=vendor==null?null:DefaultMarketplaceSubmissions.account(vendor);}
    @Override public String accountKey(){return currentAccount;}
    private List<String> types=List.of("CREATE");
    private final Map<String,Deque<State>> responses=new HashMap<>();
    private final List<String> dispatches=new ArrayList<>(),readbacks=new ArrayList<>();
    private Mapping lastMapping;
    private Prepared lastPrepared;
    private boolean rebaseActual;
    private State readbackState=State.CONFIRMED;
    private StepSnapshot lastReconciled;
    public record StepSnapshot(String id,String bodyJson,String expectedJson,String baselineJson) {}
    public void reset(){currentAccount=null;types=List.of("CREATE");responses.clear();dispatches.clear();readbacks.clear();lastMapping=null;lastPrepared=null;rebaseActual=false;readbackState=State.CONFIRMED;lastReconciled=null;}
    public void types(String... values){types=List.of(values);}
    public void outcomes(String type,String... values){responses.put(type,new ArrayDeque<>(Arrays.stream(values).map(State::valueOf).toList()));}
    public List<String> dispatches(){return List.copyOf(dispatches);}
    public List<String> readbacks(){return List.copyOf(readbacks);}
    public String mappedProduct(){return lastMapping==null?null:lastMapping.sellerProductId();}
    public List<String> selectedPaths(){return lastPrepared==null||lastPrepared.editIntent()==null?List.of():lastPrepared.editIntent().changes().stream().map(MarketplaceEditing.Change::path).toList();}
    public void rebaseActualRequest(){rebaseActual=true;}
    public void readbackOutcome(String state){readbackState=State.valueOf(state);}
    public StepSnapshot lastReconciledStep(){return lastReconciled;}
    @Override public Document projectChanges(Document d,List<MarketplaceEditing.Change> changes){return cc.ataglace.molebutter.marketplacecoupang.internal.ProviderCoupangDocumentsFixture.apply(d,changes);}
    @Override public boolean validateCommonDraft(){return true;}
    @Override public Prepared prepare(Long actor,Document document,Mapping mapping,boolean requested){
        lastMapping=mapping;String account=DefaultMarketplaceSubmissions.account("test-vendor");
        if(mapping==null&&!types.contains("CREATE"))mapping=new Mapping(account,"9001",document.options().stream().map(o->new OptionMapping(o.id(),"7001","8001")).toList());
        var steps=new ArrayList<Step>();for(String type:types){
            // A persisted mapping changes the following preparation into UPDATE, not a second CREATE.
            Type actual=type.equals("CREATE")&&mapping!=null?Type.PRODUCT:Type.valueOf(type);
            steps.add(new Step(actual.name().toLowerCase(Locale.ROOT),actual,actual==Type.ORIGINAL_PRICE||actual==Type.PRICE||actual==Type.STOCK?document.options().getFirst().id():null,actual==Type.CREATE?"POST":"PUT","/fake","","{\"serverOnly\":\"private-payload\"}","{}","{}"));
        }
        lastPrepared=new Prepared(account,mapping,List.copyOf(steps),List.of(new Change("상품명","이전","이후")),Instant.now(),document.options().stream().map(o->o.sku()).toList());return lastPrepared;
    }
    @Override public Prepared prepareSelected(Long actor,Document reference,Mapping mapping,boolean requested,
            Document observed,List<MarketplaceEditing.Change> changes){
        if(mapping==null){var p=prepare(actor,reference,null,requested);lastPrepared=new Prepared(p.accountKey(),p.mapping(),p.steps(),p.changes(),p.preparedAt(),p.expectedSkus(),new EditIntent(observed,List.copyOf(changes)));return lastPrepared;}
        lastMapping=mapping;var steps=new ArrayList<Step>();var diff=new ArrayList<Change>();var seen=new HashSet<String>();
        for(var change:changes){
            Object before=observedValue(observed,change);if(Objects.equals(before,change.value()))continue;
            Type type=change.path().equals("options.price")?Type.PRICE:change.path().equals("options.quantity")?Type.STOCK:change.path().startsWith("delivery.")?Type.DELIVERY:Type.PRODUCT;
            String id=type.name().toLowerCase(Locale.ROOT)+(change.optionId()==null?"":"-"+change.optionId());
            if(seen.add(id))steps.add(new Step(id,type,change.optionId(),"PUT","/fake","","{\"serverOnly\":\"private-payload\"}","{}","{}"));
            diff.add(new Change(change.path(),before==null?null:String.valueOf(before),String.valueOf(change.value())));
        }
        lastPrepared=new Prepared(mapping.accountKey(),mapping,List.copyOf(steps),List.copyOf(diff),Instant.now(),reference.options().stream().map(o->o.sku()).toList(),new EditIntent(observed,List.copyOf(changes)));return lastPrepared;
    }
    private static Object observedValue(Document d,MarketplaceEditing.Change c){
        if(d==null)return null;if(c.path().equals("common.name"))return d.common().name();
        var option=d.options().stream().filter(o->o.id().equals(c.optionId())).findFirst().orElse(null);
        if(option!=null){if(c.path().equals("options.price"))return option.price();if(c.path().equals("options.quantity"))return option.quantity();}
        return null;
    }
    @Override public Result execute(Long actor,Prepared prepared,Step step,Mapping current){
        dispatches.add(step.type().name());var states=responses.get(step.type().name());State state=states==null||states.isEmpty()?State.CONFIRMED:states.removeFirst();
        Mapping mapping=current;
        if(mapping==null&&(state==State.CONFIRMED||state==State.ACCEPTED))mapping=new Mapping(prepared.accountKey(),"9001",List.of(new OptionMapping("10000000-1000-4000-8000-000000000001","7001","8001")));
        return new Result(state,mapping,state==State.FAILED?"REJECTED":"SUCCESS",null,Instant.now());
    }
    @Override public Result execute(Long actor,Prepared prepared,Step step,Mapping current,java.util.function.Consumer<Step> beforeDispatch){
        var actual=rebaseActual?new Step("rebased-"+step.id(),step.type(),step.optionId(),step.method(),step.path(),step.query(),"{\"serverOnly\":\"actual-request\"}","{\"baseline\":\"latest\",\"knownSellerProductIds\":[\"555\"]}","{\"expected\":\"actual-request\"}"):step;
        beforeDispatch.accept(actual);return execute(actor,prepared,actual,current);
    }
    @Override public Result reconcile(Long actor,Prepared prepared,Step step,Result previous){
        lastReconciled=new StepSnapshot(step.id(),step.bodyJson(),step.expectedJson(),step.baselineJson());
        readbacks.add(step.type().name());Mapping mapping=previous.mapping();if(mapping==null&&readbackState!=State.UNKNOWN)mapping=new Mapping(prepared.accountKey(),"9001",List.of(new OptionMapping("10000000-1000-4000-8000-000000000001","7001","8001")));
        return new Result(readbackState,mapping,readbackState==State.UNKNOWN?"UNRESOLVED":"SUCCESS",null,previous.attemptedAt());
    }
    @TestConfiguration(proxyBeanMethods=false)
    public static class Configuration {
        @Bean @Primary public MarketplaceSubmissionTestGateway submissionTestGateway(){return new MarketplaceSubmissionTestGateway();}
    }
}
