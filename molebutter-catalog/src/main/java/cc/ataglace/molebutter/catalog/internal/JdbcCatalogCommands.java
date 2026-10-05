package cc.ataglace.molebutter.catalog.internal;

import cc.ataglace.molebutter.catalog.api.*;
import cc.ataglace.molebutter.common.api.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcCatalogCommands implements CatalogCommands {
    private final JdbcTemplate jdbc;
    private final CatalogConsistencyGuard guard;
    private final ObjectMapper json;

    private Map<String, Object> active(long id) {
        var rows = jdbc.queryForList("SELECT * FROM catalog_product WHERE id=? AND deleted_at IS NULL AND merged_into IS NULL", id);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND);
        return rows.getFirst();
    }
    private void exists(long id) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM catalog_product WHERE id=?", Long.class, id) != 1)
            throw new BusinessException(ErrorCode.NOT_FOUND);
    }
    private static String text(String value, int max, boolean required) {
        return BusinessText.checked(value, max, required, "필수 입력값과 입력 길이를 확인해 주세요.");
    }
    private static String code(String value) {
        return text(CatalogCodes.normalize(text(value, 100, true)), 100, true);
    }
    private static List<String> names(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).filter(v -> !v.isBlank()).peek(v -> text(v, 1000, false)).distinct().toList();
    }
    private static void changed(int count) {
        if (count != 1) throw new BusinessException(ErrorCode.OPTIMISTIC_LOCKING_FAILURE);
    }

    public Brand resolveBrand(BrandSelection selection) {
        String id = selection == null ? null : selection.id(), name = selection == null ? null : selection.name();
        if (id != null && id.isBlank() || id == null && (name == null || name.isBlank())) return new Brand(null, "", "");
        List<Map<String,Object>> rows;
        if (id != null) {
            long number;
            try { number = Long.parseLong(id); if (number <= 0) throw new NumberFormatException(); }
            catch (NumberFormatException e) { throw new InputValidationFailure("공통 설정에 등록된 브랜드를 선택해 주세요."); }
            rows = jdbc.queryForList("SELECT id,name,code_brand FROM product_brand WHERE id=?", number);
        } else rows = jdbc.queryForList("SELECT id,name,code_brand FROM product_brand WHERE name=?", text(name, 100, true));
        if (rows.isEmpty()) throw new InputValidationFailure("공통 설정에 등록된 브랜드를 선택해 주세요.");
        var row = rows.getFirst();
        return new Brand(((Number) row.get("id")).longValue(), row.get("name").toString(), row.get("code_brand").toString());
    }
    public List<Long> matchingIds(String value) {
        String normalized = CatalogCodes.normalize(value);
        if (normalized.isBlank()) return List.of();
        return jdbc.queryForList("SELECT id FROM catalog_product WHERE UPPER(TRIM(product_code))=? AND merged_into IS NULL AND deleted_at IS NULL ORDER BY id", Long.class, normalized);
    }
    public List<String> registrationNames(long id) {
        exists(id);
        String value = jdbc.queryForObject("SELECT registration_names FROM catalog_product WHERE id=?", String.class, id);
        return value == null ? List.of() : List.of(json.readValue(value, String[].class));
    }
    public List<Long> checkVersions(List<VersionedProduct> values) {
        guard.requireExclusive();
        if (values == null || values.isEmpty() || values.stream().anyMatch(Objects::isNull)) throw new InputValidationFailure("상품을 선택해 주세요.");
        var ids = BusinessIds.parseList(values.stream().map(VersionedProduct::id).toList());
        if (ids.size() != values.size()) throw new InputValidationFailure("선택이 중복되었습니다.");
        for (var value : values) checkRevision(Long.parseLong(value.id()), value.revision());
        return ids;
    }
    public boolean validRegistrationRow(String code, String title) {
        return CatalogCodes.normalize(code).length() <= 100 && title != null && title.length() <= 1000;
    }
    public void appendRegistrationNames(long id, List<String> values) {
        guard.requireExclusive(); active(id);
        var all = new LinkedHashSet<>(registrationNames(id)); all.addAll(names(values));
        registrationNames(id, List.copyOf(all));
    }
    public void mergeRegistrationNames(long target, List<Long> ids) {
        guard.requireExclusive(); active(target);
        var all = new LinkedHashSet<String>();
        for (long id : ids) all.addAll(registrationNames(id));
        registrationNames(target, List.copyOf(all));
    }
    public void checkRevision(long id, Long expected) {
        guard.requireExclusive();
        BusinessRevision.check(((Number) active(id).get("revision")).longValue(), expected);
    }
    public void validateMerge(long target, List<Long> ids, String value) {
        guard.requireExclusive();
        if (ids == null || ids.size() < 2 || ids.stream().anyMatch(Objects::isNull) || new HashSet<>(ids).size() != ids.size() || !ids.contains(target))
            throw new InputValidationFailure("대표 상품을 포함한 통합 대상을 선택해 주세요.");
        String normalized = CatalogCodes.normalize(value);
        if (normalized.isBlank() || ids.stream().anyMatch(id -> !CatalogCodes.normalize(active(id).get("product_code").toString()).equals(normalized)))
            throw new InputValidationFailure("동일한 전체 상품코드만 통합할 수 있습니다. 시즌이 다른 상품은 별도로 유지합니다.");
    }
    public void create(long id, ProductInput input, LocalDateTime at) {
        guard.requireExclusive();
        Objects.requireNonNull(input, "product input");
        Brand brand = resolveBrand(input.brand()); String code = code(input.productCode());
        if (!matchingIds(code).isEmpty()) throw new OperationFailure("같은 전체 상품코드가 이미 등록되어 있습니다.");
        changed(jdbc.update("INSERT INTO catalog_product(id,brand,brand_id,product_code,registration_names,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                id, brand.name(), brand.id(), code, json.writeValueAsString(names(input.registrationNames())), at, at));
    }
    public void edit(long id, long expected, ProductInput input, LocalDateTime at) {
        guard.requireExclusive();
        var old = active(id); BusinessRevision.check(((Number) old.get("revision")).longValue(), expected);
        Brand brand = resolveBrand(input.brand()); String code = code(input.productCode());
        // Existing historical duplicates can still be edited and merged without changing their code.
        if (!code.equals(old.get("product_code")) && matchingIds(code).stream().anyMatch(other -> other != id))
            throw new OperationFailure("같은 전체 상품코드가 이미 등록되어 있습니다.");
        changed(jdbc.update("UPDATE catalog_product SET brand=?,brand_id=?,product_code=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                brand.name(), brand.id(), code, at, id, expected));
        if (input.registrationNames() != null) registrationNames(id, input.registrationNames());
    }
    public BrandInference inferBrands(List<Long> ids, LocalDateTime at) {
        guard.requireExclusive();
        var brands = new LinkedHashMap<Long, String>();
        jdbc.query("SELECT id,name FROM product_brand ORDER BY id", row -> { brands.put(row.getLong(1), row.getString(2)); });
        var matcher = new ProductBrandMatcher(brands);
        int assigned = 0, preserved = 0, unresolved = 0;
        for (long id : ids) {
            var product = active(id);
            if (product.get("brand_id") != null) { preserved++; continue; }
            Long selected = matcher.matchAll(registrationNames(id));
            if (selected == null) { unresolved++; continue; }
            Brand brand = resolveBrand(new BrandSelection(selected.toString(), null));
            // Brand-only inference must not normalize historical codes or change lookup criteria.
            changed(jdbc.update("UPDATE catalog_product SET brand=?,brand_id=?,revision=revision+1,updated_at=? WHERE id=? AND revision=?",
                    brand.name(), brand.id(), at, id, product.get("revision")));
            assigned++;
        }
        return new BrandInference(assigned, preserved, unresolved);
    }
    public void bump(long id, LocalDateTime at) {
        guard.requireExclusive(); active(id);
        changed(jdbc.update("UPDATE catalog_product SET revision=revision+1,updated_at=? WHERE id=?", at, id));
    }
    public void bump(long id) {
        guard.requireExclusive(); active(id);
        changed(jdbc.update("UPDATE catalog_product SET revision=revision+1 WHERE id=?", id));
    }
    public void delete(long id, Long actor, LocalDateTime at) {
        guard.requireExclusive(); active(id);
        changed(jdbc.update("UPDATE catalog_product SET deleted_at=?,deleted_by=?,revision=revision+1,updated_at=? WHERE id=?", at, actor, at, id));
    }
    public void mergeReference(long target, long source) {
        guard.requireExclusive();
        validateMerge(target, List.of(target, source), active(target).get("product_code").toString());
        changed(jdbc.update("UPDATE catalog_product SET merged_into=?,revision=revision+1 WHERE id=?", target, source));
    }
    public void mergeHistory(long id, long target, Long actor, LocalDateTime at, String before, String after) {
        guard.requireExclusive(); active(target);
        changed(jdbc.update("INSERT INTO catalog_merge_history(id,target_id,actor_id,created_at,before_snapshot,after_snapshot) VALUES(?,?,?,?,?,?)", id, target, actor, at, before, after));
    }
    public void registrationNames(long id, List<String> values) {
        guard.requireExclusive(); active(id);
        // An unchanged value is legitimate; existence is checked independently of JDBC affected-row settings.
        jdbc.update("UPDATE catalog_product SET registration_names=? WHERE id=?", json.writeValueAsString(names(values)), id);
    }
}
