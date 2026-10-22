package cc.ataglace.molebutter.coupang.internal.client;
import cc.ataglace.molebutter.coupang.api.CoupangWriteGateway;
import cc.ataglace.molebutter.coupang.api.CoupangPrices;

import cc.ataglace.molebutter.media.api.ImageAssets;
import cc.ataglace.molebutter.media.api.R2Images;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.coupang.api.CoupangWorkingCopies.*;
import cc.ataglace.molebutter.coupang.api.CoupangInputs;
import cc.ataglace.molebutter.coupang.api.CoupangWorkingCopyDocuments;
import cc.ataglace.molebutter.coupang.api.CoupangFailure;
import cc.ataglace.molebutter.coupang.api.CoupangEditing;
import cc.ataglace.molebutter.coupang.api.CoupangCatalog;
import cc.ataglace.molebutter.coupang.api.CoupangBrands;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * Builds immutable wire snapshots from fresh remote state, never from a
 * browser's raw JSON.
 */
@Component
final class DefaultCoupangWriteGateway implements CoupangWriteGateway {
    private static final Duration CREATION_TRANSITION_GAP = Duration.ofSeconds(5);
    private static final Duration APPROVAL_CHECK_WINDOW = Duration.ofMinutes(10);
    private static final int MAX_APPROVAL_CHECKS = 120;
    private final BusinessAccess access;
    private final CoupangProductClient client;
    private final ImageAssets assets;
    private final R2Images r2;
    private final ObjectMapper json;
    private final CoupangBrands brands;
    private final cc.ataglace.molebutter.media.api.ImageInspection inspection;
    private static final Set<String> NUMBERS = Set.of("displayCategoryCode", "sellerProductId", "sellerProductItemId",
            "vendorItemId", "outboundShippingPlaceCode", "deliveryCharge", "freeShipOverAmount",
            "deliveryChargeOnReturn", "returnCharge", "originalPrice", "salePrice", "maximumBuyCount",
            "maximumBuyForPerson", "maximumBuyForPersonPeriod", "outboundShippingTimeDay", "unitCount",
            "autoPricingInfo.minSalePrice");
    private static final Set<String> BOOLS = Set.of("requested", "pccNeeded", "bestPriceGuaranteed3P", "emptyBarcode",
            "autoPricingInfo.active");
    private static final Set<String> SETTINGS = Set.of("saleStartedAt", "saleEndedAt", "manufacture", "brandId",
            "productGroup", "extraInfoMessage", "bundleInfo.bundleType");
    private static final Set<String> REGISTRATION = Set.of("externalVendorSku", "originalPrice", "salePrice",
            "maximumBuyCount", "maximumBuyForPerson", "maximumBuyForPersonPeriod", "outboundShippingTimeDay",
            "unitCount", "adultOnly", "taxType", "parallelImported", "overseasPurchased", "pccNeeded",
            "bestPriceGuaranteed3P", "barcode", "emptyBarcode", "emptyBarcodeReason", "modelNo", "searchTags",
            "offerCondition", "offerDescription", "autoPricingInfo.minSalePrice", "autoPricingInfo.active");
    DefaultCoupangWriteGateway(BusinessAccess access, CoupangProductClient client, ImageAssets assets,
            ObjectMapper json, CoupangBrands brands, R2Images r2) {
        this(access, client, assets, json, brands, r2, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    DefaultCoupangWriteGateway(BusinessAccess access, CoupangProductClient client, ImageAssets assets,
            ObjectMapper json, CoupangBrands brands, R2Images r2,
            cc.ataglace.molebutter.media.api.ImageInspection inspection) {
        this.r2 = r2;
        this.access = access;
        this.client = client;
        this.assets = assets;
        this.json = json;
        this.brands = brands;
        this.inspection = inspection;
    }
    private Prepared withApproval(Prepared prepared) {
        var parent = prepared.steps().stream().filter(s -> s.type() == Type.CREATE || s.type() == Type.PRODUCT)
                .findFirst().orElse(null);
        if (parent == null)
            return prepared;
        var steps = new ArrayList<>(prepared.steps());
        steps.add(new Step(UUID.randomUUID().toString(), Type.APPROVAL, null, "PUT",
                CoupangProductClient.PATH + "/{sellerProductId}/approvals", "", null, "{}",
                json.writeValueAsString(parent)));
        return new Prepared(prepared.accountKey(), prepared.mapping(), List.copyOf(steps),
                prepared.changes().stream().filter(c -> !"requested".equals(c.path())).toList(), prepared.preparedAt(),
                prepared.expectedSkus(), prepared.editIntent(), prepared.schemaVersion());
    }
    private static boolean approvalFollows(Prepared p, Step step) {
        return p.steps().stream().anyMatch(s -> s.type() == Type.APPROVAL) && (step.type() == Type.CREATE
                || step.type() == Type.PRODUCT && step.path().equals(CoupangProductClient.PATH));
    }
    private Result saved(Prepared p, Step step, Result result) {
        return approvalFollows(p, step) && result.state() == State.ACCEPTED && "SUCCESS".equals(result.code())
                ? new Result(State.CONFIRMED, result.mapping(), "SAVED_FOR_APPROVAL",
                        "쿠팡 저장을 완료했습니다. 이어서 판매 승인을 요청합니다.", result.attemptedAt(), result.requestJson())
                : result;
    }
    @Override
    public String accountKey() {
        return blank(client.vendorId()) ? null : client.accountKey();
    }
    public Prepared prepare(Long actor, Document input, Mapping mapping, Document observed,
            List<CoupangEditing.Change> rawChanges) {
        var changes=CoupangPrices.changes(rawChanges);
        access.productActor(actor, true);
        var document = CoupangWorkingCopyDocuments.normalize(input, json);
        if (mapping != null && !Objects.equals(client.accountKey(), mapping.accountKey()))
            throw invalid("쿠팡 계정이 변경되었습니다.");
        for (var c : changes) {
            CoupangInputs.checkCurrent(c);
            if (!CoupangInputs.matches(json, document, c))
                throw invalid("수정 항목과 입력값이 일치하지 않습니다.");
        }
        return client.operation(actor,null,()->client.writeSession(() -> withApproval(build(actor, document, mapping, observed, changes))));
    }
    public Prepared prepareStoredOption(Long actor,Document input,Mapping mapping,Document observed,List<CoupangEditing.Change> rawEdits) {
        var edits=CoupangPrices.changes(rawEdits);
        input=CoupangWorkingCopyDocuments.normalize(input,json);
        access.productActor(actor,true);
        if(mapping==null||!Objects.equals(client.accountKey(),mapping.accountKey())||edits==null||edits.size()!=1)throw invalid("쿠팡 수정 대상을 확인해 주세요.");
        var edit=edits.getFirst();CoupangInputs.checkCurrent(edit);
        if(!CoupangInputs.matches(json,input,edit))throw invalid("수정 항목과 입력값이 일치하지 않습니다.");
        Type type=switch(edit.path()){case "options.registration.salePrice"->Type.PRICE;case "options.registration.maximumBuyCount"->Type.STOCK;default->throw invalid("단일 가격·재고 수정만 허용됩니다.");};
        var link=optionMapping(mapping,edit.optionId());
        var before=observed.options().stream().filter(o->o.id().equals(edit.optionId())).findFirst().orElseThrow(()->invalid("옵션이 변경되었습니다."));
        String field=type==Type.PRICE?"salePrice":"maximumBuyCount",value=Objects.toString(edit.value(),"");
        validateWireField(field,value);if(type==Type.PRICE&&number(value).mod(BigInteger.TEN).signum()!=0)throw invalid("가격은 10원 단위로 입력해 주세요.");
        String previous=before.registration().get(field);if(previous==null)throw invalid("현재 판매값이 미확인입니다.");
        var inventory=json.createObjectNode();inventory.set(type==Type.PRICE?"salePrice":"amountInStock",json.readTree(number(previous).toString()));
        var step=currentStep(type,edit.optionId(),link.vendorItemId(),value,inventory);
        var changes=new ArrayList<Change>();diff("",json.readTree(step.baselineJson()),json.readTree(step.expectedJson()),changes);
        return new Prepared(mapping.accountKey(),mapping,numericEqual(value,inventory.path(type==Type.PRICE?"salePrice":"amountInStock"))?List.of():List.of(step),List.copyOf(changes),client.now(),List.of(),new EditIntent(observed,List.copyOf(edits)),4);
    }
    private Prepared build(Long actor,Document input,Mapping mapping,Document observed,List<CoupangEditing.Change> edits) {return build(actor,input,mapping,observed,edits,false);}
    private Prepared build(Long actor, Document input, Mapping mapping, Document observed,
            List<CoupangEditing.Change> edits,boolean quickEdit) {
        boolean create = mapping == null || mapping.sellerProductId() == null;
        if (create)
            validateCreation(input);
        var creationRules = create ? client.creationCategory(input.basic().get("displayCategoryCode")) : null;
        ObjectNode raw = create
                ? json.createObjectNode()
                : client.rawProduct(mapping.sellerProductId()).deepCopy().asObject();
        ObjectNode desired = raw.deepCopy();
        var steps = new ArrayList<Step>();
        var changes = new ArrayList<Change>();
        var links = new ArrayList<OptionMapping>();
        var skus = new ArrayList<String>();
        var source = create ? null : client.editorDocument(raw, new HashMap<>(), new HashMap<>());
        if (!create) {
            CoupangInputs.from(source, mapping);
            if (!mapping.sellerProductId().equals(source.basic().sellerProductId()))
                throw invalid("쿠팡 상품 ID가 변경되었습니다.");
            if (!Set.of("승인완료", "APPROVED", "임시저장중", "임시저장", "SAVED", "승인반려", "REJECTED", "부분승인완료")
                    .contains(Objects.toString(source.basic().statusName(), "")))
                throw invalid("현재 쿠팡 상품 상태에서는 수정할 수 없습니다.");
        }
        final Document d = create ? input : CoupangInputs.apply(json, CoupangInputs.from(source, mapping), edits);
        var identity = create ? mapping : CoupangInputs.enrich(mapping, source);
        for (var e : d.basic().entrySet())
            if (create || selected(edits, "basic." + e.getKey(), null)) {
                if (!create && Set.of("displayCategoryCode", "brand").contains(e.getKey())
                        && !Objects.equals(e.getValue(), scalar(raw.path(e.getKey()))))
                    throw invalid("카테고리·브랜드는 등록상품에서 변경할 수 없습니다.");
                if (Set.of("sellerProductName", "displayProductName").contains(e.getKey())
                        && (e.getKey().equals("sellerProductName") && e.getValue().isBlank()
                                || e.getValue().codePointCount(0, e.getValue().length()) > 100))
                    throw invalid("상품명은 1~100자로 입력해 주세요.");
                if (!create || !blank(e.getValue()))
                    set(desired, e.getKey(), e.getValue());
            }
        for (var e : d.settings().entrySet())
            if (create || selected(edits, "settings." + e.getKey(), null)) {
                if (!SETTINGS.contains(e.getKey())) {
                    if (create)
                        throw invalid("지원하지 않는 쿠팡 설정입니다.");
                    continue;
                }
                validateWireField(e.getKey(), e.getValue());
                if (!create && e.getKey().equals("brandId")
                        && !Objects.equals(e.getValue(), scalar(raw.path("brandId"))))
                    throw invalid("기존 브랜드 ID는 변경할 수 없습니다.");
                set(desired, e.getKey(), e.getValue());
            }
        if (create) {
            set(desired, "vendorId", client.vendorId());
            set(desired, "vendorUserId", requireRegistrationUser());
            brands.requireSelection(actor, d.settings().get("brandId"), d.basic().get("brand"));
        }
        if (create || edits.stream().anyMatch(c -> c.path().startsWith("delivery.")))
            regional(d, create);
        var items = json.createArrayNode();
        var sourceItems = raw.path("items");
        if (!create && sourceItems.size() != d.options().size())
            throw invalid("쿠팡 옵션 구성이 변경되었습니다.");
        var seen = new HashSet<String>();
        for (var o : d.options()) {
            var link = create ? new OptionMapping(o.id(), null, null) : optionMapping(identity, o.id());
            ObjectNode old = null;
            if (!create) {
                for (var r : sourceItems)
                    if (Objects.equals(link.sellerProductItemId(), scalar(r.path("sellerProductItemId"))))
                        old = r.deepCopy().asObject();
                if (old == null || !seen.add(link.sellerProductItemId())
                        || !Objects.equals(link.vendorItemId(), optionalId(old.path("vendorItemId"))))
                    throw invalid("쿠팡 옵션 ID가 변경되었습니다.");
            }
            ObjectNode item = old == null ? json.createObjectNode() : old.deepCopy();
            links.add(link);
            if(quickEdit&&edits.stream().anyMatch(edit->Objects.equals(edit.optionId(),o.id()))) {
                var before=observed.options().stream().filter(v->Objects.equals(v.id(),o.id())).findFirst().orElseThrow(()->invalid("옵션이 변경되었습니다."));
                String oldCode=Objects.toString(before.registration().get("externalVendorSku"),"").trim().toUpperCase(Locale.ROOT);
                String newCode=Objects.toString(o.registration().get("externalVendorSku"),"").trim().toUpperCase(Locale.ROOT);
                if(!oldCode.equals(newCode))throw invalid("판매자상품코드가 변경되었습니다. 다시 확인해 주세요.");
            }
            var attributes = o.attributes();
            if (create)
                set(item, "itemName", o.itemName());
            else if (selected(edits, "options.itemName", o.id())
                    && !Objects.equals(o.itemName(), scalar(old.path("itemName"))))
                throw invalid("기존 옵션명은 구매 속성으로 수정해 주세요.");
            for (var e : o.registration().entrySet())
                if (create || selected(edits, "options.registration." + e.getKey(), o.id())) {
                    String key = e.getKey();
                    if (!REGISTRATION.contains(key)) {
                        if (create)
                            throw invalid("지원하지 않는 쿠팡 옵션 설정입니다.");
                        continue;
                    }
                    validateWireField(key, e.getValue());
                    if (!create && link.vendorItemId() != null
                            && Set.of("salePrice", "maximumBuyCount", "originalPrice").contains(key)) {
                        Type type = key.equals("salePrice")
                                ? Type.PRICE
                                : key.equals("maximumBuyCount") ? Type.STOCK : Type.ORIGINAL_PRICE;
                        if (type != Type.STOCK && number(e.getValue()).mod(BigInteger.TEN).signum() != 0)
                            throw invalid("가격은 10원 단위로 입력해 주세요.");
                        var inventory = type == Type.ORIGINAL_PRICE
                                ? old
                                : client.rawInventory(link.vendorItemId(), type == Type.PRICE, type == Type.STOCK);
                        String field = type == Type.STOCK ? "amountInStock" : key;
                        if (!inventory.path(field).isIntegralNumber())
                            throw invalid("현재 가격·재고 조회 결과를 확인해 주세요.");
                        if (!numericEqual(e.getValue(), inventory.path(field)))
                            steps.add(currentStep(type, o.id(), link.vendorItemId(), e.getValue(), inventory));
                        continue;
                    }
                    if (!create && link.vendorItemId() != null && key.startsWith("autoPricingInfo.")
                            && !Objects.equals(e.getValue(), scalar(at(old, key))))
                        throw invalid("승인 옵션의 자동 가격 설정은 변경할 수 없습니다.");
                    set(item, key, e.getValue());
                }
            if (create || selected(edits, "options.attributes", o.id())) {
                var before = create
                        ? List.<CoupangCatalog.Attribute>of()
                        : CoupangPurchaseAttributes.read(old.path("attributes"));
                var rules = create ? creationRules : client.rawCategory(d.basic().get("displayCategoryCode"));
                item.set("attributes", create
                        ? attributeRows(o)
                        : CoupangPurchaseAttributes.patch(json, old.path("attributes"), before, attributes, rules));
            }
            if (create || selected(edits, "options.notices", o.id()))
                item.set("notices",
                        create
                                ? noticeRows(o)
                                : patchRows(old.path("notices"), edits, "options.notices", o.id(),
                                        List.of("noticeCategoryName", "noticeCategoryDetailName"),
                                        Map.of("content", "content")));
            if (create || selected(edits, "options.certifications", o.id())) {
                if (create && o.certifications().stream()
                        .anyMatch(c -> c.attachments() != null && !c.attachments().isEmpty()))
                    throw invalid("인증 첨부 수정은 지원하지 않습니다.");
                item.set("certifications",
                        create
                                ? certificationRows(o)
                                : patchRows(old.path("certifications"), edits, "options.certifications", o.id(),
                                        List.of("certificationType"), Map.of("code", "certificationCode")));
            }
            if (create || selected(edits, "options.images", o.id())) {
                var images = images(actor, d, o, old, create ? null : observed);
                if (images != null)
                    item.set("images", images);
            }
            if (create || selected(edits, "options.contents", o.id())) {
                var content = contents(actor, d, o, old, create ? null : observed);
                item.set("contents", old == null ? content : mergeContents(old.path("contents"), content));
            }
            if (create)
                item.put("itemName", CoupangPurchaseAttributes.itemName(item.path("attributes")));
            items.add(item);
            String sku = scalar(item.path("externalVendorSku"));
            if (!blank(sku))
                skus.add(sku);
        }
        if (!create) {
            var ordered = json.createArrayNode();
            for (var r : sourceItems)
                for (var i : items)
                    if (r.path("sellerProductItemId").equals(i.path("sellerProductItemId")))
                        ordered.add(i);
            items = ordered;
        }
        desired.set("items", items);
        if (create || selected(edits, "documents", null))
            desired.set("requiredDocuments",
                    create
                            ? documentRows(d, raw.path("requiredDocuments"))
                            : patchRows(raw.path("requiredDocuments"), edits, "documents", null,
                                    List.of("templateName"),
                                    Map.of("path", "documentPath", "vendorPath", "vendorDocumentPath")));
        if (create) {
            for (var e : d.delivery().entrySet())
                if (!Set.of("jeju", "notJeju").contains(e.getKey())) {
                    validateWireField(e.getKey(), e.getValue());
                    set(desired, e.getKey(), e.getValue());
                }
            desired.put("requested", false);
            CoupangPurchaseAttributes.create(items, creationRules);
            if (skus.isEmpty())
                throw invalid("판매자 상품코드를 입력해 주세요.");
            steps.add(new Step(UUID.randomUUID().toString(), Type.CREATE, null, "POST", CoupangProductClient.PATH, "",
                    wire(desired), wire(json.createObjectNode().put("creationRulesCheckedAt", creationRules.checkedAt())), wire(desired)));
        } else {
            if (!raw.equals(desired)) {
                desired.put("requested", false);
                steps.add(new Step(UUID.randomUUID().toString(), Type.PRODUCT, null, "PUT", CoupangProductClient.PATH,
                        "", wire(desired), wire(raw), wire(delta(raw, desired))));
            }
            var before = json.createObjectNode();
            var after = json.createObjectNode();
            set(after, "sellerProductId", mapping.sellerProductId());
            for (var e : d.delivery().entrySet())
                if (selected(edits, "delivery." + e.getKey(), null)
                        && !Set.of("jeju", "notJeju").contains(e.getKey())) {
                    validateWireField(e.getKey(), e.getValue());
                    before.set(e.getKey(), raw.path(e.getKey()));
                    set(after, e.getKey(), e.getValue());
                }
            var comparison = after.deepCopy();
            comparison.remove("sellerProductId");
            if (!before.equals(comparison)) {
                if (Set.of("SAVED", "APPROVING", "임시저장중", "승인대기중").contains(source.basic().statusName()))
                    throw invalid("현재 상품 상태에서는 배송을 수정할 수 없습니다.");
                steps.add(new Step(UUID.randomUUID().toString(), Type.DELIVERY, null, "PUT",
                        CoupangProductClient.PATH + "/" + mapping.sellerProductId() + "/partial", "", wire(after),
                        wire(before), wire(comparison)));
            }
        }
        for (var s : steps)
            diff("", s.type() == Type.CREATE ? json.createObjectNode() : json.readTree(s.baselineJson()), json.readTree(s.expectedJson()), changes);
        steps.sort(Comparator.comparingInt(s -> switch (s.type()) {
            case DELIVERY -> 0;
            case ORIGINAL_PRICE -> 1;
            case PRICE -> 2;
            case STOCK -> 3;
            case PRODUCT -> 4;
            case CREATE -> 5;
            case APPROVAL -> 6;
            case SALE_STOP, SALE_RESUME, DELETE -> 7;
        }));
        return new Prepared(client.accountKey(),
                new Mapping(client.accountKey(), create ? null : mapping.sellerProductId(), List.copyOf(links)),
                List.copyOf(steps), List.copyOf(changes), client.now(),
                skus.isEmpty() ? List.of() : List.of(skus.getFirst()),
                new EditIntent(create ? d : observed, List.copyOf(edits)), 4);
    }
    private void validateCreation(Document d) {
        for (String key : List.of("sellerProductName", "brand", "displayCategoryCode"))
            if (blank(d.basic().get(key)))
                throw invalid("쿠팡 기본 정보의 " + key + "를 입력해 주세요.");
        for (String key : List.of("brandId", "saleStartedAt", "saleEndedAt"))
            if (blank(d.settings().get(key)))
                throw invalid("쿠팡 설정의 " + key + "를 입력해 주세요.");
        if (d.settings().get("saleStartedAt").compareTo(d.settings().get("saleEndedAt")) > 0)
            throw invalid("판매 종료일시는 시작 이후여야 합니다.");
        for (String key : List.of("deliveryMethod", "deliveryCompanyCode", "deliveryChargeType", "deliveryCharge",
                "freeShipOverAmount", "deliveryChargeOnReturn", "returnCharge", "remoteAreaDeliverable",
                "unionDeliveryType", "outboundShippingPlaceCode", "returnCenterCode", "returnChargeName",
                "companyContactNumber", "returnZipCode", "returnAddress", "returnAddressDetail"))
            if (blank(d.delivery().get(key)))
                throw invalid("배송·반품의 " + key + "를 입력해 주세요.");
        if (!d.delivery().get("deliveryCompanyCode").matches("[A-Z][A-Z0-9_]*"))
            throw invalid("택배사 코드를 입력해 주세요.");
        for (var o : d.options())
            for (String key : List.of("originalPrice", "salePrice", "maximumBuyCount", "maximumBuyForPerson",
                    "maximumBuyForPersonPeriod", "outboundShippingTimeDay", "unitCount", "adultOnly", "taxType",
                    "parallelImported", "overseasPurchased", "pccNeeded"))
                if (blank(o.registration().get(key)))
                    throw invalid("옵션의 " + key + "를 입력해 주세요.");
    }
    private void regional(Document d, boolean create) {
        var values = d.delivery();
        if (!"Y".equals(values.get("remoteAreaDeliverable"))
                || !values.containsKey("jeju") && !values.containsKey("notJeju"))
            return;
        var code = values.get("outboundShippingPlaceCode");
        var place = create ? client.creationOutboundPlace(code) : client.rawOutboundPlace(code).items().stream()
                .filter(v -> v.usable() && v.code().equals(code)).findFirst().orElseThrow(() -> invalid("출고지를 확인해 주세요."));
        if (!place.usable() || !Objects.equals(place.code(), code)) throw invalid("출고지를 확인해 주세요.");
        var policies = place.remoteInfos().stream()
                .filter(v -> v.usable() && v.carrier().equals(values.get("deliveryCompanyCode"))).toList();
        if (policies.size() != 1)
            throw invalid("도서산간 배송비를 확인할 수 없습니다.");
        var p = policies.getFirst();
        if (values.containsKey("jeju") && !Objects.equals(values.get("jeju"), p.jeju())
                || values.containsKey("notJeju") && !Objects.equals(values.get("notJeju"), p.remote()))
            throw invalid("도서산간 배송비는 출고지 설정과 같아야 합니다.");
    }
    private static boolean selected(List<CoupangEditing.Change> changes, String path, String option) {
        return changes.stream()
                .anyMatch(c -> (c.path().equals(path) || c.rowKey() != null && c.path().startsWith(path + "."))
                        && Objects.equals(c.optionId(), option));
    }
    private String requireRegistrationUser() {
        String id = client.registrationUserId();
        if (blank(id))
            throw invalid(CoupangProductClient.REGISTRATION_USER_REQUIRED);
        return id;
    }

    private static boolean lifecycle(Type type){return Set.of(Type.SALE_STOP,Type.SALE_RESUME,Type.DELETE).contains(type);}
    public Prepared prepareAction(Long actor,Mapping mapping,Document observed,String action,String vendorItem) {
        access.productActor(actor,true);
        Type type=Type.valueOf(action);
        if(!lifecycle(type) || mapping==null || !Objects.equals(mapping.accountKey(),client.accountKey()))throw invalid("상품 작업과 계정을 확인해 주세요.");
        return client.operation(actor,null,()->client.writeSession(()->{
            var raw=client.rawProduct(mapping.sellerProductId());
            var source=client.editorDocument(raw,new HashMap<>(),new HashMap<>());
            var enriched=CoupangInputs.enrich(mapping,source);
            if(!mapping.options().equals(enriched.options()))throw invalid("옵션 ID가 변경되었습니다. 다시 진입해 주세요.");
            if(Set.of("APPROVING","승인대기중","IN_REVIEW","심사중").contains(raw.path("statusName").asString()))throw invalid("승인·심사 중에는 상품 작업을 진행할 수 없습니다.");
            var steps=new ArrayList<Step>();var changes=new ArrayList<Change>();
            boolean selected=false;
            for(var option:mapping.options()) {
                if(type!=Type.DELETE && vendorItem!=null && !vendorItem.equals(option.vendorItemId()))continue;
                selected=true;
                if(option.vendorItemId()==null){if(type==Type.DELETE)continue;throw invalid("승인된 옵션 ID가 필요합니다.");}
                var inventory=client.rawInventory(option.vendorItemId());
                boolean before=inventory.path("onSale").asBoolean();boolean desired=type==Type.SALE_RESUME;
                if(type==Type.DELETE && !before)continue;
                var stepType=type==Type.DELETE?Type.SALE_STOP:type;
                if(before==desired)continue;
                String path="/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/"+option.vendorItemId()+"/sales/"+(desired?"resume":"stop");
                steps.add(new Step(UUID.randomUUID().toString(),stepType,option.optionId(),"PUT",path,"",null,"{\"onSale\":"+before+"}","{\"onSale\":"+desired+"}"));
                changes.add(new Change("options["+option.vendorItemId()+"].onSale",Boolean.toString(before),Boolean.toString(desired)));
            }
            if(!selected && type!=Type.DELETE)throw invalid("대상 옵션이 없습니다.");
            if(type==Type.DELETE){steps.add(new Step(UUID.randomUUID().toString(),Type.DELETE,null,"DELETE",CoupangProductClient.PATH+"/"+mapping.sellerProductId(),"",null,wire(raw),"{\"deleted\":true}"));changes.add(new Change("deleted","false","true"));}
            return new Prepared(client.accountKey(),mapping,List.copyOf(steps),List.copyOf(changes),client.now(),List.of(),new EditIntent(observed,List.of()),4);
        }));
    }
    private Result executeLifecycle(Long actor,Prepared p,Step step,Mapping mapping,Instant at,java.util.function.Consumer<Step> beforeDispatch) {
        var sent=new java.util.concurrent.atomic.AtomicBoolean();var request=new java.util.concurrent.atomic.AtomicReference<String>();
        try{return client.operation(actor,null,()->client.writeSession(()->{
            JsonNode raw;
            try{raw=client.rawProduct(mapping.sellerProductId());}catch(cc.ataglace.molebutter.coupang.api.CoupangListingMissing deleted){
                if(step.type()==Type.DELETE)return result(State.CONFIRMED,mapping,"VERIFIED","상품 삭제를 확인했습니다.",at);throw deleted;}
            if(Set.of("APPROVING","승인대기중","IN_REVIEW","심사중").contains(raw.path("statusName").asString()))throw invalid("승인·심사 중인 상품입니다.");
            var actual=step;
            if(step.type()==Type.DELETE) {
                var baseline=json.readTree(step.baselineJson());
                var oldIds=new TreeSet<String>();for(var row:baseline.path("items"))oldIds.add(row.path("sellerProductItemId").asString()+":"+row.path("vendorItemId").asString());
                var newIds=new TreeSet<String>();for(var row:raw.path("items")) {
                    newIds.add(row.path("sellerProductItemId").asString()+":"+row.path("vendorItemId").asString());
                    String vendor=optionalId(row.path("vendorItemId"));if(vendor!=null && client.rawInventory(vendor).path("onSale").asBoolean())throw invalid("모든 옵션의 판매중지를 확인한 뒤 삭제해 주세요.");
                }
                if(!oldIds.equals(newIds))throw invalid("옵션 구성이 변경되었습니다. 삭제를 다시 준비해 주세요.");
                actual=new Step(step.id(),Type.DELETE,null,"DELETE",CoupangProductClient.PATH+"/"+mapping.sellerProductId(),"",null,wire(raw),step.expectedJson());
            } else {
                var option=optionMapping(mapping,step.optionId());var remote=remoteOption(raw,option);
                if(remote.isEmpty())throw invalid("옵션 ID가 변경되었습니다.");
                var inventory=client.rawInventory(option.vendorItemId());
                if(subset(json.readTree(step.expectedJson()),inventory))return result(State.CONFIRMED,mapping,"ALREADY_APPLIED","판매상태 반영을 확인했습니다.",at);
                actual=new Step(step.id(),step.type(),step.optionId(),"PUT","/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/"+option.vendorItemId()+"/sales/"+(step.type()==Type.SALE_RESUME?"resume":"stop"),"",null,wire(inventory),step.expectedJson());
            }
            access.productActor(actor,true);beforeDispatch.accept(actual);request.set(json.writeValueAsString(actual));sent.set(true);
            var retryStep=actual;
            var response=client.write(actual.method(),actual.path(),actual.query(),null,
                    () -> retryGuard(actor,retryStep,mapping,beforeDispatch));
            var out=response.status()==200?parseOutcome(response.body(),actual,mapping,at):httpFailure(response.status(),response.body(),mapping,at,actual.bodyJson());
            if(out.state()==State.ACCEPTED && "SUCCESS".equals(out.code())) {
                if(step.type()==Type.DELETE) {
                    var root=json.readTree(response.body());
                    if(mapping.sellerProductId().equals(optionalId(root.path("data")))) {
                        client.deletedReceipt(mapping.sellerProductId(),root);
                        out=result(State.CONFIRMED,mapping,"DELETE_CONFIRMED","상품 삭제를 확인했습니다.",at);
                    }else out=result(State.UNKNOWN,mapping,"INVALID_RESPONSE","삭제 결과의 상품 ID를 확인하지 못했습니다.",at);
                }else try{if(subset(json.readTree(step.expectedJson()),client.rawInventory(optionMapping(mapping,step.optionId()).vendorItemId())))out=result(State.CONFIRMED,mapping,"VERIFIED","판매상태 반영을 확인했습니다.",at);}catch(CoupangFailure ignored){}
            }
            return withRequest(out,request.get());
        }));}catch(InputValidationFailure rejected){return result(State.FAILED,mapping,"MAPPING_CHANGED",rejected.getMessage(),at);}
        catch(CoupangFailure failure){return withRequest(result(sent.get()?State.UNKNOWN:State.FAILED,mapping,failure.kind().name(),failure.getMessage(),at),request.get());}
    }
    public Result execute(Long actor, Prepared p, Step step, Mapping current,
            java.util.function.Consumer<Step> beforeDispatch) {
        access.productActor(actor, true);
        client.observationActor(actor);
        var at = client.now();
        var mapping = current == null ? p.mapping() : current;
        if (!validAccount(p, mapping))
            return result(State.FAILED, mapping, "ACCOUNT_CHANGED", "쿠팡 계정이 변경되었습니다.", at);
        if (!p.steps().stream().anyMatch(s -> s.id().equals(step.id()) && s.type() == step.type()
                && Objects.equals(s.optionId(), step.optionId())))
            return result(State.FAILED, mapping, "INVALID_STEP", "전송 단계를 확인해 주세요.", at);
        if (p.schemaVersion() < 4)
            return result(State.FAILED, mapping, "LEGACY_INTENT", "새 미리보기로 준비해 주세요.", at);
        if(lifecycle(step.type()))return executeLifecycle(actor,p,step,mapping,at,beforeDispatch);
        if (step.type() == Type.APPROVAL)
            return executeApproval(actor, p, step, mapping, at, beforeDispatch);
        if (step.type() == Type.CREATE && mapping.sellerProductId() != null)
            return result(State.FAILED, mapping, "ALREADY_CREATED", "이미 등록된 상품입니다.", at);
        var dispatched = new java.util.concurrent.atomic.AtomicBoolean();
        var exact = new java.util.concurrent.atomic.AtomicReference<String>();
        try {
            return client.writeSession(() -> {
                if (p.editIntent() == null)
                    throw invalid("저장한 수정 항목을 확인해 주세요.");
                if (step.type() != Type.CREATE
                        && (!Objects.equals(p.mapping().sellerProductId(), mapping.sellerProductId())
                                || !Objects.equals(p.mapping().options(), mapping.options())))
                    throw invalid("쿠팡 상품·옵션 연결이 변경되었습니다.");
                var input = step.type() == Type.CREATE
                        ? p.editIntent().observed()
                        : CoupangInputs.apply(json, p.editIntent().observed(), p.editIntent().changes());
                var fresh = build(actor, input, step.type() == Type.CREATE ? null : mapping, p.editIntent().observed(),
                        p.editIntent().changes(),p.editIntent().quickEditBasis()!=null);
                var candidate = fresh.steps().stream()
                        .filter(s -> s.type() == step.type() && Objects.equals(s.optionId(), step.optionId()))
                        .findFirst().orElse(null);
                if (candidate == null)
                    return result(State.CONFIRMED, mapping, "ALREADY_APPLIED", "변경값의 반영을 확인했습니다.", at);
                if (step.type() == Type.CREATE && !Objects.equals(json.readTree(step.baselineJson()).path("creationRulesCheckedAt"),
                        json.readTree(candidate.baselineJson()).path("creationRulesCheckedAt")))
                    throw invalid("카테고리 규격 확인이 변경되었습니다. 입력을 유지한 채 새 미리보기로 확인해 주세요.");
                if(p.editIntent().quickEditBasis()!=null&&!json.readTree(step.baselineJson()).equals(json.readTree(candidate.baselineJson())))
                    throw invalid("현재 쿠팡값이 변경되었습니다. 입력을 보관한 뒤 다시 확인해 주세요.");
                var actual = new Step(step.id(), candidate.type(), candidate.optionId(), candidate.method(),
                        candidate.path(), candidate.query(), candidate.bodyJson(), candidate.baselineJson(),
                        candidate.expectedJson());
                access.productActor(actor, true);
                if (actual.type() == Type.CREATE) checkCreationRules(actual);
                beforeDispatch.accept(actual);
                exact.set(json.writeValueAsString(actual));
                dispatched.set(true);
                var response = client.write(actual.method(), actual.path(), actual.query(), actual.bodyJson(),
                        () -> retryGuard(actor, actual, mapping, beforeDispatch));
                var out = response.status() == 200
                        ? parseOutcome(response.body(), actual, fresh.mapping(), at)
                        : httpFailure(response.status(), response.body(), mapping, at, actual.bodyJson());
                if (approvalFollows(p, step))
                    out = saved(p, actual, out);
                else if (out.state() == State.ACCEPTED)
                    try {
                        out = verifyKnown(fresh, actual, out);
                    } catch (RuntimeException ignored) {
                    }
                return withRequest(out, exact.get());
            });
        } catch (InputValidationFailure failure) {
            return result(State.FAILED, mapping, "MAPPING_CHANGED", failure.getMessage(), at);
        } catch (CoupangFailure failure) {
            return withRequest(result(dispatched.get() ? State.UNKNOWN : State.FAILED, mapping,
                    dispatched.get() ? failure.kind().name() : "PREFLIGHT_" + failure.kind().name(),
                    failure.getMessage(), at), exact.get());
        }
    }
    private void retryGuard(Long actor, Step actual, Mapping mapping, java.util.function.Consumer<Step> beforeDispatch) {
        access.productActor(actor, true);
        if (actual.type() == Type.CREATE) {
            checkCreationRules(actual);
        } else if (actual.type() != Type.APPROVAL) {
            boolean matches;
            if (actual.type() == Type.DELETE) {
                var current = client.rawProduct(mapping.sellerProductId());
                matches = json.readTree(actual.baselineJson()).equals(current);
                for (var item : current.path("items")) {
                    String vendorItemId = optionalId(item.path("vendorItemId"));
                    if (vendorItemId != null && client.rawInventory(vendorItemId).path("onSale").asBoolean())
                        throw invalid("모든 옵션의 판매중지를 확인한 뒤 삭제해 주세요.");
                }
            } else matches = baselineMatches(actual, mapping);
            if (!matches) throw invalid("현재 쿠팡값이 변경되었습니다. 입력을 보관한 뒤 다시 확인해 주세요.");
        }
        // Recheck account lease, supplier fingerprint, permission and persisted intent before another dispatch.
        beforeDispatch.accept(actual);
    }
    private void checkCreationRules(Step actual) {
        var rules = client.creationCategory(json.readTree(actual.bodyJson()).path("displayCategoryCode").asString());
        if (!Objects.equals(rules.checkedAt(), json.readTree(actual.baselineJson()).path("creationRulesCheckedAt").asString(null)))
            throw invalid("카테고리 규격 확인이 변경되었습니다. 입력을 유지한 채 새 미리보기로 확인해 주세요.");
    }
    private void validateWireField(String key, String value) {
        if (NUMBERS.contains(key)) {
            var n = number(value);
            if (n.compareTo(BigInteger.valueOf(9_007_199_254_740_991L)) > 0)
                throw invalid("숫자 입력 범위를 확인해 주세요.");
            if (key.equals("maximumBuyCount") && n.compareTo(BigInteger.valueOf(99999)) > 0)
                throw invalid("판매 수량은 99999 이하로 입력해 주세요.");
            if (key.equals("unitCount") && n.signum() == 0)
                throw invalid("판매 단위는 1 이상이어야 합니다.");
        }
        if (BOOLS.contains(key) && !Set.of("true", "false").contains(value))
            throw invalid("참·거짓 설정을 확인해 주세요.");
        var choices = Map.ofEntries(Map.entry("adultOnly", Set.of("EVERYONE", "ADULT_ONLY")),
                Map.entry("taxType", Set.of("TAX", "FREE")),
                Map.entry("parallelImported", Set.of("PARALLEL_IMPORTED", "NOT_PARALLEL_IMPORTED")),
                Map.entry("overseasPurchased", Set.of("OVERSEAS_PURCHASED", "NOT_OVERSEAS_PURCHASED")),
                Map.entry("deliveryMethod",
                        Set.of("SEQUENCIAL", "COLD_FRESH", "MAKE_ORDER", "AGENT_BUY", "VENDOR_DIRECT")),
                Map.entry("deliveryChargeType", Set.of("FREE", "NOT_FREE", "CHARGE_RECEIVED", "CONDITIONAL_FREE")),
                Map.entry("remoteAreaDeliverable", Set.of("Y", "N")),
                Map.entry("unionDeliveryType", Set.of("UNION_DELIVERY", "NOT_UNION_DELIVERY")));
        if (choices.containsKey(key) && !choices.get(key).contains(value))
            throw invalid("쿠팡 설정의 선택값을 확인해 주세요.");
        if (key.equals("saleStartedAt") || key.equals("saleEndedAt"))
            try {
                if (java.time.LocalDateTime.parse(value).getYear() > 2099)
                    throw new IllegalArgumentException();
            } catch (RuntimeException bad) {
                throw invalid("판매 일시는 2099년 이하의 유효한 날짜로 입력해 주세요.");
            }
        if (key.equals("searchTags")) {
            var tags = Arrays.stream(value.split(",")).map(String::trim).filter(t -> !t.isEmpty()).toList();
            if (tags.size() > 20 || tags.stream().anyMatch(t -> t.codePointCount(0, t.length()) > 20))
                throw invalid("검색어는 각 20자 이하, 20개 이하로 입력해 주세요.");
        }
        if (key.equals("emptyBarcodeReason") && value.codePointCount(0, value.length()) > 100
                || key.equals("offerDescription") && value.codePointCount(0, value.length()) > 700)
            throw invalid("설정 내용의 길이를 줄여 주세요.");
    }
    private static Result withRequest(Result result, String request) {
        return new Result(result.state(), result.mapping(), result.code(), result.message(), result.attemptedAt(),
                request, result.rejectionDetails());
    }
    private Result executeApproval(Long actor, Prepared prepared, Step step, Mapping mapping, Instant attempted,
            java.util.function.Consumer<Step> beforeDispatch) {
        if (mapping.sellerProductId() == null)
            return result(State.FAILED, mapping, "MAPPING_CHANGED", "저장된 등록상품 ID를 확인해 주세요.", attempted);
        var sent = new java.util.concurrent.atomic.AtomicBoolean();
        boolean creation = json.readValue(step.expectedJson(), Step.class).type() == Type.CREATE;
        try {
            return client.writeSession(() -> {
                String path = CoupangProductClient.PATH + "/" + mapping.sellerProductId() + "/approvals";
                var actual = new Step(step.id(), Type.APPROVAL, null, "PUT", path, "", null, step.baselineJson(),
                        step.expectedJson());
                access.productActor(actor, true);
                beforeDispatch.accept(actual);
                sent.set(true);
                java.util.function.Supplier<CoupangHttpTransport.Response> dispatch = () -> client.write("PUT", path, "", null,
                        () -> retryGuard(actor, actual, mapping, beforeDispatch));
                var response = creation ? client.withFirstResponseGap(CREATION_TRANSITION_GAP, dispatch) : dispatch.get();
                if (response.status() != 200) {
                    if (response.status() == 400 && approvalProcessing(response.body()))
                        return result(State.FAILED, mapping, "APPROVAL_NOT_READY",
                                "쿠팡이 상품을 처리 중입니다. 10분 후 승인 요청만 재시도해 주세요.", attempted);
                    return httpFailure(response.status(), response.body(), mapping, attempted);
                }
                var accepted = parseOutcome(response.body(), actual, mapping, attempted);
                if (accepted.state() != State.ACCEPTED)
                    return accepted;
                try {
                    return creation ? verifyCreatedApprovalAutomatically(actor, prepared, actual, accepted)
                            : verifyApproval(prepared, actual, accepted);
                } catch (CoupangFailure e) {
                    return result(State.ACCEPTED, mapping,
                            e.kind() == CoupangFailure.Kind.PRODUCT_PROCESSING ? "APPROVAL_LOOKUP_PROCESSING" : "APPROVAL_LOOKUP_FAILED",
                            e.kind() == CoupangFailure.Kind.PRODUCT_PROCESSING
                                    ? "판매 승인 요청 접수 · 자동 결과 조회에서 쿠팡 상품 처리 중 응답을 받았습니다. 잠시 후 결과를 다시 확인해 주세요."
                                    : "판매 승인 요청 접수 · 자동 결과 조회를 완료하지 못했습니다. 결과를 다시 확인해 주세요.", attempted);
                }
            });
        } catch (CoupangFailure e) {
            return result(sent.get() ? State.UNKNOWN : State.FAILED, mapping, e.kind().name(),
                    sent.get() ? "판매 승인 요청 결과를 확인하지 못했습니다. 재전송하지 말고 결과를 확인해 주세요." : "승인 요청을 보내지 못했습니다.", attempted);
        }
    }
    /** Only reads repeat after a confirmed approval response. The worker stays RUNNING while waiting. */
    private Result verifyCreatedApprovalAutomatically(Long actor, Prepared prepared, Step approval, Result accepted) {
        long deadline = System.nanoTime() + APPROVAL_CHECK_WINDOW.toNanos();
        var latest = accepted;
        for (int check = 0; check < MAX_APPROVAL_CHECKS && System.nanoTime() < deadline; check++) {
            try {
                access.productActor(actor, true);
            } catch (cc.ataglace.molebutter.common.api.BusinessException denied) {
                return result(State.ACCEPTED, latest.mapping(), "APPROVAL_CHECK_ACCESS_DENIED",
                        "판매 승인 요청은 접수되었습니다. 권한 변경으로 자동 결과 확인을 중단했습니다.", accepted.attemptedAt());
            }
            try {
                var previous = latest;
                latest = client.withFirstResponseGap(CREATION_TRANSITION_GAP,
                        () -> verifyApproval(prepared, approval, previous));
                if (latest.state() != State.ACCEPTED || !"APPROVAL_PENDING".equals(latest.code()))
                    return latest;
            } catch (CoupangFailure failure) {
                if (failure.kind() != CoupangFailure.Kind.PRODUCT_PROCESSING)
                    return result(State.ACCEPTED, latest.mapping(), "APPROVAL_LOOKUP_FAILED",
                            "판매 승인 요청 접수 · 자동 결과 조회를 완료하지 못했습니다. 결과를 다시 확인해 주세요.", accepted.attemptedAt());
            }
        }
        return result(State.ACCEPTED, latest.mapping(), "APPROVAL_CHECK_TIMEOUT",
                "판매 승인 요청 접수 · 10분 동안 자동 결과 확인을 진행했으나 쿠팡 처리가 완료되지 않았습니다.", accepted.attemptedAt());
    }
    private boolean approvalProcessing(byte[] body) {
        try {
            String message = json.readTree(body).path("message").asString("");
            return message.contains("상품 정보가 등록 또는 수정되고 있습니다");
        } catch (RuntimeException e) {
            return false;
        }
    }
    private Result verifyApproval(Prepared p, Step approval, Result previous) {
        var save = json.readValue(approval.expectedJson(), Step.class);
        var mapping = previous.mapping();
        if (save.type() == Type.CREATE) {
            var expected = json.readTree(save.expectedJson()).deepCopy().asObject();
            expected.put("requested", true);
            return verifyKnown(p, new Step(save.id(), Type.CREATE, null, save.method(), save.path(), save.query(),
                    save.bodyJson(), save.baselineJson(), wire(expected)), previous);
        }
        if (save.type() != Type.PRODUCT)
            throw invalid("승인 요청의 저장 단계를 확인해 주세요.");
        var actual = client.rawProduct(mapping.sellerProductId());
        String status = Objects.toString(scalar(actual.path("statusName")), "");
        if (Set.of("승인반려", "REJECTED").contains(status))
            return result(State.ACCEPTED, mapping, "APPROVAL_REJECTED", "쿠팡 승인 반려 사유를 확인해 주세요.",
                    previous.attemptedAt());
        if (!Set.of("승인완료", "APPROVED").contains(status))
            return result(State.ACCEPTED, mapping, "APPROVAL_PENDING", "판매 승인 결과를 기다리고 있습니다.", previous.attemptedAt());
        var expected = json.readTree(save.expectedJson()).deepCopy().asObject();
        expected.remove("requested");
        return result(subset(expected, actual) ? State.CONFIRMED : State.ACCEPTED, mapping,
                subset(expected, actual) ? "VERIFIED" : "REFLECTION_PENDING",
                subset(expected, actual) ? "상품 변경과 판매 승인을 확인했습니다." : "판매 승인은 완료되었으며 변경값 반영 확인이 필요합니다.",
                previous.attemptedAt());
    }
    public Result reconcile(Long actor, Prepared prepared, Step step, Result previous) {
        client.observationActor(actor);
        access.productActor(actor, true);
        Mapping mapping = previous.mapping() == null ? prepared.mapping() : previous.mapping();
        if (!validAccount(prepared, mapping))
            return result(State.FAILED, mapping, "ACCOUNT_CHANGED", "쿠팡 계정이 변경되었습니다. 연결 정보를 확인해 주세요.",
                    previous.attemptedAt());
        if (step.type() == Type.CREATE && mapping.sellerProductId() == null)
            return result(State.UNKNOWN, mapping, "UNRESOLVED",
                    "등록 응답의 상품 ID를 확인하지 못했습니다. 재등록하지 말고 Wing에서 직접 확인해 주세요.", previous.attemptedAt());
        try {
            return client.writeSession(() -> {
                if(step.type()==Type.DELETE) {
                    try{client.rawProduct(mapping.sellerProductId());}catch(cc.ataglace.molebutter.coupang.api.CoupangListingMissing deleted){return result(State.CONFIRMED,mapping,"DELETE_CONFIRMED","상품 삭제를 확인했습니다.",previous.attemptedAt());}
                    return result(previous.state()==State.UNKNOWN?State.UNKNOWN:State.ACCEPTED,mapping,"UNRESOLVED","삭제 결과를 확인 중입니다. 재전송하지 마세요.",previous.attemptedAt());
                }
                if (step.type() == Type.APPROVAL) {
                    var parent = json.readValue(step.expectedJson(), Step.class);
                    return previous.state() == State.ACCEPTED && parent.type() == Type.CREATE
                            ? verifyCreatedApprovalAutomatically(actor, prepared, step, previous)
                            : verifyApproval(prepared, step, previous);
                }
                if (step.type() == Type.CREATE)
                    return verifyKnown(prepared, step, previous);
                if ("NEEDS_CORRECTION".equals(previous.code()))
                    return previous;
                if (expectedMatches(step, mapping))
                    return result(State.CONFIRMED, mapping, "VERIFIED",
                            previous.code().equals("NEEDS_CORRECTION") ? previous.message() : "쿠팡 반영을 확인했습니다.",
                            previous.attemptedAt());
                return result(previous.state() == State.ACCEPTED ? State.ACCEPTED : State.UNKNOWN, mapping,
                        previous.code(),
                        previous.state() == State.ACCEPTED ? "쿠팡 반영을 기다리고 있습니다." : "쿠팡 처리 결과를 확인하지 못했습니다.",
                        previous.attemptedAt());
            });
        } catch (CoupangFailure e) {
            return result(State.UNKNOWN, mapping, e.kind().name(), "쿠팡 처리 결과를 확인하지 못했습니다.", previous.attemptedAt());
        }
    }
    private boolean validAccount(Prepared p, Mapping m) {
        return client.accountKey().equals(p.accountKey()) && m != null && client.accountKey().equals(m.accountKey());
    }
    private Result verifyKnown(Prepared prepared, Step step, Result accepted) {
        Mapping mapping = accepted.mapping();
        if (step.type() == Type.CREATE) {
            var actual = client.rawProduct(mapping.sellerProductId());
            var expected = json.readTree(step.expectedJson());
            if (!Objects.equals(mapping.sellerProductId(), optionalId(actual.path("sellerProductId")))
                    || !expected.path("vendorId").equals(actual.path("vendorId")))
                return result(State.ACCEPTED, mapping, "MAPPING_CHANGED", "등록 상품의 식별자를 확인하지 못했습니다.",
                        accepted.attemptedAt());
            try {
                mapping = mappingFor(mapping, actual, expected);
            } catch (InputValidationFailure e) {
                return result(State.ACCEPTED, mapping, "CREATE_OPTIONS_UNCONFIRMED", "상품은 조회되지만 옵션 연결을 확인하지 못했습니다.",
                        accepted.attemptedAt());
            }
            String status = Objects.toString(scalar(actual.path("statusName")), "");
            boolean approved = Set.of("승인완료", "APPROVED").contains(status);
            if (Set.of("승인반려", "REJECTED").contains(status))
                return result(State.ACCEPTED, mapping, "APPROVAL_REJECTED", "쿠팡 심사에서 반려되었습니다. Wing에서 사유를 확인해 주세요.",
                        accepted.attemptedAt());
            if (Set.of("심사중", "승인대기중", "부분승인완료", "IN_REVIEW", "PENDING_APPROVAL").contains(status))
                return result(State.ACCEPTED, mapping, "APPROVAL_PENDING", "상품 등록을 확인했습니다. 판매 승인을 기다리고 있습니다.",
                        accepted.attemptedAt());
            if (!approved && !Set.of("임시저장", "SAVED").contains(status))
                return result(State.ACCEPTED, mapping, "CREATE_STATUS_UNCONFIRMED", "등록 상품의 현재 상태를 확인하지 못했습니다.",
                        accepted.attemptedAt());
            if (expected.path("requested").asBoolean() && !approved)
                return result(State.ACCEPTED, mapping, "APPROVAL_PENDING", "상품 등록을 확인했습니다. 판매 승인을 기다리고 있습니다.",
                        accepted.attemptedAt());
            if (!cc.ataglace.molebutter.coupang.api.CoupangCreationConfirmation.completeOptions(mapping,
                    prepared.mapping().options().stream().map(OptionMapping::optionId).toList(), approved))
                return result(State.ACCEPTED, mapping, "CREATE_OPTIONS_UNCONFIRMED", "상품은 조회되지만 옵션 연결을 확인하지 못했습니다.",
                        accepted.attemptedAt());
            if ("NEEDS_CORRECTION".equals(accepted.code()) && !approved)
                return result(State.ACCEPTED, mapping, accepted.code(), accepted.message(), accepted.attemptedAt());
            // Creation dates, search-tag order and image locations are presentation differences.
            // Report other differences without keeping a verified creation locked forever.
            boolean reflected = createApplied(expected, actual);
            return result(State.CONFIRMED, mapping, reflected ? "CREATED_CONFIRMED" : "CREATED_VALUES_DIFFER",
                    reflected ? "쿠팡 등록·옵션 연결을 확인했습니다." : "등록·옵션 연결을 확인했습니다. 현재 상품값이 등록 요청과 다릅니다. 상품 수정에서 최신값을 확인해 주세요.",
                    accepted.attemptedAt());
        }
        if ("NEEDS_CORRECTION".equals(accepted.code()))
            return accepted;
        return expectedMatches(step, mapping)
                ? result(State.CONFIRMED, mapping, "VERIFIED", "쿠팡 반영을 확인했습니다.", accepted.attemptedAt())
                : accepted;
    }
    private boolean baselineMatches(Step step, Mapping mapping) {
        if (mapping.sellerProductId() == null)
            return false;
        JsonNode expected = json.readTree(step.baselineJson());
        if (step.type() == Type.PRODUCT)
            return expected.equals(client.rawProduct(mapping.sellerProductId()));
        if (step.type() == Type.DELIVERY)
            return subset(expected, client.rawProduct(mapping.sellerProductId()));
        var option = optionMapping(mapping, step.optionId());
        var product = client.rawProduct(mapping.sellerProductId());
        boolean owned = false;
        for (var item : product.path("items"))
            if (Objects.equals(option.sellerProductItemId(), optionalId(item.path("sellerProductItemId")))
                    && Objects.equals(option.vendorItemId(), optionalId(item.path("vendorItemId"))))
                owned = true;
        if (step.type() == Type.ORIGINAL_PRICE)
            return owned && subset(expected, remoteOption(product, option));
        return owned && subset(expected, client.rawInventory(option.vendorItemId()));
    }
    private boolean expectedMatches(Step step, Mapping mapping) {
        if (mapping.sellerProductId() == null)
            return false;
        var expected = json.readTree(step.expectedJson());
        if (step.type() == Type.CREATE)
            return matchesCreate(expected, client.rawProduct(mapping.sellerProductId()));
        if (step.type() == Type.PRODUCT) {
            var actual = client.rawProduct(mapping.sellerProductId());
            boolean requested = step.bodyJson() != null && json.readTree(step.bodyJson()).path("requested").asBoolean();
            if (requested && !Arrays.asList("승인완료", "APPROVED").contains(scalar(actual.path("statusName"))))
                return false;
            // requested is a command flag, not proof that approval has completed.
            if (expected.isObject())
                expected.asObject().remove("requested");
            return subset(expected, actual);
        }
        if (step.type() == Type.DELIVERY)
            return subset(expected, client.rawProduct(mapping.sellerProductId()));
        var option = optionMapping(mapping, step.optionId());
        if (step.type() == Type.ORIGINAL_PRICE)
            return subset(expected, remoteOption(client.rawProduct(mapping.sellerProductId()), option));
        return subset(expected,
                client.rawInventory(option.vendorItemId(), step.type() == Type.PRICE, step.type() == Type.STOCK));
    }
    private JsonNode remoteOption(JsonNode product, OptionMapping option) {
        for (var item : product.path("items"))
            if (Objects.equals(option.sellerProductItemId(), optionalId(item.path("sellerProductItemId")))
                    && Objects.equals(option.vendorItemId(), optionalId(item.path("vendorItemId"))))
                return item;
        return json.createObjectNode();
    }
    Result parseOutcome(byte[] body, Step step, Mapping mapping, Instant attempted) {
        return new CoupangWriteOutcome(json).parseOutcome(body, step, mapping, attempted);
    }
    Result httpFailure(int status, byte[] body, Mapping mapping, Instant now) {
        return new CoupangWriteOutcome(json).httpFailure(status, body, mapping, now);
    }
    private Result httpFailure(int status, byte[] body, Mapping mapping, Instant now, String requestJson) {
        return new CoupangWriteOutcome(json).httpFailure(status, body, mapping, now, requestJson);
    }
    private Step currentStep(Type type, String optionId, String vendorItemId, String value, JsonNode inventory) {
        var before = json.createObjectNode();
        var after = json.createObjectNode();
        String key = switch (type) {
            case ORIGINAL_PRICE -> "originalPrice";
            case PRICE -> "salePrice";
            case STOCK -> "amountInStock";
            default -> throw new IllegalArgumentException("Not a current value step");
        };
        value = number(value).toString();
        before.set(key, inventory.path(key));
        after.set(key, json.readTree(value));
        String path = "/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/" + vendorItemId + (switch (type) {
            case ORIGINAL_PRICE -> "/original-prices/";
            case PRICE -> "/prices/";
            case STOCK -> "/quantities/";
            default -> throw new IllegalArgumentException("Not a current value step");
        }) + value;
        return new Step(UUID.randomUUID().toString(), type, optionId, "PUT", path,
                type == Type.PRICE ? "forceSalePriceUpdate=false" : "", null, wire(before), wire(after));
    }
    private ArrayNode images(Long actor, Document d, Option o, ObjectNode source, Document approved) {
        var selected = o.images().stream()
                .sorted(Comparator.comparing((Image i) -> !i.representative()).thenComparingInt(Image::order)).toList();
        if (source != null) {
            var sourceImages = new ArrayList<String>();
            for (var row : source.path("images"))
                sourceImages.add(CoupangProductClient.imageUrl(optionalText(row.path("cdnPath")),
                        optionalText(row.path("vendorPath"))) + "|" + scalar(row.path("imageType")) + "|"
                        + scalar(row.path("imageOrder")));
            var desiredImages = selected.stream().map(i -> i.url() + "|" + i.imageType() + "|" + i.order()).toList();
            if (sourceImages.equals(desiredImages))
                return null;

        }
        if (selected.stream().filter(Image::representative).count() != 1
                || selected.stream().filter(i -> i.imageType().equals("DETAIL")).count() > 9
                || selected.stream().filter(i -> i.imageType().equals("USED_PRODUCT")).count() > 4)
            throw invalid("옵션마다 대표 이미지 1개, 추가 이미지 9개 이하, 중고상태 이미지 4개 이하가 필요합니다.");
        var rows = json.createArrayNode();
        int order = 0;
        for (var i : selected) {
            String url;
            if (i.assetId() != null) {
                if (inspection != null)
                    inspection.require(actor,
                            List.of(new cc.ataglace.molebutter.media.api.ImageInspection.Input(i.imageType(), o.id(),
                                    i.id(), i.url(), i.assetId(), false)));
                var a = assets.readEdited(actor, i.assetId()).metadata();
                var errors = cc.ataglace.molebutter.media.api.ImageRules.errors(i.imageType(), a.mimeType(), a.width(),
                        a.height(), a.bytes());
                if (!errors.isEmpty())
                    throw invalid(String.join(" ", errors));
                url = assets.submissionUrl(actor, i.assetId());
                var edited = json.createObjectNode();
                edited.put("imageOrder", order++);
                edited.put("imageType", i.imageType());
                edited.put("vendorPath", url);
                rows.add(edited);
                continue;
            }
            ObjectNode row = json.createObjectNode();
            if (source != null)
                for (var old : source.path("images"))
                    if (Objects.equals(i.url(), CoupangProductClient.imageUrl(optionalText(old.path("cdnPath")),
                            optionalText(old.path("vendorPath")))))
                        row = (ObjectNode) old.deepCopy();
            url = https(i.url());
            var prior = approved == null
                    ? null
                    : approved.options().stream().flatMap(option -> option.images().stream())
                            .filter(image -> Objects.equals(image.url(), i.url())).findFirst().orElse(null);
            if (row.isEmpty() && prior != null) {
                // Previously observed URLs are server-owned evidence; no mutable-value conflict
                // is imposed.
                if (source != null)
                    for (var current : source.path("images"))
                        if (Objects.equals(scalar(current.path("imageType")), prior.type())
                                && current.path("imageOrder").asInt() == prior.order())
                            row = current.deepCopy().asObject();
                row.remove("cdnPath");
                row.put("vendorPath", url);
            } else if (row.isEmpty()) {
                var a = r2.resolve(actor, url);
                var errors = cc.ataglace.molebutter.media.api.ImageRules.errors(i.imageType(), a.mimeType(), a.width(),
                        a.height(), a.bytes());
                if (!errors.isEmpty())
                    throw invalid(String.join(" ", errors));
                url = a.url();
                if (url.length() > 200)
                    throw invalid("쿠팡 이미지 주소는 200자 이하로 선택해 주세요.");
            }
            row.put("imageOrder", order++);
            row.put("imageType", i.imageType());
            if (!row.hasNonNull("cdnPath") && !row.hasNonNull("vendorPath"))
                row.put("vendorPath", https(url));
            rows.add(row);
        }
        return rows;
    }
    private ArrayNode contents(Long actor, Document d, Option o, ObjectNode source, Document approved) {
        var rows = json.createArrayNode();
        var selected = o.contents();
        if (selected.isEmpty())
            throw invalid("옵션 설명을 입력해 주세요.");
        String previous = previousDescription(source);
        if (approved != null)
            for (var option : approved.options())
                for (var content : option.contents())
                    previous += "IMAGE".equals(content.type()) || "IMAGE".equals(content.detailType())
                            ? "<img src=\"" + escape(content.content()) + "\">"
                            : content.content();
        for (var c : selected) {
            boolean image = "IMAGE".equals(c.type()) || "IMAGE".equals(c.detailType());
            if (inspection != null)
                inspection.require(actor, cc.ataglace.molebutter.media.api.ImageInspection.descriptionInputs(
                        image ? "<img src=\"" + escape(c.content()) + "\">" : c.content(), previous));
            String description = cc.ataglace.molebutter.media.api.EditedImages.description(actor,
                    image ? "<img src=\"" + escape(c.content()) + "\">" : c.content(), previous, r2, assets);
            var row = json.createObjectNode();
            row.put("contentsType", c.type());
            var details = json.createArrayNode();
            var part = json.createObjectNode();
            part.put("detailType", image ? "IMAGE" : "TEXT");
            part.put("content",
                    image
                            ? (c.assetId() != null ? assets.submissionUrl(actor, c.assetId()) : https(c.content()))
                            : publishHtml(actor, d, description));
            details.add(part);
            row.set("contentDetails", details);
            rows.add(row);
        }
        return rows;
    }
    private String publishHtml(Long actor, Document d, String html) {
        String result = html;
        if (result.contains("/api/coupang/assets/") || result.contains("blob:"))
            throw invalid("기존 로컬 설명 이미지를 R2 이미지로 다시 선택해 주세요.");
        var pattern = java.util.regex.Pattern.compile(
                "<img\\b[^>]*?\\bsrc\\s*=\\s*(?:[\\\"']([^\\\"']*)[\\\"']|([^\\s>]+))",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        var matcher = pattern.matcher(result);
        while (matcher.find())
            https(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
        return result;
    }
    private static String previousDescription(ObjectNode source) {
        if (source == null)
            return "";
        var html = new StringBuilder();
        for (var content : source.path("contents"))
            for (var part : content.path("contentDetails")) {
                String value = part.path("content").asString("");
                if (part.path("detailType").asString("").equals("IMAGE") && !value.startsWith("https://")
                        && value.matches("(?:vendor_inventory|image)/[a-zA-Z0-9_./%-]+"))
                    value = "https://img1a.coupangcdn.com/image/" + value.replaceFirst("^image/", "");
                if (part.path("detailType").asString("").equals("IMAGE"))
                    html.append("<img src=\"").append(escape(value)).append("\">");
                else
                    html.append(value);
            }
        return html.toString();
    }
    private ArrayNode attributeRows(Option o) {
        var rows = json.createArrayNode();
        for (var a : o.attributes()) {
            var r = rows.addObject();
            r.put("attributeTypeName", a.name());
            r.put("attributeValueName", a.value());
            r.put("exposed", a.exposed());
        }
        return rows;
    }
    private ArrayNode noticeRows(Option o) {
        var rows = json.createArrayNode();
        for (var a : o.notices()) {
            var r = rows.addObject();
            r.put("noticeCategoryName", a.category());
            r.put("noticeCategoryDetailName", a.name());
            r.put("content", a.content());
        }
        return rows;
    }
    private ArrayNode certificationRows(Option o) {
        var rows = json.createArrayNode();
        for (var c : o.certifications()) {
            var r = rows.addObject();
            r.put("certificationType", c.type());
            r.put("certificationCode", c.code());
        }
        return rows;
    }
    private ArrayNode documentRows(Document d, JsonNode current) {
        var rows = json.createArrayNode();
        for (var doc : d.documents()) {
            ObjectNode row = json.createObjectNode();
            for (var old : current)
                if (Objects.equals(doc.templateName(), scalar(old.path("templateName"))))
                    row = old.deepCopy().asObject();
            row.put("templateName", doc.templateName());
            if (!blank(doc.path())) {
                row.put("documentPath", doc.path());
                row.remove("vendorDocumentPath");
            } else if (!blank(doc.vendorPath())) {
                row.put("vendorDocumentPath", https(doc.vendorPath()));
                row.remove("documentPath");
            } else
                throw invalid("서류 첨부 경로를 확인해 주세요.");
            rows.add(row);
        }
        return rows;
    }
    private ArrayNode mergeContents(JsonNode current, ArrayNode next) {
        if (!current.isArray() || current.isEmpty())
            return next;
        if (current.size() != next.size()) {
            for (var row : current) {
                if (hasUnknown(row, Set.of("contentsType", "contentDetails")))
                    throw invalid("기존 설명 부가 정보의 수정 보존 규격 확인이 필요합니다.");
                for (var part : row.path("contentDetails"))
                    if (hasUnknown(part, Set.of("detailType", "content")))
                        throw invalid("기존 설명 세부 정보의 수정 보존 규격 확인이 필요합니다.");
            }
            return next;
        }
        var rows = json.createArrayNode();
        for (int n = 0; n < next.size(); n++) {
            var old = current.get(n);
            var incoming = next.get(n);
            var row = (ObjectNode) old.deepCopy();
            row.set("contentsType", incoming.path("contentsType"));
            var details = json.createArrayNode();
            var oldParts = old.path("contentDetails");
            var newParts = incoming.path("contentDetails");
            if (oldParts.size() != newParts.size()) {
                for (var part : oldParts)
                    if (hasUnknown(part, Set.of("detailType", "content")))
                        throw invalid("기존 설명 세부 정보의 수정 보존 규격 확인이 필요합니다.");
                details = (ArrayNode) newParts.deepCopy();
            } else
                for (int p = 0; p < newParts.size(); p++) {
                    var part = (ObjectNode) oldParts.get(p).deepCopy();
                    part.set("detailType", newParts.get(p).path("detailType"));
                    part.set("content", newParts.get(p).path("content"));
                    details.add(part);
                }
            row.set("contentDetails", details);
            rows.add(row);
        }
        return rows;
    }
    private static boolean hasUnknown(JsonNode row, Set<String> names) {
        for (var entry : row.properties())
            if (!names.contains(entry.getKey()))
                return true;
        return false;
    }
    private ArrayNode patchRows(JsonNode current, List<CoupangEditing.Change> edits, String path, String option,
            List<String> keys, Map<String, String> fields) {
        var result = current.isArray() ? current.deepCopy().asArray() : json.createArrayNode();
        for (var edit : edits) {
            if (!Objects.equals(edit.optionId(), option)
                    || !(edit.path().equals(path) || edit.path().startsWith(path + ".")))
                continue;
            int index = -1;
            for (int n = 0; n < result.size(); n++) {
                boolean match = true;
                for (int k = 0; k < keys.size(); k++)
                    match &= edit.rowKey().get(k).equals(scalar(result.get(n).path(keys.get(k))));
                if (match) {
                    if (index >= 0)
                        throw invalid("목록 행 식별자가 중복되어 전송할 수 없습니다.");
                    index = n;
                }
            }
            if (edit.action().equals("REMOVE")) {
                if (index >= 0)
                    result.remove(index);
                continue;
            }
            var row = index < 0 ? result.addObject() : result.get(index).asObject();
            for (int k = 0; k < keys.size(); k++)
                row.put(keys.get(k), edit.rowKey().get(k));
            var value = json.valueToTree(edit.value());
            for (var field : fields.entrySet()) {
                boolean wholeRow = edit.path().equals(path);
                if (!(wholeRow ? value.has(field.getKey()) : edit.path().equals(path + "." + field.getKey())))
                    continue;
                var selected = wholeRow ? value.path(field.getKey()) : value;
                if (path.equals("documents")) {
                    String location = selected.isNull() ? "" : selected.asString();
                    if (location.isBlank()) {
                        row.remove(field.getValue());
                        continue;
                    }
                    if (location.length() > 150)
                        throw invalid("서류 경로는 150자 이하로 입력해 주세요.");
                    if (field.getKey().equals("vendorPath"))
                        https(location);
                }
                row.set(field.getValue(), selected.deepCopy());
            }
            if (path.equals("documents") && blank(scalar(row.path("documentPath")))
                    && blank(scalar(row.path("vendorDocumentPath"))))
                throw invalid("서류 첨부 경로를 확인해 주세요.");
        }
        return result;
    }
    private Mapping mappingFor(Mapping original, JsonNode actual, JsonNode expected) {
        if (!actual.path("items").isArray() || actual.path("items").size() != original.options().size()
                || expected.path("items").size() != original.options().size())
            throw invalid("등록 옵션 구성이 변경되었습니다.");
        var options = new ArrayList<OptionMapping>();
        for (int n = 0; n < original.options().size(); n++) {
            JsonNode row = null;
            var link = original.options().get(n);
            var wanted = expected.path("items").get(n);
            for (var got : actual.path("items"))
                if (link.sellerProductItemId() != null
                        ? Objects.equals(link.sellerProductItemId(), optionalId(got.path("sellerProductItemId")))
                        : wanted.path("externalVendorSku").equals(got.path("externalVendorSku"))
                                && wanted.path("itemName").equals(got.path("itemName"))) {
                    if (row != null)
                        throw invalid("등록 옵션 연결을 확인해 주세요.");
                    row = got;
                }
            // Only use a unique seller code inside the product ID returned by the POST.
            // Preserve verified option IDs if display names or seller codes later change.
            if (row == null && link.sellerProductItemId() == null)
                for (var got : actual.path("items"))
                    if (wanted.path("externalVendorSku").equals(got.path("externalVendorSku"))) {
                        if (row != null)
                            throw invalid("등록 옵션 연결을 확인해 주세요.");
                        row = got;
                    }
            if (row == null || optionalId(row.path("sellerProductItemId")) == null)
                throw invalid("등록 옵션 연결을 확인해 주세요.");
            if (link.vendorItemId() != null
                    && !Objects.equals(link.vendorItemId(), optionalId(row.path("vendorItemId"))))
                throw invalid("등록 옵션 번호가 변경되었습니다.");
            options.add(new OptionMapping(original.options().get(n).optionId(),
                    optionalId(row.path("sellerProductItemId")), optionalId(row.path("vendorItemId"))));
        }
        var mapping = new Mapping(original.accountKey(), optionalId(actual.path("sellerProductId")),
                List.copyOf(options));
        if (!cc.ataglace.molebutter.coupang.api.CoupangCreationConfirmation.completeOptions(mapping,
                original.options().stream().map(OptionMapping::optionId).toList(), false))
            throw invalid("등록 옵션 연결이 중복되거나 누락되었습니다.");
        return mapping;
    }
    private boolean matchesCreate(JsonNode expected, JsonNode actual) {
        for (String key : List.of("sellerProductName", "displayCategoryCode", "vendorId"))
            if (!expected.path(key).equals(actual.path(key)))
                return false;
        var a = actual.path("items");
        var e = expected.path("items");
        if (!a.isArray() || a.size() != e.size())
            return false;
        for (var wantedItem : e) {
            JsonNode actualItem = null;
            for (var got : a)
                if (wantedItem.path("externalVendorSku").equals(got.path("externalVendorSku"))
                        && wantedItem.path("itemName").equals(got.path("itemName"))) {
                    if (actualItem != null)
                        return false;
                    actualItem = got;
                }
            if (actualItem == null)
                return false;
        }
        return true;
    }
    private static boolean createApprovalApplied(JsonNode expected, JsonNode actual) {
        return !expected.path("requested").asBoolean()
                || Arrays.asList("승인완료", "APPROVED").contains(scalar(actual.path("statusName")));
    }
    private boolean createApplied(JsonNode expected, JsonNode actual) {
        for (var p : expected.properties()) {
            if (Set.of("requested", "sellerProductId", "productId", "saleStartedAt", "saleEndedAt").contains(p.getKey()))
                continue; // Approval/IDs are checked independently; creation dates can be normalized by Coupang.
            if ("items".equals(p.getKey())) {
                for (var wanted : p.getValue()) {
                    JsonNode got = null;
                    for (var candidate : actual.path("items"))
                        if (wanted.path("externalVendorSku").equals(candidate.path("externalVendorSku"))
                                && wanted.path("itemName").equals(candidate.path("itemName")))
                            got = candidate;
                    if (got == null)
                        return false;
                    for (var field : wanted.properties()) {
                        if ("sellerProductItemId".equals(field.getKey()) || "vendorItemId".equals(field.getKey()))
                            continue;
                        if ("images".equals(field.getKey())) {
                            if (!imagesApplied(field.getValue(), got.path("images")))
                                return false;
                        } else if ("searchTags".equals(field.getKey())) {
                            if (!unorderedValuesApplied(field.getValue(), got.path("searchTags")))
                                return false;
                        } else if ("contents".equals(field.getKey())) {
                            if (!creationContentsApplied(field.getValue(), got.path("contents")))
                                return false;
                        } else if (Set.of("attributes", "notices", "certifications").contains(field.getKey())) {
                            if (!arrayMembersApplied(field.getValue(), got.path(field.getKey())))
                                return false;
                        } else if (!subset(field.getValue(), got.path(field.getKey())))
                            return false;
                    }
                }
            } else if ("requiredDocuments".equals(p.getKey())) {
                if (!arrayMembersApplied(p.getValue(), actual.path(p.getKey())))
                    return false;
            } else if (!subset(p.getValue(), actual.path(p.getKey())))
                return false;
        }
        return true;
    }
    private static boolean arrayMembersApplied(JsonNode expected, JsonNode actual) {
        if (expected.isArray() && expected.isEmpty() && (actual.isMissingNode() || actual.isNull()))
            return true;
        if (!actual.isArray())
            return false;
        for (var wanted : expected) {
            boolean found = false;
            for (var got : actual)
                if (subset(wanted, got))
                    found = true;
            if (!found)
                return false;
        }
        return true;
    }
    private static boolean imagesApplied(JsonNode expected, JsonNode actual) {
        if (!expected.isArray() || !actual.isArray() || actual.size() != expected.size())
            return false;
        var unmatched = new ArrayList<JsonNode>(actual.valueStream().toList());
        for (var wanted : expected) {
            if (!wanted.isObject()) return false;
            var comparable = wanted.deepCopy().asObject();
            comparable.remove("vendorPath"); comparable.remove("cdnPath");
            // Match each role/order once. URL rewriting must not hide a missing image or changed metadata.
            int match = -1;
            for (int i = 0; i < unmatched.size(); i++) {
                var got = unmatched.get(i);
                if ((hasImageLocation(got.path("vendorPath")) || hasImageLocation(got.path("cdnPath")))
                        && subset(comparable, got)) { match = i; break; }
            }
            if (match < 0)
                return false;
            unmatched.remove(match);
        }
        return true;
    }
    private static boolean hasImageLocation(JsonNode value) {
        return value.isString() && !value.asString().isBlank();
    }
    private static boolean unorderedValuesApplied(JsonNode expected, JsonNode actual) {
        if (!expected.isArray() || !actual.isArray() || expected.size() != actual.size()) return false;
        var unmatched = new ArrayList<JsonNode>(actual.valueStream().toList());
        for (var wanted : expected) {
            if (!unmatched.remove(wanted)) return false;
        }
        return unmatched.isEmpty();
    }
    private static boolean creationContentsApplied(JsonNode expected, JsonNode actual) {
        if (!expected.isArray() || !actual.isArray() || expected.size() != actual.size()) return false;
        // Normalize copies only: the original request and observed response remain audit evidence.
        var wantedRows = expected.deepCopy(); var gotRows = actual.deepCopy();
        for (int i = 0; i < wantedRows.size(); i++) {
            var wantedRow = wantedRows.get(i); var gotRow = gotRows.get(i);
            var wantedParts = wantedRow.path("contentDetails"); var gotParts = gotRow.path("contentDetails");
            if (!wantedParts.isArray() || !gotParts.isArray() || wantedParts.size() != gotParts.size()) return false;
            for (int j = 0; j < wantedParts.size(); j++) {
                var wanted = wantedParts.get(j); var got = gotParts.get(j);
                if (!wanted.isObject() || !got.isObject()) return false;
                if ("IMAGE".equals(wanted.path("detailType").asString())
                        && wanted.path("detailType").equals(got.path("detailType"))) {
                    if (!hasImageLocation(wanted.path("content")) || !hasImageLocation(got.path("content"))) return false;
                    got.asObject().set("content", wanted.path("content"));
                } else if ("HTML".equals(wantedRow.path("contentsType").asString())
                        && "TEXT".equals(wanted.path("detailType").asString())
                        && wanted.path("content").isString() && got.path("content").isString()) {
                    wanted.asObject().put("content", imageLocationIndependentHtml(wanted.path("content").asString()));
                    got.asObject().put("content", imageLocationIndependentHtml(got.path("content").asString()));
                }
            }
        }
        return subset(wantedRows, gotRows);
    }
    private static final java.util.regex.Pattern HTML_IMAGE_SOURCE = java.util.regex.Pattern.compile(
            "(<img\\b(?:[^\"'>]|\"[^\"]*\"|'[^']*')*?\\s+src\\s*=\\s*)(?:[\"']([^\"']*)[\"']|([^\\s>]+))",
            java.util.regex.Pattern.CASE_INSENSITIVE);
    private static String imageLocationIndependentHtml(String html) {
        return HTML_IMAGE_SOURCE.matcher(html).replaceAll(match -> {
            String url = Objects.toString(match.group(2), match.group(3));
            return java.util.regex.Matcher.quoteReplacement(url.isBlank() ? match.group() : match.group(1) + "\"image-location\"");
        });
    }
    private JsonNode delta(JsonNode before, JsonNode after) {
        if (before.isObject() && after.isObject()) {
            var result = json.createObjectNode();
            for (var p : after.properties())
                if (!before.path(p.getKey()).equals(p.getValue()))
                    result.set(p.getKey(), delta(before.path(p.getKey()), p.getValue()));
            return result;
        }
        if (before.isArray() && after.isArray() && before.size() == after.size()) {
            var result = json.createArrayNode();
            for (int n = 0; n < after.size(); n++) {
                var row = delta(before.get(n), after.get(n));
                if (row.isObject() && !after.get(n).path("sellerProductItemId").isMissingNode())
                    row.asObject().set("sellerProductItemId", after.get(n).path("sellerProductItemId"));
                result.add(row);
            }
            return result;
        }
        return after.deepCopy();
    }
    private static boolean subset(JsonNode expected, JsonNode actual) {
        if (expected.isObject()) {
            for (var p : expected.properties())
                if (!subset(p.getValue(), actual.path(p.getKey())))
                    return false;
            return true;
        }
        if (expected.isArray()) {
            if (!actual.isArray() || expected.size() != actual.size())
                return false;
            boolean keyed = !expected.isEmpty() && expected.valueStream()
                    .allMatch(row -> row.isObject() && !row.path("sellerProductItemId").isMissingNode());
            for (int n = 0; n < expected.size(); n++) {
                var wanted = expected.get(n);
                JsonNode got = actual.get(n);
                if (keyed) {
                    got = null;
                    for (var row : actual)
                        if (wanted.path("sellerProductItemId").equals(row.path("sellerProductItemId"))) {
                            if (got != null)
                                return false;
                            got = row;
                        }
                    if (got == null)
                        return false;
                }
                if (!subset(wanted, got))
                    return false;
            }
            return true;
        }
        return expected.equals(actual);
    }
    private static OptionMapping optionMapping(Mapping m, String id) {
        return m.options().stream().filter(x -> x.optionId().equals(id)).findFirst()
                .orElseThrow(() -> invalid("옵션 연결을 확인해 주세요."));
    }
    private void set(ObjectNode target, String path, String value) {
        if (value == null)
            return;
        String[] parts = path.split("\\.");
        ObjectNode row = target;
        for (int n = 0; n < parts.length - 1; n++) {
            var child = row.path(parts[n]);
            if (!child.isMissingNode() && !child.isObject())
                throw invalid("기존 " + path + " 설정의 보존 형식을 확인해 주세요.");
            if (!child.isObject()) {
                child = json.createObjectNode();
                row.set(parts[n], child);
            }
            row = (ObjectNode) child;
        }
        String key = parts[parts.length - 1];
        if (blank(value) && row.path(key).isMissingNode())
            return;
        if (NUMBERS.contains(path)) {
            if (blank(value))
                return;
            row.set(key, json.readTree(number(value).toString()));
        } else if (BOOLS.contains(path)) {
            if (blank(value))
                return;
            if (!Set.of("true", "false").contains(value))
                throw invalid(path + "의 참·거짓 값을 확인해 주세요.");
            row.put(key, Boolean.parseBoolean(value));
        } else if (path.equals("searchTags")) {
            var tags = json.createArrayNode();
            for (var tag : value.split(","))
                if (!tag.isBlank())
                    tags.add(tag.trim());
            row.set(key, tags);
        } else
            row.put(key, value);
    }
    private static JsonNode at(JsonNode row, String path) {
        for (String part : path.split("\\."))
            row = row.path(part);
        return row;
    }
    private static BigInteger number(String v) {
        if (v == null || !v.matches("[0-9]{1,20}"))
            throw invalid("쿠팡 숫자 입력을 확인해 주세요.");
        return new BigInteger(v);
    }
    private static boolean numericEqual(String value, JsonNode n) {
        return n.isIntegralNumber() && new BigInteger(value).equals(n.bigIntegerValue());
    }
    private static String scalar(JsonNode n) {
        return n.isMissingNode() || n.isNull()
                ? null
                : n.isString() ? n.asString() : n.isNumber() || n.isBoolean() ? n.toString() : null;
    }
    private static String optionalText(JsonNode n) {
        return n.isString() ? n.asString() : null;
    }
    private static String optionalId(JsonNode n) {
        String v = scalar(n);
        return v != null && v.matches("[0-9]{1,30}") ? v : null;
    }
    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
    private static String escape(String v) {
        return v == null
                ? ""
                : v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    static String https(String value) {
        try {
            var uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null || uri.getPort() != -1 && uri.getPort() != 443 || value.length() > 200
                    || privateHost(uri.getHost()))
                throw new IllegalArgumentException();
            return uri.toASCIIString();
        } catch (RuntimeException e) {
            throw invalid("쿠팡에 전달할 공개 HTTPS 이미지 경로를 확인해 주세요.");
        }
    }
    private static boolean privateHost(String host) {
        String h = host.toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        if (h.equals("localhost") || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal")
                || !h.contains("."))
            return true;
        if (h.matches("[0-9.]+")) {
            String[] parts = h.split("\\.");
            if (parts.length != 4)
                return true;
            int[] p = new int[4];
            for (int n = 0; n < 4; n++) {
                p[n] = Integer.parseInt(parts[n]);
                if (p[n] < 0 || p[n] > 255)
                    return true;
            }
            return p[0] == 0 || p[0] == 10 || p[0] == 127 || p[0] == 169 && p[1] == 254
                    || p[0] == 172 && p[1] >= 16 && p[1] <= 31 || p[0] == 192 && p[1] == 168
                    || p[0] == 100 && p[1] >= 64 && p[1] <= 127 || p[0] >= 224;
        }
        if (h.contains(":")) {
            try {
                var address = java.net.InetAddress.getByName(h);
                return address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress() || h.startsWith("[fc")
                        || h.startsWith("[fd");
            } catch (java.net.UnknownHostException e) {
                return true;
            }
        }
        return false;
    }
    private String wire(JsonNode n) {
        String value = json.writeValueAsString(n);
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 5 * 1024 * 1024)
            throw invalid("쿠팡 전송 내용은 5MiB 이하로 줄여 주세요.");
        return value;
    }
    private void diff(String path, JsonNode before, JsonNode after, List<Change> changes) {
        if (path.equals("vendorUserId."))
            return;
        if (before.equals(after))
            return;
        if (before.isObject() || after.isObject()) {
            var keys = new LinkedHashSet<String>();
            if (before.isObject())
                before.properties().forEach(p -> keys.add(p.getKey()));
            if (after.isObject())
                after.properties().forEach(p -> keys.add(p.getKey()));
            for (var key : keys)
                diff(path + key + ".", before.path(key), after.path(key), changes);
            return;
        }
        if (before.isArray() || after.isArray()) {
            for (int n = 0; n < Math.max(before.size(), after.size()); n++) {
                var a = before.isArray() && n < before.size() ? before.get(n) : json.nullNode();
                var b = after.isArray() && n < after.size() ? after.get(n) : json.nullNode();
                diff(path + "[" + (n + 1) + "].", a, b, changes);
            }
            return;
        }
        String a = summary(before), b = summary(after);
        if (before.isString() && after.isString()
                && Math.max(before.asString().length(), after.asString().length()) > 200) {
            String x = before.asString(), y = after.asString();
            int at = 0;
            while (at < Math.min(x.length(), y.length()) && x.charAt(at) == y.charAt(at))
                at++;
            int start = Math.max(0, at - 70);
            a = snippet(x, start);
            b = snippet(y, start);
        }
        changes.add(new Change(path.replaceFirst("\\.$", ""), a, b));
    }
    private static String snippet(String value, int start) {
        start = Math.min(start, value.length());
        int end = Math.min(value.length(), start + 200);
        return (start > 0 ? "…" : "") + value.substring(start, end) + (end < value.length() ? "…" : "");
    }
    private String summary(JsonNode n) {
        if (n.isMissingNode() || n.isNull())
            return null;
        if (n.isArray() || n.isObject())
            return "정보 " + n.size() + "개";
        String v = scalar(n);
        return v != null && v.length() > 200 ? v.substring(0, 200) + "…" : v;
    }
    private static Result result(State s, Mapping m, String c, String message, Instant at) {
        return new Result(s, m, c, message, at);
    }
    private static InputValidationFailure invalid(String message) {
        return new InputValidationFailure(message);
    }
}
