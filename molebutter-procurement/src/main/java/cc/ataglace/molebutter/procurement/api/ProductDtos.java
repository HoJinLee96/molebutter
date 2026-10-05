package cc.ataglace.molebutter.procurement.api;

import java.time.LocalDateTime;
import java.util.List;

public final class ProductDtos {
    private ProductDtos() {
    }

    public enum ProcurementMall {
        LFMALL("LF몰"), HAZZYS("헤지스"), NAVER_SMART_STORE("네이버 쇼핑윈도"), LOTTE_ON("롯데온"),
        LOTTE_IMALL("롯데홈쇼핑"), HI_THEHYUNDAI("더현대Hi"), HMALL("현대Hmall");

        private final String displayName;

        ProcurementMall(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public record ProcurementProductView(String id, String brand, String brandId, String productCode, String comparisonCode,
            String codeType, String brandKey, String searchQuery, String searchMode, String suggestedQuery,
            boolean managed, long revision, long lookupRevision, String imageUrl, long duplicateCount,
            String latestStatus, LocalDateTime latestAt, RefreshResult latestResult,
            SupplierDtos.Listing selectedSupplier, ChangeDtos.Summary changes) {
        public ProcurementProductView(String id, String brand, String brandId, String productCode, String comparisonCode,
                String codeType, String brandKey, String searchQuery, String searchMode, String suggestedQuery,
                boolean managed, long revision, long lookupRevision, String imageUrl, long duplicateCount,
                String latestStatus, LocalDateTime latestAt, RefreshResult latestResult,
                SupplierDtos.Listing selectedSupplier) {
            this(id, brand, brandId, productCode, comparisonCode, codeType, brandKey, searchQuery, searchMode,
                    suggestedQuery, managed, revision, lookupRevision, imageUrl, duplicateCount, latestStatus, latestAt,
                    latestResult, selectedSupplier, null);
        }

        public ProcurementProductView withChanges(ChangeDtos.Summary value) {
            return new ProcurementProductView(id, brand, brandId, productCode, comparisonCode, codeType, brandKey, searchQuery,
                    searchMode, suggestedQuery, managed, revision, lookupRevision, imageUrl, duplicateCount,
                    latestStatus, latestAt, latestResult, selectedSupplier, value);
        }

        public ProcurementProductView withSelection(SupplierDtos.Listing selected) {
            return new ProcurementProductView(id, brand, brandId, productCode, comparisonCode, codeType, brandKey, searchQuery,
                    searchMode, suggestedQuery, managed, revision, lookupRevision, imageUrl, duplicateCount,
                    latestStatus, latestAt, latestResult, selected);
        }
    }

    public record ScheduleSettings(long revision, boolean scheduleEnabled, String scheduleTime) {
    }

    public record RefreshInput(String scope, List<String> ids) {
    }

    /** 최신화 작업 이력 조회 조건. 날짜는 한국 시간 YYYY-MM-DD, 비어 있으면 그쪽 경계 없음. */
    public record RefreshRunQuery(String from, String to, String status, boolean failed, int page, int size, Long run) {
    }

    /** 이력 페이지와 함께 필터와 무관한 진행 중 작업·선택 작업·재고 조회 차단 작업을 돌려준다. */
    public record RefreshRunPage(List<java.util.Map<String, Object>> items, int page, int totalPages,
            long totalElements,
            List<java.util.Map<String, Object>> active, java.util.Map<String, Object> selected,
            java.util.Map<String, Object> stockLookupBlock, java.util.Map<String, Object> searchGate) {
    }

    public enum NaverChannelType {
        WINDOW, SMARTSTORE, UNKNOWN, CONFLICT
    }

    public record NaverChannel(NaverChannelType type, String vertical) {
        public NaverChannel {
            type = type == null ? NaverChannelType.UNKNOWN : type;
        }
    }

    public record Offer(String naverProductId, String title, String mallName, String mallProductId, String url,
            Long price, Long deliveryFee, ProcurementMall mall, String imageUrl, NaverChannel naverChannel,
            SearchStoreEvidence searchStore) {
        public Offer(String nv, String title, String mallName, String id, String url, Long price, Long delivery,
                ProcurementMall mall, String image, NaverChannel channel) {
            this(nv, title, mallName, id, url, price, delivery, mall, image, channel, null);
        }

        public Offer(String nv, String title, String mallName, String id, String url, Long price, Long delivery,
                ProcurementMall mall, String image) {
            this(nv, title, mallName, id, url, price, delivery, mall, image, null);
        }

        public Offer withChannel(NaverChannel channel) {
            return new Offer(naverProductId, title, mallName, mallProductId, url, price, deliveryFee, mall, imageUrl,
                    channel, searchStore);
        }

        public Offer(String nv, String title, String mallName, String id, String url, Long price, Long delivery) {
            this(nv, title, mallName, id, url, price, delivery, null, null);
        }
    }

    public record SearchStoreEvidence(String channelId, String cachedChannelId, String channelName, String storeName,
            String windowType, String windowName) {
    }

    public record StockEvidence(String runId, String representativeKey, String channelId, LocalDateTime checkedAt,
            boolean directVerified) {
        public StockEvidence(long runId, String representativeKey, String channelId, LocalDateTime checkedAt,
                boolean directVerified) {
            this(Long.toString(runId), representativeKey, channelId, checkedAt, directVerified);
        }
    }

    // Read compatibility only: old page-limit metadata is never emitted or newly
    // stored.
    public static String normalSearchReason(String value) {
        return java.util.Set.of("COMPLETED", "END_OF_RESULTS", "PAGE_LIMIT")
                .contains(java.util.Objects.toString(value, "")) ? "COMPLETED" : value;
    }

    private static boolean legacyPageNotice(String part) {
        return java.util.Set.of("최대 3페이지 조회", "최대 5페이지 조회", "최대 3페이지 범위의 검색 결과입니다. 이후 페이지는 확인하지 않았습니다.",
                "최대 5페이지 범위의 검색 결과입니다. 이후 페이지는 확인하지 않았습니다.").contains(part.trim());
    }

    private static boolean legacyPageCompletion(String message) {
        return message != null && java.util.Arrays.stream(message.split(" · ")).anyMatch(ProductDtos::legacyPageNotice);
    }

    private static String searchNotice(String message, boolean completed) {
        if (message == null)
            return null;
        String result = java.util.Arrays.stream(message.split(" · "))
                .filter(p -> !legacyPageNotice(p) && !(completed && p.trim().equals("검색 일부 조회")))
                .collect(java.util.stream.Collectors.joining(" · "));
        return result.isBlank() ? null : result;
    }

    public record SearchResult(List<Offer> offers, boolean complete, String message, String completionReason,
            List<String> searchKeys) {
        public SearchResult {
            completionReason = normalSearchReason(completionReason);
            complete = complete || "COMPLETED".equals(completionReason) || legacyPageCompletion(message);
            message = searchNotice(message, complete);
        }

        public SearchResult(List<Offer> offers, boolean complete, String message, String reason) {
            this(offers, complete, message, reason, null);
        }

        public SearchResult(List<Offer> offers, boolean complete, String message) {
            this(offers, complete, message, complete ? "COMPLETED" : null);
        }
    }

    public record CandidateSearchResult(List<Offer> offers, List<Offer> needsReview, List<String> excludedMalls,
            boolean complete, String message, String completionReason) {
        public CandidateSearchResult {
            completionReason = normalSearchReason(completionReason);
            message = searchNotice(message, complete);
        }

        public CandidateSearchResult(List<Offer> offers, List<Offer> review, List<String> excluded, boolean complete,
                String message) {
            this(offers, review, excluded, complete, message, complete ? "COMPLETED" : null);
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceOption(String id, String label, Long stock, String state,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String stockScope,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) List<SimpleChoice> simpleChoices) {
        public SourceOption {
            simpleChoices = simpleChoices == null ? List.of() : List.copyOf(simpleChoices);
        }

        public SourceOption(String id, String label, Long stock, String state, String stockScope) {
            this(id, label, stock, state, stockScope, List.of());
        }

        public SourceOption(String id, String label, Long stock, String state) {
            this(id, label, stock, state, null);
        }
    }

    public record SimpleChoice(String id, String groupName, String name) {
    }

    public record StoreEvidence(String kind, String retailer, String name, String namespace, String externalId,
            java.util.Map<String, String> references) {
        public StoreEvidence {
            references = references == null ? java.util.Map.of() : java.util.Map.copyOf(references);
        }

        public StoreEvidence(String kind, String retailer, String name, String namespace, String externalId) {
            this(kind, retailer, name, namespace, externalId, java.util.Map.of());
        }
    }

    public record SourceDetails(String title, String modelCode, String brand, List<SourceOption> options,
            String storeName, StoreEvidence storeEvidence, boolean optionsComplete, NaverChannel naverChannel) {
        public SourceDetails(String title, String modelCode, String brand, List<SourceOption> options, String storeName,
                StoreEvidence evidence, boolean complete) {
            this(title, modelCode, brand, options, storeName, evidence, complete, null);
        }

        public SourceDetails(String title, String modelCode, String brand, List<SourceOption> options, String storeName,
                StoreEvidence storeEvidence) {
            this(title, modelCode, brand, options, storeName, storeEvidence, true);
        }

        public SourceDetails(String title, String modelCode, String brand, List<SourceOption> options,
                String storeName) {
            this(title, modelCode, brand, options, storeName, null);
        }

        public SourceDetails(String title, String modelCode, String brand, List<SourceOption> options) {
            this(title, modelCode, brand, options, "");
        }
    }

    public record BranchInfo(String name, String state, String source, String evidence, StoreEvidence store) {
        public BranchInfo(String name, String state, String source, String evidence) {
            this(name, state, source, evidence, null);
        }
    }

    public record RefreshStatus(String productId, String status, String runId, String runStatus, String message,
            String blockReason, int loginRetryCount, LocalDateTime nextRetryAt, LocalDateTime nextSearchAt,
            int searchRetryCount, String searchFailureStage, String searchFailureCode,
            java.util.Map<String, Object> searchGate, boolean active) {
    }

    public record CodeMatch(String state, String originalCode, String comparisonCode, String season, String color,
            String message) {
    }

    public record SupplierResult(Offer offer, CodeMatch match, String state, List<SourceOption> options, String message,
            String sourceTitle, String sourceModelCode, String sourceBrand, BranchInfo branch,
            StockEvidence stockEvidence) {
        public SupplierResult(Offer offer, CodeMatch match, String state, List<SourceOption> options, String message,
                String title, String model, String brand, BranchInfo branch) {
            this(offer, match, state, options, message, title, model, brand, branch, null);
        }

        public boolean skipped() {
            return "SKIPPED_SAME_STORE".equals(state);
        }

        // Historical REVIEW/EXCLUDED results stay unverified until a new lookup.
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean accepted() {
            return match != null && ("SEARCH_RESULT".equals(match.state()) || "MATCHED".equals(match.state()));
        }

        public SupplierResult(Offer offer, CodeMatch match, String state, List<SourceOption> options, String message) {
            this(offer, match, state, options, message, null, null, null, null);
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(value = "message", allowGetters = true)
    public record RecommendationDiagnostic(String runId, String productId, String listingKey, ProcurementMall mall,
            String supplierProductId,
            Long searchPrice, String url, String kind, String causeCode, Integer httpStatus, LocalDateTime createdAt) {
        public String message() {
            return switch (causeCode) {
                case "HTTP_RESTRICTED" -> "접속 제한 · HTTP " + httpStatus;
                case "SECURITY_CHECK" -> "접속 제한 · 보안 확인";
                case "TIMEOUT" -> "재고 조회 시간 초과";
                case "NETWORK" -> "재고 조회 통신 오류";
                case "HTTP" -> "재고 조회 실패 · HTTP " + httpStatus;
                case "RESPONSE_FORMAT" -> "상품 응답 형식 오류";
                case "PRODUCT_MISMATCH" -> "상품 응답 불일치";
                default -> "재고 조회 처리 오류";
            };
        }

        @com.fasterxml.jackson.annotation.JsonProperty("message")
        public String displayMessage() {
            return message();
        }
    }

    public record RefreshResult(String status, Long searchPrice, String searchMall, Long searchDeliveryFee,
            List<SupplierResult> suppliers, LocalDateTime checkedAt, String message, Boolean recommendationLimited,
            List<RecommendationDiagnostic> recommendationDiagnostics, String completionReason,
            Integer statusPolicyVersion, List<String> statusReasons) {
        public RefreshResult(String status, Long price, String mall, Long fee, List<SupplierResult> suppliers,
                LocalDateTime at, String message, Boolean limited, List<RecommendationDiagnostic> diagnostics,
                String reason) {
            this(status, price, mall, fee, suppliers, at, message, limited, diagnostics, reason, null, List.of());
        }

        // Pre-V16 stored results omit this field; do not rewrite historical JSON or
        // relax global validation.
        public RefreshResult {
            statusReasons = statusReasons == null ? List.of() : List.copyOf(statusReasons);
            completionReason = normalSearchReason(completionReason);
            message = searchNotice(message, "COMPLETED".equals(completionReason));
            recommendationLimited = Boolean.TRUE.equals(recommendationLimited);
            recommendationDiagnostics = recommendationDiagnostics == null ? List.of()
                    : List.copyOf(recommendationDiagnostics);
        }

        public RefreshResult(String status, Long price, String mall, Long fee, List<SupplierResult> suppliers,
                LocalDateTime at, String message, Boolean limited, List<RecommendationDiagnostic> diagnostics) {
            this(status, price, mall, fee, suppliers, at, message, limited, diagnostics, null);
        }

        public RefreshResult(String status, Long price, String mall, Long fee, List<SupplierResult> suppliers,
                LocalDateTime at, String message, Boolean limited) {
            this(status, price, mall, fee, suppliers, at, message, limited, List.of());
        }

        public RefreshResult(String status, Long price, String mall, Long fee, List<SupplierResult> suppliers,
                LocalDateTime at, String message) {
            this(status, price, mall, fee, suppliers, at, message, false);
        }
    }

}
