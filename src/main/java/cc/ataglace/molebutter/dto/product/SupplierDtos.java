package cc.ataglace.molebutter.dto.product;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;

public final class SupplierDtos {
    private SupplierDtos() {
    }

    public record ExternalIdentity(String namespace, String externalId) {
    }

    public record Store(String id, ProcurementMall mall, String kind, String name, String identityKey, List<String> aliases,
            long revision, String retailer, List<ExternalIdentity> identities) {
        public Store {
            identities = identities == null ? List.of() : List.copyOf(identities);
        }

        public Store(String id, ProcurementMall mall, String kind, String name, String identityKey, List<String> aliases,
                long revision) {
            this(id, mall, kind, name, identityKey, aliases, revision, null, List.of());
        }
    }

    public record IdentityCandidate(String supplierId, String observedRunId, ProcurementMall mall, StoreEvidence evidence) {
    }

    public record IdentityInput(Long revision, String supplierId, String observedRunId) {
    }

    public record Rule(String id, ProcurementMall mall, String storeId, long revision) {
    }

    public record MallPreference(ProcurementMall mall, String scope, List<String> storeIds, boolean branchRequired) {
    }

    public record PreferenceView(long revision, List<Store> stores, List<Rule> rules, List<MallPreference> groups,
            Map<ProcurementMall, Boolean> branchRequirements) {
        @com.fasterxml.jackson.annotation.JsonProperty
        public List<StoreInput> companyChoices() {
            return List.of(new StoreInput(null, ProcurementMall.LOTTE_ON, "COMPANY", "주식회사 LF", null, null));
        }

        public PreferenceView(Preferences p) {
            this(p.revision(), p.stores(), p.rules(), p.groups(), p.branchRequirements());
        }
    }

    public record MallPreferenceInput(Long revision, String scope, List<String> storeIds, List<StoreInput> newStores,
            Boolean branchRequired) {
        public MallPreferenceInput(Long revision, String scope, List<String> storeIds, List<StoreInput> newStores) {
            this(revision, scope, storeIds, newStores, null);
        }
    }

    public record Preferences(long revision, List<Store> stores, List<Rule> rules,
            Map<ProcurementMall, Boolean> branchRequirements) {
        // 필드가 없던 과거 작업은 모든 쇼핑몰의 지점을 확인하던 기준을 유지한다.
        public Preferences {
            branchRequirements = branchRequirements == null ? Map.of() : Map.copyOf(branchRequirements);
        }

        public Preferences(long revision, List<Store> stores, List<Rule> rules) {
            this(revision, stores, rules, Map.of());
        }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public List<MallPreference> groups() {
            return rules.stream().map(Rule::mall).distinct().map(mall -> {
                var selected = rules.stream().filter(r -> r.mall() == mall).toList();
                boolean all = !branchRequired(mall) || selected.stream().anyMatch(r -> r.storeId() == null);
                return new MallPreference(mall, all ? "ALL" : "STORES",
                        all ? List.of() : selected.stream().map(Rule::storeId).distinct().toList(),
                        branchRequired(mall));
            }).toList();
        }

        public boolean branchRequired(ProcurementMall mall) {
            return mall == null || branchRequirements.getOrDefault(mall, true);
        }

        public boolean mallAllowed(ProcurementMall mall) {
            return rules.stream().anyMatch(r -> r.mall() == mall);
        }

        public boolean allowed(ProcurementMall mall, String storeId) {
            return !branchRequired(mall) ? mallAllowed(mall)
                    : rules.stream()
                            .anyMatch(r -> r.mall() == mall && (r.storeId() == null || r.storeId().equals(storeId)));
        }
    }

    public record StoreInput(Long revision, ProcurementMall mall, String kind, String name, String sellerKey, String retailer,
            String productUrl, String expectedChannelUid, Long preferenceRevision) {
        public StoreInput(Long revision, ProcurementMall mall, String kind, String name, String sellerKey, String retailer) {
            this(revision, mall, kind, name, sellerKey, retailer, null, null, null);
        }

        public StoreInput(Long revision, ProcurementMall mall, String kind, String name, String sellerKey) {
            this(revision, mall, kind, name, sellerKey, null);
        }
    }

    public record ChannelRequest(String productUrl) {
    }

    public record ChannelPreview(String productId, String channelUid, String channelName, long preferenceRevision) {
    }

    public record RuleInput(Long revision, ProcurementMall mall, String storeId) {
    }

    public record AssignmentInput(Long revision, String storeId, StoreInput newStore, boolean addPreferred,
            Long preferenceRevision) {
        public AssignmentInput(Long revision, String storeId) {
            this(revision, storeId, null, false, null);
        }
    }

    public record SelectionInput(Long revision, String supplierId) {
    }

    public record Listing(String id, long revision, ProcurementMall mall, Store store, boolean manual, boolean conflict,
            boolean preferred, boolean current, boolean selected, String priceStatus,
            Long referencePrice, Long deliveryFee, LocalDateTime priceCheckedAt,
            String inventoryState, String url, String imageUrl, SupplierResult result, String storeStatus,
            boolean branchRequired, Long recommendationSaving, boolean selectedMissing,
            LocalDateTime selectedSearchAt) {
        public Listing(String id, long revision, ProcurementMall mall, Store store, boolean manual, boolean conflict,
                boolean preferred, boolean current, boolean selected, String priceStatus, Long referencePrice,
                Long deliveryFee, LocalDateTime priceCheckedAt, String inventoryState, String url, String imageUrl,
                SupplierResult result, String storeStatus, boolean branchRequired, Long recommendationSaving) {
            this(id, revision, mall, store, manual, conflict, preferred, current, selected, priceStatus, referencePrice,
                    deliveryFee, priceCheckedAt, inventoryState, url, imageUrl, result, storeStatus, branchRequired,
                    recommendationSaving, false, null);
        }

        public Listing withSearchEvidence(boolean missing, LocalDateTime at) {
            return new Listing(id, revision, mall, store, manual, conflict, preferred, current, selected, priceStatus,
                    referencePrice, deliveryFee, priceCheckedAt, inventoryState, url, imageUrl, result, storeStatus,
                    branchRequired, recommendationSaving, missing, at);
        }

        public Listing(String id, long revision, ProcurementMall mall, Store store, boolean manual, boolean conflict,
                boolean preferred, boolean current, boolean selected, String priceStatus, Long referencePrice,
                Long deliveryFee, LocalDateTime priceCheckedAt, String inventoryState, String url, String imageUrl,
                SupplierResult result, String storeStatus, boolean branchRequired) {
            this(id, revision, mall, store, manual, conflict, preferred, current, selected, priceStatus, referencePrice,
                    deliveryFee, priceCheckedAt, inventoryState, url, imageUrl, result, storeStatus, branchRequired,
                    null);
        }

        public Listing recommended(long saving) {
            return new Listing(id, revision, mall, store, manual, conflict, preferred, current, selected, priceStatus,
                    referencePrice, deliveryFee, priceCheckedAt, inventoryState, url, imageUrl, result, storeStatus,
                    branchRequired, saving);
        }

        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean requiresReview() {
            return result == null || !cc.ataglace.molebutter.infra.product.NaverChannelPolicy.comparable(result.offer())
                    || branchRequired && cc.ataglace.molebutter.service.product.SupplierStorePolicy
                            .storeContradiction(store, result == null ? null : result.branch())
                    || branchRequired
                            && (!manual && !"CONFIRMED".equals(storeStatus) || store == null || conflict && !manual)
                    || !result.accepted() || "STALE".equals(priceStatus);
        }

        @com.fasterxml.jackson.annotation.JsonProperty
        public String selectionUnavailableReason() {
            if (result != null && !cc.ataglace.molebutter.infra.product.NaverChannelPolicy.comparable(result.offer()))
                return "쇼핑윈도로 확인된 판매글만 선정할 수 있습니다.";
            if (result != null && result.offer().mall() == ProcurementMall.NAVER_SMART_STORE
                    && (result.skipped() || result.stockEvidence() != null && !result.stockEvidence().directVerified()))
                return "재고 조회로 해당 판매글을 확인한 뒤 선정해 주세요.";
            if (requiresReview())
                return "매장 정보나 조회 기준을 확인하고 다시 최신화해 주세요.";
            if (!preferred && recommendationSaving == null)
                return "선호 목록에 포함된 판매글을 선택해 주세요.";
            return null;
        }

        @com.fasterxml.jackson.annotation.JsonProperty
        public boolean selectable() {
            return selectionUnavailableReason() == null;
        }
    }

    public record Group(String id, ProcurementMall mall, Store store, boolean selected, Long minPrice, Long maxPrice,
            List<Listing> listings, boolean branchRequired) {
    }

    public record SelectionBasis(String supplierId, String listingKey, LocalDateTime selectedAt, String changeId,
            String lookupPolicy, String naverPolicy, String stockPolicy) {
        public SelectionBasis(String supplierId, String listingKey, LocalDateTime selectedAt, String changeId,
                String lookupPolicy, String naverPolicy) {
            this(supplierId, listingKey, selectedAt, changeId, lookupPolicy, naverPolicy, null);
        }

        public SelectionBasis(String supplierId, String listingKey, LocalDateTime selectedAt, String changeId,
                String lookupPolicy) {
            this(supplierId, listingKey, selectedAt, changeId, lookupPolicy, null);
        }

        public SelectionBasis(String supplierId, String listingKey, LocalDateTime selectedAt, String changeId) {
            this(supplierId, listingKey, selectedAt, changeId, "SEARCH_QUERY", "WINDOW_ONLY", "STORE_REPRESENTATIVE");
        }

        public boolean usesSearchQuery() {
            return "SEARCH_QUERY".equals(lookupPolicy);
        }

        public boolean usesWindowOnly() {
            return "WINDOW_ONLY".equals(naverPolicy);
        }

        public boolean matches(Offer offer) {
            return supplierId != null && java.util.Objects.equals(listingKey,
                    cc.ataglace.molebutter.service.product.SupplierStorePolicy.listingKey(offer));
        }
    }

    public record RecommendationStatus(String state, String supplierId, Long referencePrice, boolean limited) {
    }

    public record Comparison(List<Group> groups, List<Listing> pending, List<Listing> excluded, Listing selected,
            List<Store> stores, boolean preferencesConfigured, List<Group> recommendations,
            RecommendationStatus recommendationStatus, List<StockLookupJob> stockLookups, ChangeDtos.Summary changes) {
        public Comparison(List<Group> groups, List<Listing> pending, List<Listing> excluded, Listing selected,
                List<Store> stores, boolean configured, List<Group> recommendations, RecommendationStatus status,
                List<StockLookupJob> jobs) {
            this(groups, pending, excluded, selected, stores, configured, recommendations, status, jobs, null);
        }

        public Comparison withChanges(ChangeDtos.Summary value) {
            return new Comparison(groups, pending, excluded, selected, stores, preferencesConfigured, recommendations,
                    recommendationStatus, stockLookups, value);
        }

        public Comparison(List<Group> groups, List<Listing> pending, List<Listing> excluded, Listing selected,
                List<Store> stores, boolean configured, List<Group> recommendations, RecommendationStatus status) {
            this(groups, pending, excluded, selected, stores, configured, recommendations, status, List.of());
        }
    }

    public record StockLookupInput(Long revision, Long supplierRevision) {
    }

    public record StockLookupJob(String id, String productId, String supplierId, String status, long revision,
            String message, LocalDateTime createdAt, LocalDateTime finishedAt, boolean blocking) {
    }
}
