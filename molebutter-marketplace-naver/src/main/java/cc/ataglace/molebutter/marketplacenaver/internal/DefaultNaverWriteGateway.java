package cc.ataglace.molebutter.marketplacenaver.internal;
import cc.ataglace.molebutter.marketplace.api.MarketplaceWriteGateway;
import cc.ataglace.molebutter.media.api.ImageAssets;
import cc.ataglace.molebutter.media.api.R2Images;
import cc.ataglace.molebutter.marketplacenaver.api.NaverGateway;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.marketplace.api.*;
import cc.ataglace.molebutter.marketplace.api.MarketplaceDrafts.Document;

/** SmartStore requests are compiled from the latest server-owned source plus explicit allowed changes. */
@Component
final class DefaultNaverWriteGateway implements MarketplaceWriteGateway {
    private final BusinessAccess access;private final NaverGateway gateway;private final ImageAssets assets;private final ObjectMapper json;
    private final NaverWriteValidation validation;private final R2Images r2;private final cc.ataglace.molebutter.media.api.ImageInspection inspection;
    private static final String ORIGIN="/v2/products/origin-products/",CHANNEL="/v2/products/channel-products/";
    DefaultNaverWriteGateway(BusinessAccess access,NaverGateway gateway,ImageAssets assets,ObjectMapper json,R2Images r2){this(access,gateway,assets,json,r2,null);}
    @org.springframework.beans.factory.annotation.Autowired
    DefaultNaverWriteGateway(BusinessAccess access,NaverGateway gateway,ImageAssets assets,ObjectMapper json,R2Images r2,cc.ataglace.molebutter.media.api.ImageInspection inspection){this.r2=r2;this.access=access;this.gateway=gateway;this.assets=assets;this.json=json;this.validation=new NaverWriteValidation(json);this.inspection=inspection;}
    public Document projectChanges(Document document,List<MarketplaceEditing.Change> changes){return NaverEditPatch.apply(document,changes);}
    public String market(){return "NAVER";}
    public String accountKey(){return gateway.accountKey();}
    private JsonNode linkedProduct(Mapping mapping){var source=gateway.product(mapping.sellerProductId());if(mapping.channelProductId()!=null)gateway.channel(mapping.channelProductId());return source;}
    public Prepared prepare(Long actor,Document document,Mapping mapping,boolean requested){
        access.productActor(actor,true);checkAccount(mapping);NaverCommonProjection.requireMediaSources(document);var input=NaverDraftAdapter.from(document);
        return gateway.session(()->{
            if(mapping!=null&&mapping.sellerProductId()!=null){var source=linkedProduct(mapping);var projection=NaverEditPatch.editor(source);var observed=NaverDraftAdapter.observed(document,projection,mapping);return selected(actor,document,mapping,observed,NaverEditPatch.diff(NaverDraftAdapter.from(observed),input),source);}
            if("PRESERVE".equals(input.optionMode()))throw invalid("기존 옵션 보존 모드로 신규 상품을 등록할 수 없습니다.");
            MarketplaceCommonSettings.validate(document);NaverDeliveryPolicy.verify(input,q->gateway.metadata("bundle-group",q));var body=wire(actor,input,null,null,null,true);validate(body,true,input);
            var step=new Step(UUID.randomUUID().toString(),Type.CREATE,null,"POST","/v2/products","",json.writeValueAsString(body),json.writeValueAsString(existingProducts(sellerCode(body))),json.writeValueAsString(body));
            return new Prepared(accountKey(),null,List.of(step),visible(null,input),Instant.now(),List.of(sellerCode(body)),new EditIntent(document,List.of()),market());
        });
    }
    public Prepared prepareSelected(Long actor,Document reference,Mapping mapping,boolean requested,Document observed,List<MarketplaceEditing.Change> changes){
        access.productActor(actor,true);checkAccount(mapping);if(mapping==null||mapping.sellerProductId()==null)return prepare(actor,NaverEditPatch.apply(observed,changes),null,false);
        return gateway.session(()->selected(actor,reference,mapping,observed,changes,linkedProduct(mapping)));
    }
    private Prepared selected(Long actor,Document reference,Mapping mapping,Document observed,List<MarketplaceEditing.Change> changes,JsonNode source){
        var view=NaverEditPatch.editor(source);if(view.limits().groupProduct())throw invalid(view.limits().message());
        var latest=NaverDraftAdapter.observed(reference,view,mapping);var old=NaverDraftAdapter.from(observed);var now=NaverDraftAdapter.from(latest);
        var desired=NaverEditPatch.apply(latest,changes);var input=NaverDraftAdapter.from(desired);
        if(!changes.isEmpty()&&now.optionMode().equals("COMBINATION")){
            var wanted=NaverDraftAdapter.from(NaverEditPatch.apply(observed,changes));
            var step=new NaverOptionStockUpdates(json).prepare(old,now,wanted,mapping,NaverEditPatch.diff(old,wanted));
            return new Prepared(accountKey(),mapping,List.of(step),visible(old,input),Instant.now(),List.of(sellerCode(source)),new EditIntent(observed,List.copyOf(changes)),market());
        }
        if(changes.stream().anyMatch(c->c.path().contains(".deliveryInfo.")))NaverDeliveryPolicy.verify(input,q->gateway.metadata("bundle-group",q));
        checkChanges(old,now,input,changes,view.limits());
        var effective=NaverEditPatch.diff(now,input);var originChanges=effective.stream().filter(c->!c.path().contains(".fields.smartstoreChannelProduct.")).toList();var channelChanges=effective.stream().filter(c->c.path().contains(".fields.smartstoreChannelProduct.")).toList();
        if(effective.isEmpty())return new Prepared(accountKey(),mapping,List.of(),List.of(),Instant.now(),List.of(sellerCode(source)),new EditIntent(latest,List.of()),market());
        var steps=new ArrayList<Step>();var finalBody=wire(actor,input,mapping,source,effective,false);validate(finalBody,false,input,effective);
        // Origin PUT also accepts SmartStore channel fields; GET need not supply a channel number.
        if(!channelChanges.isEmpty()&&mapping.channelProductId()==null){
            steps.add(step(ORIGIN+mapping.sellerProductId(),finalBody,now,input,effective));
        }else{
            if(!originChanges.isEmpty()){var body=finalBody.deepCopy();body.set("smartstoreChannelProduct",source.path("smartstoreChannelProduct").deepCopy());clean(body);steps.add(step(ORIGIN+mapping.sellerProductId(),body,now,input,originChanges));}
            if(!channelChanges.isEmpty()){
                var body=finalBody.deepCopy();if(originChanges.isEmpty()&&!changed(effective,"description"))body.path("originProduct").asObject().remove("detailContent");
                steps.add(step(CHANNEL+mapping.channelProductId(),body,now,input,channelChanges));
            }
        }
        return new Prepared(accountKey(),mapping,List.copyOf(steps),visible(now,input),Instant.now(),List.of(sellerCode(finalBody)),new EditIntent(latest,List.copyOf(effective)),market());
    }
    private Step step(String path,ObjectNode body,NaverEditor.Input before,NaverEditor.Input after,List<MarketplaceEditing.Change> changes){
        var expected=expectedValues(after,changes,body);
        return new Step(UUID.randomUUID().toString(),Type.PRODUCT,null,"PUT",path,"",json.writeValueAsString(body),json.writeValueAsString(selectedValues(before,changes)),json.writeValueAsString(expected));
    }
    private ObjectNode expectedValues(NaverEditor.Input input,List<MarketplaceEditing.Change> changes,JsonNode body){
        var expected=selectedValues(input,changes);
        if(changed(changes,"images"))expected.set(NaverEditPatch.PREFIX+"images",imageValues(body.path("originProduct").path("images")));
        // With omitted stock, the latest observed quantity is retained by Naver.
        // Normalize the expected status without putting that quantity back into the request.
        if(!body.path("originProduct").has("stockQuantity")){
            var stock=json.valueToTree(input.fields().get("originProduct.stockQuantity"));
            String status=NaverEditPatch.PREFIX+"fields.originProduct.statusType";
            if(expected.path(status).asString("").equals("SALE")&&stock.isIntegralNumber()&&stock.asLong()==0)expected.put(status,"OUTOFSTOCK");
        }
        return normalizeExpected(expected,body);
    }
    private ObjectNode normalizeExpected(ObjectNode expected,JsonNode body){
        String status=NaverEditPatch.PREFIX+"fields.originProduct.statusType";
        var stock=body.path("originProduct").path("stockQuantity");
        if(expected.has(status)&&stock.isIntegralNumber()&&stock.asLong()==0)expected.put(status,"OUTOFSTOCK");
        return expected;
    }
    private ObjectNode selectedValues(NaverEditor.Input input,List<MarketplaceEditing.Change> changes){var values=json.createObjectNode();for(var c:changes){var value=json.valueToTree(NaverEditPatch.value(input,c));if(c.path().equals(NaverEditPatch.PREFIX+"images")){var images=json.createArrayNode();for(var image:input.images().stream().sorted(Comparator.comparingInt(NaverEditor.Image::order)).toList())images.add(json.createObjectNode().put("url",image.url()).put("representative",image.representative()).put("order",image.order()));value=images;}else if(c.path().equals(NaverEditPatch.PREFIX+"options"))value=json.valueToTree(input.options().stream().sorted(Comparator.comparing(NaverEditor.Option::id)).toList());else if(c.path().equals(NaverEditPatch.PREFIX+"fields.smartstoreChannelProduct.channelProductName")&&(value.isNull()||value.isString()&&value.asString().isBlank()))value=json.valueToTree(input.fields().get("originProduct.name"));
            String prefix=NaverEditPatch.PREFIX+"fields.originProduct.deliveryInfo.deliveryFee.";if(c.path().startsWith(prefix)){
                String key=c.path().substring(prefix.length()),type=Objects.toString(input.fields().get("originProduct.deliveryInfo.deliveryFee.deliveryFeeType"),"");
                if(key.equals("baseFee")&&type.equals("FREE"))value=json.valueToTree(0L);
                if(key.equals("freeConditionalAmount")&&!type.equals("CONDITIONAL_FREE")||key.equals("repeatQuantity")&&!type.equals("UNIT_QUANTITY_PAID")||Set.of("secondBaseQuantity","secondExtraFee","thirdBaseQuantity","thirdExtraFee").contains(key)&&!type.equals("RANGE_QUANTITY_PAID"))value=json.nullNode();
            }
            if(c.path().startsWith(NaverEditPatch.PREFIX+"fields.originProduct.customerBenefit.immediateDiscountPolicy.discountMethod.")&&input.fields().get("originProduct.customerBenefit.immediateDiscountPolicy.discountMethod.value")==null)value=json.nullNode();
            values.set(c.path(),value);}return values;}
    private JsonNode imageValues(JsonNode source){var result=json.createArrayNode();int order=0;String representative=source.path("representativeImage").path("url").asString("");if(!representative.isBlank())result.add(json.createObjectNode().put("url",representative).put("representative",true).put("order",order++));for(var image:source.path("optionalImages"))result.add(json.createObjectNode().put("url",image.path("url").asString()).put("representative",false).put("order",order++));return result;}
    private void checkChanges(NaverEditor.Input observed,NaverEditor.Input current,NaverEditor.Input desired,List<MarketplaceEditing.Change> changes,NaverEditor.Limits limits){
        if(!Objects.equals(current.optionMode(),desired.optionMode()))throw invalid("기존 상품의 옵션 유형 변경은 지원하지 않습니다. 현재 옵션 유형을 유지해 주세요.");
        for(var c:changes){
            JsonNode before=selectedValues(observed,List.of(c)).path(c.path()),now=selectedValues(current,List.of(c)).path(c.path()),wanted=selectedValues(desired,List.of(c)).path(c.path());
            if(!NaverEditPatch.equivalent(before,now)&&!NaverEditPatch.equivalent(now,wanted))throw invalid("스마트스토어의 선택한 항목이 외부에서 변경되었습니다. 조회 당시 값: "+show(before)+" / 현재 값: "+show(now)+" / 입력 값: "+show(wanted)+". 다시 조회해 주세요.");
            String key=c.path().substring(NaverEditPatch.PREFIX.length());
            if(limits.optionStructureReadonly()&&Set.of("optionMode","optionNames","options").contains(key)&&!NaverEditPatch.equivalent(now,wanted))throw invalid("기존 단독형·직접 입력형·표준형 옵션은 구조를 유지해 주세요.");
            if(limits.categoryReadonly()&&key.equals("fields.originProduct.leafCategoryId")&&!NaverEditPatch.equivalent(now,wanted))throw invalid("이 상품의 카테고리는 스마트스토어센터에서 변경해 주세요.");
            if(limits.modelReadonly()&&key.startsWith("fields.originProduct.detailAttribute.naverShoppingSearchInfo.model")&&!NaverEditPatch.equivalent(now,wanted))throw invalid("카탈로그에 매칭된 모델은 스마트스토어센터에서 변경해 주세요.");
            if(key.equals("fields.originProduct.detailAttribute.releaseDate")&&!now.isNull()&&!now.isMissingNode()&&!(now.isString()&&now.asString().isBlank())&&!NaverEditPatch.equivalent(now,wanted))throw invalid("이미 등록된 출시일은 수정하거나 삭제할 수 없습니다.");
            if(!current.optionMode().equals("NONE")&&key.equals("fields.originProduct.stockQuantity")&&!NaverEditPatch.equivalent(now,wanted))throw invalid("옵션 상품의 재고는 각 옵션에서 변경해 주세요.");
        }
    }
    public Result execute(Long actor,Prepared prepared,Step step,Mapping current){return execute(actor,prepared,step,current,s->{});}
    public Result execute(Long actor,Prepared prepared,Step step,Mapping current,Consumer<Step> beforeDispatch){
        access.productActor(actor,true);checkAccount(current);if(!market().equals(prepared.market())||!accountKey().equals(prepared.accountKey()))return failure(current,"ACCOUNT_CHANGED");
        try{return gateway.session(()->{
            Step actual=step;
            NaverEditor.Input executionInput=NaverDraftAdapter.from(prepared.editIntent().observed());
            try{
                if(step.type()==Type.CREATE){if(inspection!=null){executionInput=NaverDraftAdapter.from(NaverEditPatch.apply(prepared.editIntent().observed(),prepared.editIntent().changes()));var checks=new ArrayList<cc.ataglace.molebutter.media.api.ImageInspection.Input>();for(var image:executionInput.images())checks.add(new cc.ataglace.molebutter.media.api.ImageInspection.Input("NAVER",image.representative()?"REPRESENTATION":"DETAIL",null,image.id(),image.url(),image.assetId(),false));checks.addAll(cc.ataglace.molebutter.media.api.ImageInspection.descriptionInputs("NAVER",executionInput.description(),""));inspection.require(actor,checks);}if(current!=null&&current.sellerProductId()!=null)return failure(current,"ALREADY_REGISTERED");actual=new Step(step.id(),step.type(),step.optionId(),step.method(),step.path(),step.query(),step.bodyJson(),json.writeValueAsString(existingProducts(sellerCode(json.readTree(step.bodyJson())))),step.expectedJson());}
                else {
                    var source=gateway.product(current.sellerProductId());var view=NaverEditPatch.editor(source);if(view.limits().groupProduct())return failure(current,"GROUP_PRODUCT");
                    if(NaverOptionStockUpdates.isStep(step)){
                        var stocks=new NaverOptionStockUpdates(json);var latest=NaverDraftAdapter.from(NaverDraftAdapter.observed(prepared.editIntent().observed(),view,current));executionInput=latest;
                        var body=stocks.body(latest,current,step);
                        if(stocks.reflected(latest,current,step))return new Result(State.CONFIRMED,current,"CONFIRMED","재조회에서 선택한 옵션 재고가 확인되었습니다.",Instant.now());
                        actual=new Step(step.id(),step.type(),step.optionId(),step.method(),step.path(),step.query(),json.writeValueAsString(body),step.baselineJson(),step.expectedJson());
                    }else{
                    var expected=normalizeExpected(json.readTree(step.expectedJson()).deepCopy().asObject(),json.readTree(step.bodyJson()));
                    var relevant=prepared.editIntent().changes().stream().filter(c->expected.has(c.path())).toList();
                    var latest=NaverDraftAdapter.observed(prepared.editIntent().observed(),view,current);var now=NaverDraftAdapter.from(latest);var old=NaverDraftAdapter.from(prepared.editIntent().observed());
                    var desired=NaverDraftAdapter.from(NaverEditPatch.apply(latest,relevant));executionInput=desired;
                    if(matches(selectedValues(now,relevant),expected))return new Result(State.CONFIRMED,mapping(source,desired,current),"CONFIRMED","재조회에서 변경 값이 확인되었습니다.",Instant.now());
                    checkChanges(old,now,desired,relevant,view.limits());
                    var imageWire=json.readTree(step.bodyJson()).path("originProduct").path("images");var body=wire(actor,desired,current,source,relevant,false,imageWire);
                    if(relevant.stream().noneMatch(c->c.path().contains(".fields.smartstoreChannelProduct.")))body.set("smartstoreChannelProduct",source.path("smartstoreChannelProduct").deepCopy());
                    clean(body);validate(body,false,desired,relevant);actual=new Step(step.id(),step.type(),null,step.method(),step.path(),step.query(),json.writeValueAsString(body),step.baselineJson(),json.writeValueAsString(expectedValues(desired,relevant,body)));
                    }
                }
            }catch(InputValidationFailure e){return failure(current,"BASELINE_CHANGED");}
            if(step.type()==Type.CREATE||prepared.editIntent().changes().stream().anyMatch(c->c.path().contains(".deliveryInfo.")))NaverDeliveryPolicy.verify(executionInput,q->gateway.metadata("bundle-group",q));
            beforeDispatch.accept(actual);final Step sent=actual;NaverGateway.Response response;
            try{response=gateway.write(sent.method(),sent.path(),json.readTree(sent.bodyJson()));}catch(MarketplaceFailure e){return new Result(State.UNKNOWN,current,"RESULT_UNKNOWN","요청 결과를 확인한 뒤 다시 시도해 주세요.",Instant.now(),sent.bodyJson());}
            var outcome=parseOutcome(response,sent,current,executionInput);
            if(outcome.state()==State.ACCEPTED&&outcome.mapping()!=null&&outcome.mapping().sellerProductId()!=null)return reconcileWithRequest(actor,prepared,sent,outcome);
            return outcome;
        });}catch(MarketplaceFailure preflightFailure){return new Result(State.FAILED,current,"PREFLIGHT_"+preflightFailure.kind().name(),"전송 전 조회에 실패하여 상품 요청을 보내지 않았습니다. 다시 확인해 주세요.",Instant.now());}
    }
    public Result reconcile(Long actor,Prepared prepared,Step step,Result previous){access.productActor(actor,true);checkAccount(previous.mapping());if(!accountKey().equals(prepared.accountKey()))return new Result(State.FAILED,previous.mapping(),"ACCOUNT_CHANGED","스마트스토어 연결 계정이 변경되었습니다.",previous.attemptedAt(),previous.requestJson());return gateway.session(()->reconcileWithRequest(actor,prepared,step,previous));}
    private Result reconcileWithRequest(Long actor,Prepared prepared,Step step,Result previous){
        Mapping mapping=previous.mapping();var input=NaverDraftAdapter.from(NaverEditPatch.apply(prepared.editIntent().observed(),prepared.editIntent().changes()));
        try{
            if(mapping==null||mapping.sellerProductId()==null){
                // Seller codes and matching content cannot prove which request created a product.
                // Keep the unconfirmed write locked until a causally verified product ID is available.
                return pending(previous,"CREATE_UNCONFIRMED");
            }
            var source=linkedProduct(mapping);var verified=mapping(source,input,mapping);
            if(!Objects.equals(mapping.sellerProductId(),verified.sellerProductId())||mapping.channelProductId()!=null&&!Objects.equals(mapping.channelProductId(),verified.channelProductId()))return pending(previous,"MAPPING_CHANGED");
            mapping=verified;var current=NaverDraftAdapter.observed(prepared.editIntent().observed(),NaverEditPatch.editor(source),mapping);var latest=NaverDraftAdapter.from(current);
            boolean reflected;
            if(step.type()==Type.CREATE)reflected=createMatches(json.readTree(step.bodyJson()),source);
            else if(NaverOptionStockUpdates.isStep(step))reflected=new NaverOptionStockUpdates(json).reflected(latest,mapping,step);
            else{var request=json.readTree(previous.requestJson()==null?step.bodyJson():previous.requestJson());var keys=normalizeExpected(json.readTree(step.expectedJson()).deepCopy().asObject(),request);var selected=new ArrayList<MarketplaceEditing.Change>();for(var key:keys.properties())selected.add(new MarketplaceEditing.Change(key.getKey(),null,null));reflected=matches(selectedValues(latest,selected),keys);}
            String status=source.path("originProduct").path("statusType").asString("");boolean approval=Set.of("WAIT","UNADMISSION").contains(status);
            if(step.type()==Type.CREATE){
                // The successful POST's ID plus an authenticated GET proves creation. Later
                // edits and server normalization must not keep a confirmed creation locked.
                // Require every local option to retain a verified remote identity first.
                var rows=source.path("originProduct").path("detailAttribute").path("optionInfo").path("optionCombinations");
                var ids=new HashSet<String>();for(var row:rows){var id=NaverEditPatch.id(row.path("id"));if(id!=null)ids.add(id);}
                var linkedIds=new HashSet<String>();for(var link:mapping.options())linkedIds.add(link.sellerProductItemId());
                if(rows.size()!=input.options().size()||!ids.equals(linkedIds)
                        ||!cc.ataglace.molebutter.marketplace.api.MarketplaceCreationConfirmation.completeOptions(mapping,input.options().stream().map(NaverEditor.Option::id).toList(),false))return pending(new Result(previous.state(),mapping,previous.code(),previous.message(),previous.attemptedAt(),previous.requestJson()),"CREATE_OPTIONS_UNCONFIRMED");
                if(approval)return new Result(State.ACCEPTED,mapping,"APPROVAL_PENDING","상품 번호가 발급되었으며 승인 결과를 확인하고 있습니다.",previous.attemptedAt(),previous.requestJson());
                if(status.equals("REJECTION"))return pending(new Result(previous.state(),mapping,previous.code(),previous.message(),previous.attemptedAt(),previous.requestJson()),"APPROVAL_REJECTED");
                if(!Set.of("SALE","OUTOFSTOCK","SUSPENSION","CLOSE").contains(status))return pending(previous,"CREATE_STATUS_UNCONFIRMED");
                if(!source.path("smartstoreChannelProduct").path("naverShoppingRegistration").isBoolean())return pending(previous,"VERIFY_UNAVAILABLE");
                return new Result(State.CONFIRMED,mapping,reflected?"CREATED_CONFIRMED":"CREATED_VALUES_DIFFER",reflected?"등록 상품과 옵션 연결을 확인했습니다.":"등록 상품과 옵션 연결을 확인했습니다. 현재 상품값이 등록 요청과 다릅니다. 상품 수정에서 최신값을 확인해 주세요.",previous.attemptedAt(),previous.requestJson());
            }
            return new Result(reflected&&!approval?State.CONFIRMED:State.ACCEPTED,mapping(source,input,mapping),reflected?approval?"APPROVAL_PENDING":"CONFIRMED":"REFLECTION_PENDING",reflected?approval?"상품 번호가 발급되었으며 승인 결과를 확인하고 있습니다.":"재조회에서 변경 값이 확인되었습니다.":"요청은 접수되었으며 반영 결과를 확인하고 있습니다.",Instant.now(),previous.requestJson());
        }catch(MarketplaceFailure|InputValidationFailure e){return pending(new Result(previous.state(),mapping,previous.code(),previous.message(),previous.attemptedAt(),previous.requestJson()),"VERIFY_UNAVAILABLE");}
    }
    private Result pending(Result previous,String code){return new Result(previous.mapping()!=null&&previous.mapping().sellerProductId()!=null?State.ACCEPTED:State.UNKNOWN,previous.mapping(),code,"등록 여부와 반영 결과를 확인해야 합니다. 같은 요청을 다시 전송하지 않습니다.",Instant.now(),previous.requestJson());}
    Result parseOutcome(NaverGateway.Response response,Step step,Mapping existing,NaverEditor.Input input){
        if(response.status()<200||response.status()>=300){if(response.status()>=500)return new Result(State.UNKNOWN,existing,"HTTP_"+response.status(),"스마트스토어 응답이 불명확하여 결과 확인이 필요합니다.",Instant.now(),step.bodyJson());return new Result(State.FAILED,existing,"HTTP_"+response.status(),"스마트스토어가 요청을 거절했습니다. 입력 값과 API 권한을 확인해 주세요.",Instant.now(),step.bodyJson());}
        JsonNode body;try{body=gateway.parse(response);}catch(RuntimeException e){return new Result(State.UNKNOWN,existing,"RESPONSE","스마트스토어 응답을 확인하지 못했습니다.",Instant.now(),step.bodyJson());}
        Mapping mapping=existing;
        try{mapping=mapping(body,input,existing);}catch(InputValidationFailure e){if(step.type()==Type.CREATE)return new Result(State.UNKNOWN,existing,"RESPONSE_ID","등록 상품 번호를 확인하지 못했습니다.",Instant.now(),step.bodyJson());}
        return new Result(State.ACCEPTED,mapping,"ACCEPTED","스마트스토어에 접수되었습니다. 재조회로 반영 여부를 확인합니다.",Instant.now(),step.bodyJson());
    }
    private Mapping mapping(JsonNode source,NaverEditor.Input input,Mapping previous){
        String origin=NaverEditPatch.id(source.path("originProductNo"));if(origin==null)origin=NaverEditPatch.id(source.path("originProduct").path("originProductNo"));if(origin==null&&previous!=null)origin=previous.sellerProductId();if(origin==null)throw invalid("등록 상품 번호를 확인해 주세요.");
        String channel=NaverEditPatch.id(source.path("smartstoreChannelProductNo"));if(channel==null)channel=NaverEditPatch.id(source.path("smartstoreChannelProduct").path("channelProductNo"));if(channel==null&&previous!=null)channel=previous.channelProductId();
        var links=new ArrayList<OptionMapping>();var rows=source.path("originProduct").path("detailAttribute").path("optionInfo").path("optionCombinations");
        for(var option:input.options()){
            var candidates=new ArrayList<JsonNode>();String remote=remote(previous,option.id());for(var row:rows){boolean match=remote!=null?remote.equals(NaverEditPatch.id(row.path("id"))):optionMatches(option,row);if(match)candidates.add(row);}
            if(candidates.size()==1){String id=NaverEditPatch.id(candidates.getFirst().path("id"));if(id!=null)links.add(new OptionMapping(option.id(),id,null));}
            else if(candidates.size()>1)throw invalid("옵션 연결이 중복되었습니다. 다시 조회해 주세요.");
            else if(remote!=null)links.add(new OptionMapping(option.id(),remote,null));
        }
        return new Mapping(accountKey(),origin,List.copyOf(links),channel);
    }
    private boolean optionMatches(NaverEditor.Option option,JsonNode row){for(int i=0;i<option.values().size();i++)if(!Objects.equals(option.values().get(i),row.path("optionName"+(i+1)).asString("")))return false;return Objects.equals(option.sellerManagerCode(),row.path("sellerManagerCode").asString(""));}
    private static String remote(Mapping mapping,String local){if(mapping!=null&&mapping.options()!=null)for(var link:mapping.options())if(local.equals(link.optionId()))return link.sellerProductItemId();return null;}
    private void checkAccount(Mapping mapping){if(mapping!=null&&!accountKey().equals(mapping.accountKey()))throw invalid("스마트스토어 연결 계정이 변경되었습니다. 다시 조회해 주세요.");}
    private ObjectNode wire(Long actor,NaverEditor.Input input,Mapping mapping,JsonNode source,List<MarketplaceEditing.Change> changes,boolean create){return wire(actor,input,mapping,source,changes,create,null);}
    private ObjectNode wire(Long actor,NaverEditor.Input input,Mapping mapping,JsonNode source,List<MarketplaceEditing.Change> changes,boolean create,JsonNode resolvedImages){
        if(!create)NaverOptionStockUpdates.requireGeneralUpdateSafe(source);
        var body=json.createObjectNode();body.set("originProduct",source==null?json.createObjectNode():source.path("originProduct").deepCopy());body.set("smartstoreChannelProduct",source==null?json.createObjectNode():source.path("smartstoreChannelProduct").deepCopy());
        for(var e:input.fields().entrySet())if(create||changes==null||changedField(changes,e.getKey()))NaverEditPatch.put(body,e.getKey(),json.valueToTree(e.getValue()));
        var origin=body.path("originProduct").asObject();if(!origin.path("detailAttribute").isObject())origin.set("detailAttribute",json.createObjectNode());var detail=origin.path("detailAttribute").asObject();

        String descriptionValue=create||changed(changes,"description")?description(actor,input.description(),source==null?"":source.path("originProduct").path("detailContent").asString("")):null;
        if(create||changed(changes,"images")){
            var existingUrls=new HashSet<String>();if(source!=null){existingUrls.add(source.path("originProduct").path("images").path("representativeImage").path("url").asString(""));for(var old:source.path("originProduct").path("images").path("optionalImages"))existingUrls.add(old.path("url").asString(""));}
            for(var image:input.images())if(image.assetId()!=null||!existingUrls.contains(image.url())){
                if(inspection!=null)inspection.require(actor,List.of(new cc.ataglace.molebutter.media.api.ImageInspection.Input("NAVER",image.representative()?"REPRESENTATION":"DETAIL",null,image.id(),image.url(),image.assetId(),false)));if(image.assetId()!=null){if(assets.editedMetadata(actor,image.assetId())==null)throw invalid("기존 로컬 이미지를 R2 이미지로 다시 선택해 주세요.");}else r2.resolve(actor,image.url());
            }
            if(resolvedImages!=null&&resolvedImages.isObject())origin.set("images",resolvedImages.deepCopy());
            else{
                var images=json.createObjectNode();var optional=json.createArrayNode();
                for(var image:input.images().stream().sorted(Comparator.comparingInt(NaverEditor.Image::order)).toList()){
                    String url=image.assetId()==null&&existingUrls.contains(image.url())?image.url():gateway.uploadImage(actor,image);var row=json.createObjectNode().put("url",url);if(image.representative())images.set("representativeImage",row);else optional.add(row);
                }images.set("optionalImages",optional);origin.set("images",images);
            }
        }
        if(create||changed(changes,"description"))origin.put("detailContent",descriptionValue);else origin.remove("detailContent");
        boolean optionChanged=create||changed(changes,"optionNames")||changed(changes,"options")||changed(changes,"optionMode");
        if(optionChanged&&"COMBINATION".equals(input.optionMode())){
            var info=detail.path("optionInfo").isObject()?detail.path("optionInfo").deepCopy().asObject():json.createObjectNode();var names=json.createObjectNode();for(int i=0;i<input.optionNames().size();i++)names.put("optionGroupName"+(i+1),input.optionNames().get(i));info.set("optionCombinationGroupNames",names);info.put("useStockManagement",true);
            var rows=json.createArrayNode();long total=0;for(var option:input.options()){
                String remote=remote(mapping,option.id());var row=json.createObjectNode();if(source!=null&&remote!=null)for(var old:source.path("originProduct").path("detailAttribute").path("optionInfo").path("optionCombinations"))if(remote.equals(NaverEditPatch.id(old.path("id"))))row=old.deepCopy().asObject();
                if(remote!=null)row.put("id",Long.parseLong(remote));for(int i=1;i<=3;i++)row.remove("optionName"+i);for(int i=0;i<option.values().size();i++)row.put("optionName"+(i+1),option.values().get(i));
                if(option.price()!=null)row.put("price",option.price());if(option.stockQuantity()!=null)row.put("stockQuantity",option.stockQuantity());row.put("sellerManagerCode",option.sellerManagerCode());row.put("usable",option.usable()==null||option.usable());rows.add(row);if(option.usable()==null||option.usable())total=Math.addExact(total,option.stockQuantity()==null?0:option.stockQuantity());
            }info.set("optionCombinations",rows);detail.set("optionInfo",info);origin.put("stockQuantity",total);
        }else if(create&&"NONE".equals(input.optionMode()))detail.remove("optionInfo");
        // The update contract accepts SALE/SUSPENSION; existing zero stock can be retained by omission.
        // Do not silently turn approval/closed/prohibited states into SALE while editing an unrelated field.
        if(!create&&!changedField(changes,"originProduct.statusType")&&origin.path("statusType").asString("").equals("OUTOFSTOCK")&&origin.path("stockQuantity").isIntegralNumber()&&origin.path("stockQuantity").asLong()==0)origin.put("statusType","SALE");
        // Sending a copied quantity can undo an order placed between our GET and PUT.
        // Combination edits intentionally send their recomputed total; unrelated edits do not.
        if(!create&&!changedField(changes,"originProduct.stockQuantity")&&!(optionChanged&&"COMBINATION".equals(input.optionMode())))origin.remove("stockQuantity");
        clean(body);return body;
    }
    private String description(Long actor,String value,String previous){if(inspection!=null)inspection.require(actor,cc.ataglace.molebutter.media.api.ImageInspection.descriptionInputs("NAVER",value,previous));return cc.ataglace.molebutter.media.api.EditedImages.description(actor,value,previous,r2,assets);}
    private static void clean(ObjectNode body){var origin=body.path("originProduct").asObject();for(String key:List.of("originProductNo","createdDate","modifiedDate","lastModifiedDate","groupProductNo"))origin.remove(key);var channel=body.path("smartstoreChannelProduct");if(channel.isObject())for(String key:List.of("channelProductNo","channelNo","originProductNo","createdDate","modifiedDate"))channel.asObject().remove(key);}
    private boolean createMatches(JsonNode expected,JsonNode source){
        if(!source.path("originProduct").isObject())return false;var body=source.deepCopy();var origin=body.path("originProduct").asObject();if(!origin.has("saleType"))origin.put("saleType","NEW");var detail=origin.path("detailAttribute");if(detail.isObject()&&!detail.has("taxType"))detail.asObject().put("taxType","TAX");var channel=body.path("smartstoreChannelProduct");if(channel.isObject()&&!channel.has("storeKeepExclusiveProduct"))channel.asObject().put("storeKeepExclusiveProduct",false);for(String key:List.of("name","leafCategoryId","salePrice","detailContent","images"))if(!contains(body.path("originProduct").path(key),expected.path("originProduct").path(key)))return false;
        for(var e:expected.path("originProduct").properties())if(!Set.of("detailAttribute","statusType").contains(e.getKey())&&!contains(body.path("originProduct").path(e.getKey()),e.getValue()))return false;
        for(var e:expected.path("originProduct").path("detailAttribute").properties())if(!e.getKey().equals("optionInfo")&&!contains(body.path("originProduct").path("detailAttribute").path(e.getKey()),e.getValue()))return false;
        for(var e:expected.path("smartstoreChannelProduct").properties()){
            if(e.getKey().equals("channelProductName")&&e.getValue().asString("").isBlank())continue;
            var actual=body.path("smartstoreChannelProduct").path(e.getKey());
            // Shopping enrollment is conditional: Naver stores false for non-advertisers even when true is requested.
            // Only this documented boolean normalization is valid; absence, invalid types and false -> true still conflict.
            if(e.getKey().equals("naverShoppingRegistration")&&e.getValue().isBoolean()&&e.getValue().asBoolean()&&actual.isBoolean()&&!actual.asBoolean())continue;
            if(!contains(actual,e.getValue()))return false;
        }
        var options=expected.path("originProduct").path("detailAttribute").path("optionInfo").path("optionCombinations");var actual=body.path("originProduct").path("detailAttribute").path("optionInfo").path("optionCombinations");if(options.size()!=actual.size())return false;for(var wanted:options){int matches=0;for(var row:actual){boolean equal=true;for(var e:wanted.properties()){
            if(e.getKey().equals("id"))continue;
            if(e.getKey().equals("sellerManagerCode")&&e.getValue().asString("").isEmpty()&&(row.path(e.getKey()).isMissingNode()||row.path(e.getKey()).isNull()))continue;
            if(e.getKey().equals("usable")&&e.getValue().asBoolean()&&row.path(e.getKey()).isMissingNode())continue;
            if(!contains(row.path(e.getKey()),e.getValue()))equal=false;
        }if(equal)matches++;}if(matches!=1)return false;}return true;
    }
    private static boolean contains(JsonNode actual,JsonNode expected){
        if(expected.isMissingNode())return true;if(expected.isObject()){if(expected.isEmpty())return actual.isObject()||actual.isMissingNode()||actual.isNull();if(!actual.isObject())return false;for(var e:expected.properties())if(!contains(actual.path(e.getKey()),e.getValue()))return false;return true;}
        if(expected.isArray()){if(expected.isEmpty()&&(actual.isMissingNode()||actual.isNull()))return true;if(!actual.isArray()||actual.size()!=expected.size())return false;for(int i=0;i<expected.size();i++)if(!contains(actual.get(i),expected.get(i)))return false;return true;}
        if(actual.isNumber()&&expected.isNumber())try{return new BigDecimal(actual.asString()).compareTo(new BigDecimal(expected.asString()))==0;}catch(RuntimeException e){return false;}
        return actual.equals(expected);
    }
    private static boolean changed(List<MarketplaceEditing.Change> changes,String key){return changes!=null&&changes.stream().anyMatch(c->c.path().equals(NaverEditPatch.PREFIX+key));}
    private static boolean changedField(List<MarketplaceEditing.Change> changes,String path){return changes.stream().anyMatch(c->c.path().equals(NaverEditPatch.PREFIX+"fields."+path));}
    private static boolean matches(JsonNode value,JsonNode expected){return NaverEditPatch.jsonEquivalent(value,expected);}
    private List<Change> visible(NaverEditor.Input before,NaverEditor.Input after){var changes=new ArrayList<Change>();if(before==null){for(var e:after.fields().entrySet())changes.add(new Change(e.getKey(),"",show(e.getValue())));changes.add(new Change("옵션","",Integer.toString(after.options().size())));changes.add(new Change("이미지","",Integer.toString(after.images().size())));}else for(var c:NaverEditPatch.diff(before,after))changes.add(new Change(c.path().substring(NaverEditPatch.PREFIX.length()),show(NaverEditPatch.value(before,c)),show(c.value())));return List.copyOf(changes);}
    private static String show(Object value){if(value==null)return "";String s=Objects.toString(value);return s.length()>500?s.substring(0,500)+"…":s;}
    private static String sellerCode(JsonNode body){return body.path("originProduct").path("detailAttribute").path("sellerCodeInfo").path("sellerManagementCode").asString("");}
    private List<String> existingProducts(String code){
        if(code.isBlank())return List.of();var result=new LinkedHashSet<String>();int page=1;
        while(true){var query=json.createObjectNode().put("searchKeywordType","SELLER_CODE").put("sellerManagementCode",code).put("page",page).put("size",100);var found=gateway.search(query);if(found==null||!found.path("contents").isArray())throw new MarketplaceFailure(MarketplaceFailure.Kind.RESPONSE,"NAVER");for(var row:found.path("contents")){String id=NaverEditPatch.id(row.path("originProductNo"));if(id!=null)result.add(id);}if(found.path("last").asBoolean(false)||page>=found.path("totalPages").asInt(1))break;if(++page>1000)throw invalid("등록 확인을 위한 검색 범위가 너무 큽니다. 판매자 관리 코드를 구분해 주세요.");}
        return List.copyOf(result);
    }
    private Result failure(Mapping mapping,String code){return new Result(State.FAILED,mapping,code,"스마트스토어 저장을 진행할 수 없습니다. 입력 값과 최신 조회 결과를 확인해 주세요.",Instant.now());}
    private static InputValidationFailure invalid(String message){return new InputValidationFailure(message);}
    private void validate(ObjectNode body,boolean create,NaverEditor.Input input){validation.validate(body,create,input);}
    private void validate(ObjectNode body,boolean create,NaverEditor.Input input,List<MarketplaceEditing.Change> changes){validation.validate(body,create,input,changes);}
}
