package cc.ataglace.molebutter.app.internal;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.catalog.api.ProductBrandMatcher;
import cc.ataglace.molebutter.procurement.api.ProductCodePolicy;
import cc.ataglace.molebutter.procurement.api.ProductSupplierService;
import cc.ataglace.molebutter.procurement.api.ProcurementScheduleService;
import cc.ataglace.molebutter.catalog.api.ProductRegistrationWorkbook;
import cc.ataglace.molebutter.procurement.api.ProcurementLifecycle;
import cc.ataglace.molebutter.procurement.api.ProductChangeService;
import cc.ataglace.molebutter.procurement.api.ProcurementProductQueries;
import cc.ataglace.molebutter.app.internal.ApplicationProductStore;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import cc.ataglace.molebutter.common.api.PageResponse;

import cc.ataglace.molebutter.common.api.OperationFailure;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {
    private final ApplicationProductStore db;
    private final ProcurementProductQueries queries;
    private final ProcurementLifecycle procurement;
    private final ProcurementScheduleService schedules;
    private final cc.ataglace.molebutter.catalog.api.CatalogCommands catalog;
    private final cc.ataglace.molebutter.inventory.api.InventoryLinkService inventory;
    private final ProductChangeService changes;
    private final cc.ataglace.molebutter.operations.api.NotificationService notifications;
    private final ProductSupplierService suppliers;
    private final ProductRegistrationWorkbook excel;
    public PageResponse<CatalogProduct> list(Long a,String q,String m,String s,int p){return queries.list(a,q,m,s,p);}
    public PageResponse<CatalogProduct> list(Long a,String q,String m,String s,int p,int z){return queries.list(a,q,m,s,p,z);}
    public PageResponse<CatalogProduct> list(Long a,String q,String m,String s,int p,int z,String c){return queries.list(a,q,m,s,p,z,c);}
    public cc.ataglace.molebutter.procurement.api.ChangeDtos.Counts changeCounts(Long a,String q,String m,String s){return queries.changeCounts(a,q,m,s);}
    public CatalogProduct product(long id){return queries.product(id);}
    public Map<String,Object> detail(Long a,long id){return queries.detail(a,id);}
    public PageResponse<Map<String,Object>> history(Long a,long id,int p){return queries.history(a,id,p);}
    public List<String> brands(Long a){return queries.brands(a);}
    public List<CatalogProduct> duplicates(Long a,long id){return queries.duplicates(a,id);}

    private record Brand(Long id, String name, String key) {
    }

    private Brand resolve(String id, String name) {
        if (id != null && id.isBlank() || id == null && (name == null || name.isBlank()))
            return new Brand(null, "", "");
        var rows = id != null ? db.jdbc.queryForList("SELECT * FROM product_brand WHERE id=?", Long.valueOf(id))
                : db.jdbc.queryForList("SELECT * FROM product_brand WHERE name=?", ApplicationProductStore.text(name, 100, true));
        if (rows.isEmpty())
            throw new InputValidationFailure("공통 설정에 등록된 브랜드를 선택해 주세요.");
        var r = rows.getFirst();
        return new Brand(((Number) r.get("id")).longValue(), r.get("name").toString(), r.get("code_brand").toString());
    }

    private record Identity(String code, String type, String comparison, String query, String mode) {
    }

    private Identity identity(CatalogEdit input) {
        String code = ProductCodePolicy.normalize(ApplicationProductStore.text(input.productCode(), 100, true));
        String query = ApplicationProductStore.text(input.searchQuery(), 255, true);
        return new Identity(code, "GENERAL", "", query, "MANUAL");
    }

    private Identity existingIdentity(CatalogProduct p) {
        return new Identity(p.productCode(), p.codeType(), p.comparisonCode(), p.searchQuery(), p.searchMode());
    }

    // Compatibility endpoint only; registration and edits never apply its
    // suggestion.
    public Map<String, String> codePreview(Long actor, CatalogEdit input) {
        db.authorize(actor, false);
        var b = resolve(input.brandId(), input.brand());
        String code = ProductCodePolicy.normalize(ApplicationProductStore.text(input.productCode(), 100, false)),
                type = b.key().isBlank() ? "GENERAL" : "LF_ACCESSORY";
        return Map.of("comparisonCode", ProductCodePolicy.comparison(type, code), "codeType", type, "suggestedQuery",
                ProductCodePolicy.suggested(type, code));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CatalogProduct create(Long actor, CatalogEdit input) {
        db.authorize(actor, false);
        db.lock();
        Brand b = resolve(input.brandId(), input.brand());
        Identity i = identity(input);
        if (i.code().isBlank())
            throw new InputValidationFailure("전체 상품코드를 입력해 주세요.");
        if (!matchingIds(i.code()).isEmpty())
            throw new OperationFailure("같은 전체 상품코드가 이미 등록되어 있습니다.");
        return insert(b, i, List.of());
    }

    private CatalogProduct insert(Brand b, Identity i, List<String> names) {
        long id = ApplicationProductStore.id();
        catalog.create(id, b.name(), b.id(), i.code(), db.encode(names), db.time.now());
        procurement.create(id,i.type(),i.comparison(),i.query(),i.mode());
        return product(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CatalogProduct edit(Long actor, long id, CatalogEdit input) {
        db.authorize(actor, false);
        db.lock();
        var p = product(id);
        catalog.checkRevision(id, input.revision());
        Brand b = resolve(input.brandId(), input.brand());
        Identity i = identity(input);
        if (!i.code().equals(p.productCode()) && matchingIds(i.code()).stream().anyMatch(v -> v != id))
            throw new OperationFailure("같은 전체 상품코드가 이미 등록되어 있습니다.");
        update(p, b, i);
        return product(id);
    }

    private void update(CatalogProduct p, Brand b, Identity i) {
        boolean changed = !i.code().equals(p.productCode()) || !i.query().equals(p.searchQuery());
        catalog.edit(Long.parseLong(p.id()), p.revision(), b.name(), b.id(), i.code(), db.time.now());
        procurement.searchQuery(Long.parseLong(p.id()),i.query());
        if (changed)
            invalidate(Long.parseLong(p.id()));
    }

    private void invalidate(long id) {
        procurement.invalidate(id);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void bulk(Long actor, BulkEdit input) {
        db.authorize(actor, false);
        db.lock();
        var ids = checkVersions(input.products());
        if (input.brand() == null && input.brandId() == null && input.managed() == null)
            throw new InputValidationFailure("변경할 값을 입력해 주세요.");
        Brand b = input.brand() != null || input.brandId() != null ? resolve(input.brandId(), input.brand()) : null;
        for (long id : ids) {
            if (b != null) {
                var p = product(id);
                update(p, b, existingIdentity(p));
            }
            if (input.managed() != null)
                {
                procurement.managed(id,input.managed());
                catalog.bump(id,db.time.now());
            }
        }
    }

    private List<Long> checkVersions(List<VersionedId> values) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(Objects::isNull))
            throw new InputValidationFailure("상품을 선택해 주세요.");
        var ids = ApplicationProductStore.ids(values.stream().map(VersionedId::id).toList());
        if (ids.size() != values.size())
            throw new InputValidationFailure("선택이 중복되었습니다.");
        for (var v : values)
            {
            product(Long.parseLong(v.id()));
            catalog.checkRevision(Long.parseLong(v.id()), v.revision());
            }
        return ids;
    }

    private void ensureIdle(List<Long> ids) {
        procurement.ensureIdle(ids);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long actor, DeleteProducts input) {
        db.authorize(actor, false);
        db.lock();
        var ids = checkVersions(input.products());
        ensureIdle(ids);
        inventory.assertDeletable(ids);
        for (long id : ids) {
            catalog.delete(id,actor,db.time.now());
            procurement.deleted(id);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CatalogProduct merge(Long actor, long target, MergeInput input) {
        db.authorize(actor, false);
        db.lock();
        var ids = checkVersions(input.products());
        ensureIdle(ids);
        if (ids.size() < 2 || !ids.contains(target) || input.managed() == null)
            throw new InputValidationFailure("대표 상품을 포함한 통합 대상을 선택해 주세요.");
        String code = ProductCodePolicy.normalize(input.productCode());
        if (code.isBlank()
                || ids.stream().anyMatch(id -> !ProductCodePolicy.normalize(product(id).productCode()).equals(code)))
            throw new InputValidationFailure("동일한 전체 상품코드만 통합할 수 있습니다. 시즌이 다른 상품은 별도로 유지합니다.");
        String chosen = suppliers.mergeChoice(ids, input.selectedSupplierId());
        var before = ids.stream().map(id -> detail(actor, id)).toList();
        Set<String> names = new LinkedHashSet<>();
        for (long id : ids) {
            names.addAll(registrationNames(id));
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
        var b = resolve(input.brandId(), input.brand());
        var p = product(target);
        update(p, b, identity(
                new CatalogEdit(p.revision(), input.brand(), code, input.searchQuery(), input.brandId(), null, null)));
        invalidate(target);
        catalog.registrationNames(target,db.encode(names));
        procurement.managed(target,input.managed());
        catalog.mergeHistory(ApplicationProductStore.id(),target,actor,db.time.now(),db.encode(before),db.encode(detail(actor,target)));
        return product(target);
    }

    private List<Long> matchingIds(String code) {
        if (code.isBlank())
            return List.of();
        return db.jdbc.queryForList(
                "SELECT id FROM catalog_product WHERE UPPER(TRIM(product_code))=? AND merged_into IS NULL AND deleted_at IS NULL ORDER BY id",
                Long.class, code);
    }

    private List<String> registrationNames(long id) {
        String value = db.jdbc.queryForObject("SELECT registration_names FROM catalog_product WHERE id=?", String.class,
                id);
        return value == null ? List.of() : List.of(db.decode(value, String[].class));
    }

    private Map<Long, String> brandNames() {
        Map<Long, String> map = new LinkedHashMap<>();
        db.jdbc.query("SELECT id,name FROM product_brand ORDER BY id", r -> {
            map.put(r.getLong(1), r.getString(2));
        });
        return map;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BrandInferenceResult inferBrands(Long actor, BrandInferenceInput input) {
        db.authorize(actor, false);
        db.lock();
        return assignMissingBrands(checkVersions(input.products()));
    }

    private BrandInferenceResult assignMissingBrands(List<Long> ids) {
        var matcher = new ProductBrandMatcher(brandNames());
        int changed = 0, preserved = 0, unresolved = 0;
        for (long id : ids) {
            var p = product(id);
            if (p.brandId() != null) {
                preserved++;
                continue;
            }
            Long brand = matcher.matchAll(registrationNames(id));
            if (brand == null) {
                unresolved++;
                continue;
            }
            var b = resolve(brand.toString(), null);
            update(p, b, existingIdentity(p));
            changed++;
        }
        return new BrandInferenceResult(changed, preserved, unresolved);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ImportResult upload(Long actor, String name, byte[] bytes) {
        db.authorize(actor, false);
        var parsed = excel.parse(bytes);
        db.lock();
        Map<String, List<ProductRegistrationWorkbook.Row>> groups = new LinkedHashMap<>();
        var warnings = new ArrayList<>(parsed.warnings());
        int excluded = 0, duplicates = 0, created = 0, existing = 0;
        for (var row : parsed.rows()) {
            String code = ProductCodePolicy.normalize(row.code());
            if (code.isBlank()) {
                excluded++;
                continue;
            }
            if (code.length() > 100 || row.title().length() > 1000) {
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
                Set<String> all = new LinkedHashSet<>(registrationNames(id));
                all.addAll(titles);
                catalog.registrationNames(id,db.encode(all));
                existing++;
            }
            touched.add(id);
        }
        int assigned = assignMissingBrands(touched).assigned();
        for (long id : newProducts) {
            var p = product(id);
            String query = ProductCodePolicy.suggested(p.brandKey().isBlank() ? "GENERAL" : "LF_ACCESSORY",
                    p.productCode());
            procurement.initialSearch(id,query);
        }
        notifications.publish("import:" + ApplicationProductStore.id(), "PRODUCT_IMPORT", excluded > 0 ? "WARNING" : "INFO",
                "상품 일괄등록 완료", "신규 " + created + " / 기존 " + existing + " / 중복 " + duplicates + " / 제외 " + excluded,
                "IMPORT", null, List.of(actor));
        return new ImportResult(parsed.rows().size(), created, existing, duplicates, excluded, assigned,
                List.copyOf(warnings));
    }

    public ScheduleSettings schedule(Long actor){return schedules.schedule(actor);}
    public ScheduleSettings updateSchedule(Long actor,ScheduleSettings settings){return schedules.updateSchedule(actor,settings);}
}
