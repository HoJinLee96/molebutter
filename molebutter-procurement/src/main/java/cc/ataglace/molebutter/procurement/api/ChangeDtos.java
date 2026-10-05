package cc.ataglace.molebutter.procurement.api;
import cc.ataglace.molebutter.procurement.api.ProductDtos;

import java.time.LocalDateTime;
import java.util.*;
import cc.ataglace.molebutter.procurement.api.ProductDtos.ProcurementMall;

public final class ChangeDtos {
        private ChangeDtos() {
        }

        public record OptionValue(String id, String label, String scope, Long stock, String state,
                        LocalDateTime checkedAt, LocalDateTime stateAt) {
                public OptionValue(String id, String label, String scope, Long stock, String state, LocalDateTime at) {
                        this(id, label, scope, stock, state, at, at);
                }
        }

        public record Observation(String id, String key, ProcurementMall mall, String storeId, String identity, String name,
                        String url,
                        Long price, Long fee, LocalDateTime priceAt, List<OptionValue> options, boolean partial,
                        boolean verified,
                        boolean current, boolean recommendable, LocalDateTime observedAt, LocalDateTime feeAt,
                        String lookupState) {
                public Observation(String id, String key, ProcurementMall mall, String storeId, String identity, String name,
                                String url, Long price, Long fee, LocalDateTime priceAt, List<OptionValue> options,
                                boolean partial, boolean verified, boolean current, boolean recommendable,
                                LocalDateTime observedAt, LocalDateTime feeAt) {
                        this(id, key, mall, storeId, identity, name, url, price, fee, priceAt, options, partial,
                                        verified, current, recommendable, observedAt, feeAt, null);
                }

                public Observation withLookupState(String state) {
                        return new Observation(id, key, mall, storeId, identity, name, url, price, fee, priceAt,
                                        options, partial, verified, current, recommendable, observedAt, feeAt, state);
                }

                public Observation(String id, String key, ProcurementMall mall, String storeId, String identity, String name,
                                String url, Long price, Long fee, LocalDateTime priceAt, List<OptionValue> options,
                                boolean partial, boolean verified, boolean current, boolean recommendable,
                                LocalDateTime observedAt) {
                        this(id, key, mall, storeId, identity, name, url, price, fee, priceAt, options, partial,
                                        verified, current, recommendable, observedAt, priceAt);
                }
        }

        public record State(Map<String, Observation> baseline, Map<String, Observation> current,
                        Map<String, Observation> lastValid,
                        Set<String> baselineFound, Set<String> searchFound, boolean searchNormal,
                        String completionReason,
                        LocalDateTime reviewedAt, String reviewer, String reviewKind) {
                public State {
                        completionReason = ProductDtos.normalSearchReason(completionReason);
                }
        }

        public record Delta(String kind, String optionId, String optionLabel, String scope, Long before, Long after,
                        Long difference, String beforeState, String afterState, LocalDateTime baselineAt,
                        LocalDateTime checkedAt, String note) {
        }

        public record ListingChange(String supplierId, String name, String url, boolean changed, boolean fresh,
                        boolean missing,
                        String comparisonNote, LocalDateTime lastStockAt, List<Delta> deltas) {
        }

        public record GroupChange(String groupId, Long before, Long after, Long difference, boolean cheapestChanged,
                        int changedListings) {
        }

        public record Alternative(String supplierId, String name, Long price, Long fee, long saving) {
        }

        public record Summary(long version, LocalDateTime reviewedAt, String reviewer, String reviewKind,
                        boolean selectedChanged,
                        boolean anyChanged, String selectedId, List<ListingChange> listings, List<GroupChange> groups,
                        List<Alternative> alternatives,
                        String completionReason, boolean reviewable) {
                public Summary {
                        completionReason = ProductDtos.normalSearchReason(completionReason);
                }

                public static Summary empty() {
                        return new Summary(0, null, null, null, false, false, null, List.of(), List.of(), List.of(),
                                        null, false);
                }
        }

        public record ReviewInput(Long version) {
        }

        public record Counts(long selected, long all) {
        }

        public record HistoryItem(String id, String source, String supplierId, String originProductId, LocalDateTime at,
                        String name, String url, String actor, List<Delta> changes) {
        }
}
