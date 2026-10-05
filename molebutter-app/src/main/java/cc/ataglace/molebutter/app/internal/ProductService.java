package cc.ataglace.molebutter.app.internal;
import cc.ataglace.molebutter.app.internal.ProductHttpDtos.*;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.catalog.api.CatalogCommands.*;
import cc.ataglace.molebutter.catalog.api.CatalogCodes;
import cc.ataglace.molebutter.procurement.api.ProductCodePolicy;
import cc.ataglace.molebutter.procurement.api.ProductSupplierService;
import cc.ataglace.molebutter.procurement.api.ProcurementScheduleService;
import cc.ataglace.molebutter.catalog.api.ProductRegistrationWorkbook;
import cc.ataglace.molebutter.procurement.api.ProcurementLifecycle;
import cc.ataglace.molebutter.procurement.api.ProductChangeService;
import cc.ataglace.molebutter.procurement.api.ProcurementProductQueries;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.common.api.PageResponse;

import cc.ataglace.molebutter.common.api.InputValidationFailure;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {
    private final ApplicationProductContext context;
    private final ProcurementProductQueries queries;
    private final ProcurementLifecycle procurement;
    private final ProcurementScheduleService schedules;
    private final cc.ataglace.molebutter.catalog.api.CatalogCommands catalog;
    private final cc.ataglace.molebutter.inventory.api.InventoryLinkService inventory;
    private final ProductChangeService changes;
    private final cc.ataglace.molebutter.operations.api.NotificationService notifications;
    private final ProductSupplierService suppliers;
    private final ProductRegistrationWorkbook excel;
    public PageResponse<ProcurementProductView> list(Long a,String q,String m,String s,int p){return queries.list(a,q,m,s,p);}
    public PageResponse<ProcurementProductView> list(Long a,String q,String m,String s,int p,int z){return queries.list(a,q,m,s,p,z);}
    public PageResponse<ProcurementProductView> list(Long a,String q,String m,String s,int p,int z,String c){return queries.list(a,q,m,s,p,z,c);}
    public cc.ataglace.molebutter.procurement.api.ChangeDtos.Counts changeCounts(Long a,String q,String m,String s){return queries.changeCounts(a,q,m,s);}
    public ProcurementProductView product(long id){return queries.product(id);}
    public Map<String,Object> detail(Long a,long id){return queries.detail(a,id);}
    public PageResponse<Map<String,Object>> history(Long a,long id,int p){return queries.history(a,id,p);}
    public List<String> brands(Long a){return queries.brands(a);}
    public List<ProcurementProductView> duplicates(Long a,long id){return queries.duplicates(a,id);}

    private Brand resolve(String id, String name) {
        return catalog.resolveBrand(new BrandSelection(id, name));
    }

    private record Identity(String code, String type, String comparison, String query, String mode) {
    }

    private Identity identity(ProductEditRequest input) {
        String code = CatalogCodes.normalize(input.productCode());
        String query = procurement.validateSearchQuery(input.searchQuery());
        return new Identity(code, "GENERAL", "", query, "MANUAL");
    }

    // Compatibility endpoint only; registration and edits never apply its
    // suggestion.
    public Map<String, String> codePreview(Long actor, ProductEditRequest input) {
        context.authorize(actor, false);
        var b = resolve(input.brandId(), input.brand());
        String code = ProductCodePolicy.normalize(cc.ataglace.molebutter.common.api.BusinessText.checked(input.productCode(), 100, false, "필수 입력값과 입력 길이를 확인해 주세요.")),
                type = b.key().isBlank() ? "GENERAL" : "LF_ACCESSORY";
        return Map.of("comparisonCode", ProductCodePolicy.comparison(type, code), "codeType", type, "suggestedQuery",
                ProductCodePolicy.suggested(type, code));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProcurementProductView create(Long actor, ProductEditRequest input) {
        context.authorize(actor, false);
        context.lock();
        Brand b = resolve(input.brandId(), input.brand());
        Identity i = identity(input);
        return insert(b, i, List.of());
    }

    private ProcurementProductView insert(Brand b, Identity i, List<String> names) {
        long id = cc.ataglace.molebutter.common.api.BusinessIds.next();
        catalog.create(id, new ProductInput(new BrandSelection(b.id() == null ? "" : b.id().toString(), null), i.code(), names), context.time.now());
        procurement.create(id,i.type(),i.comparison(),i.query(),i.mode());
        return product(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProcurementProductView edit(Long actor, long id, ProductEditRequest input) {
        context.authorize(actor, false);
        context.lock();
        var p = product(id);
        catalog.checkRevision(id, input.revision());
        Brand b = resolve(input.brandId(), input.brand());
        Identity i = identity(input);
        update(p, b, i);
        return product(id);
    }

    private void update(ProcurementProductView p, Brand b, Identity i) {
        boolean changed = !i.code().equals(p.productCode()) || !i.query().equals(p.searchQuery());
        catalog.edit(Long.parseLong(p.id()), p.revision(), new ProductInput(new BrandSelection(b.id() == null ? "" : b.id().toString(), null), i.code(), null), context.time.now());
        procurement.searchQuery(Long.parseLong(p.id()),i.query());
        if (changed)
            invalidate(Long.parseLong(p.id()));
    }

    private void invalidate(long id) {
        procurement.invalidate(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void bulk(Long actor, BulkEdit input) {
        context.authorize(actor, false);
        context.lock();
        var ids = checkVersions(input.products());
        if (input.brand() == null && input.brandId() == null && input.managed() == null)
            throw new InputValidationFailure("변경할 값을 입력해 주세요.");
        Brand b = input.brand() != null || input.brandId() != null ? resolve(input.brandId(), input.brand()) : null;
        for (long id : ids) {
            if (b != null) {
                var p = product(id);
                catalog.editBrand(id, p.revision(), new BrandSelection(b.id() == null ? "" : b.id().toString(), null), context.time.now());
            }
            if (input.managed() != null)
                {
                procurement.managed(id,input.managed());
                catalog.bump(id,context.time.now());
            }
        }
    }

    private List<Long> checkVersions(List<VersionedId> values) {
        return catalog.checkVersions(values == null ? null : values.stream()
                .map(v -> v == null ? null : new VersionedProduct(v.id(), v.revision())).toList());
    }

    private void ensureIdle(List<Long> ids) {
        procurement.ensureIdle(ids);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long actor, DeleteProducts input) {
        context.authorize(actor, false);
        context.lock();
        var ids = checkVersions(input.products());
        ensureIdle(ids);
        inventory.assertDeletable(ids);
        for (long id : ids) {
            catalog.delete(id,actor,context.time.now());
            procurement.deleted(id);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProcurementProductView merge(Long actor, long target, MergeInput input) {
        context.authorize(actor, false);
        context.lock();
        var ids = checkVersions(input.products());
        ensureIdle(ids);
        if (input.managed() == null) throw new InputValidationFailure("대표 상품을 포함한 통합 대상을 선택해 주세요.");
        String code = CatalogCodes.normalize(input.productCode());
        catalog.validateMerge(target, ids, code);
        var b = resolve(input.brandId(), input.brand());
        procurement.validateSearchQuery(input.searchQuery());
        String chosen = suppliers.mergeChoice(ids, input.selectedSupplierId());
        var before = ids.stream().map(id -> detail(actor, id)).toList();
        for (long id : ids) {
            if (id == target)
                continue;
            chosen = suppliers.moveListings(target, id, chosen);
            changes.merge(target, id);
            inventory.merge(target, id);
            procurement.moveHistory(target,id);
            catalog.mergeReference(target,id);
            procurement.mergedSource(id);
        }
        suppliers.mergeSelections(actor, target, ids, chosen);
        var p = product(target);
        update(p, b, identity(
                new ProductEditRequest(p.revision(), input.brand(), code, input.searchQuery(), input.brandId(), null, null)));
        invalidate(target);
        catalog.mergeRegistrationNames(target,ids);
        procurement.managed(target,input.managed());
        catalog.mergeHistory(cc.ataglace.molebutter.common.api.BusinessIds.next(),target,actor,context.time.now(),context.encode(before),context.encode(detail(actor,target)));
        return product(target);
    }

    private List<Long> matchingIds(String code) { return catalog.matchingIds(code); }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BrandInferenceResult inferBrands(Long actor, BrandInferenceInput input) {
        context.authorize(actor, false);
        context.lock();
        return assignMissingBrands(checkVersions(input.products()));
    }

    private BrandInferenceResult assignMissingBrands(List<Long> ids) {
        var result = catalog.inferBrands(ids, context.time.now());
        return new BrandInferenceResult(result.assigned(), result.preserved(), result.unresolved());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ImportResult upload(Long actor, String name, byte[] bytes) {
        context.authorize(actor, false);
        var parsed = excel.parse(bytes);
        context.lock();
        Map<String, List<ProductRegistrationWorkbook.Row>> groups = new LinkedHashMap<>();
        var warnings = new ArrayList<>(parsed.warnings());
        int excluded = 0, duplicates = 0, created = 0, existing = 0;
        for (var row : parsed.rows()) {
            String code = ProductCodePolicy.normalize(row.code());
            if (code.isBlank()) {
                excluded++;
                continue;
            }
            if (!catalog.validRegistrationRow(code, row.title())) {
                excluded++;
                warnings.add(row.index() + "행: 상품코드 또는 등록명이 너무 깁니다.");
                continue;
            }
            groups.computeIfAbsent(code, k -> new ArrayList<>()).add(row);
        }
        List<Long> touched = new ArrayList<>(), newProducts = new ArrayList<>();
        for (var e : groups.entrySet()) {
            var found = matchingIds(e.getKey());
            if (found.size() > 1) {
                excluded += e.getValue().size();
                warnings.add(e.getValue().getFirst().index() + "행: 같은 코드의 기존 상품이 여러 개입니다. 통합 후 등록해 주세요.");
                continue;
            }
            duplicates += e.getValue().size() - 1;
            var titles = e.getValue().stream().map(ProductRegistrationWorkbook.Row::title).filter(t -> !t.isBlank())
                    .distinct().toList();
            long id;
            if (found.isEmpty()) {
                var b = new Brand(null, "", "");
                id = Long
                        .parseLong(insert(b, new Identity(e.getKey(), "GENERAL", "", e.getKey(), "AUTO"), titles).id());
                newProducts.add(id);
                created++;
            } else {
                id = found.getFirst();
                catalog.appendRegistrationNames(id,titles);
                existing++;
            }
            touched.add(id);
        }
        int assigned = assignMissingBrands(touched).assigned();
        for (long id : newProducts) procurement.initialSearch(id);
        notifications.publish("import:" + cc.ataglace.molebutter.common.api.BusinessIds.next(), "PRODUCT_IMPORT", excluded > 0 ? "WARNING" : "INFO",
                "상품 일괄등록 완료", "신규 " + created + " / 기존 " + existing + " / 중복 " + duplicates + " / 제외 " + excluded,
                "IMPORT", null, List.of(actor));
        return new ImportResult(parsed.rows().size(), created, existing, duplicates, excluded, assigned,
                List.copyOf(warnings));
    }

    public ScheduleSettings schedule(Long actor){return schedules.schedule(actor);}
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScheduleSettings updateSchedule(Long actor,ScheduleSettings settings){return schedules.updateSchedule(actor,settings);}
}
