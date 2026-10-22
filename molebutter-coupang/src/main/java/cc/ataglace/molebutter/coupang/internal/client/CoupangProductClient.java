package cc.ataglace.molebutter.coupang.internal.client;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.coupang.api.CoupangCatalog.*;
import cc.ataglace.molebutter.coupang.api.CoupangEditor.*;
import cc.ataglace.molebutter.coupang.api.CoupangFailure;
import cc.ataglace.molebutter.coupang.api.CoupangListingMissing;
import static cc.ataglace.molebutter.coupang.api.CoupangFailure.Kind.*;
@Component
final class CoupangProductClient {
    static final String PATH = "/v2/providers/seller_api/apis/api/v1/marketplace/seller-products";
    private static final java.time.Duration REQUEST_GAP = java.time.Duration.ofSeconds(2);
    private final String vendor, access, secret;
    private final ObjectMapper json;
    private final CoupangHttpTransport transport;
    private final Clock clock;
    // One client belongs to one credential/account identity. Only explicit category reads populate this cache.
    private final Map<String, CategoryRules> creationRules = new LinkedHashMap<>();
    private record OutboundObservation(cc.ataglace.molebutter.coupang.api.CoupangShippingPlaces.Place place, java.time.Instant checkedAt) {}
    private final Map<String, OutboundObservation> outboundObservations = new LinkedHashMap<>();
    private cc.ataglace.molebutter.coupang.api.CoupangShippingPlaces.Page rememberOutbound(cc.ataglace.molebutter.coupang.api.CoupangShippingPlaces.Page page) {
        synchronized (outboundObservations) {
            for (var place : page.items()) outboundObservations.put(place.code(), new OutboundObservation(place, now()));
            while (outboundObservations.size() > 1000) outboundObservations.remove(outboundObservations.keySet().iterator().next());
        }
        return page;
    }
    cc.ataglace.molebutter.coupang.api.CoupangShippingPlaces.Place creationOutboundPlace(String code) {
        synchronized (outboundObservations) {
            var saved = outboundObservations.get(code);
            if (saved == null || !saved.checkedAt().plusSeconds(600).isAfter(now()))
                throw new InputValidationFailure("출고지 확인이 만료되었거나 없습니다. 입력을 유지한 채 판매자 주소록에서 출고지를 다시 조회해 주세요.");
            return saved.place();
        }
    }
    CategoryRules cachedCategory(String code) {
        synchronized (creationRules) {
            creationRules.values().removeIf(r -> !java.time.Instant.parse(r.checkedAt()).plusSeconds(600).isAfter(now()));
            return creationRules.get(code);
        }
    }
    CategoryRules creationCategory(String code) {
        var rules = cachedCategory(code);
        if (rules == null) throw new InputValidationFailure("카테고리 규격 확인이 만료되었거나 없습니다. 입력을 유지한 채 카테고리 규격을 다시 조회해 주세요.");
        return rules;
    }
    private org.springframework.beans.factory.ObjectProvider<cc.ataglace.molebutter.coupang.api.CoupangObservations> observationProvider;
    private final ThreadLocal<Long> observationActor=new ThreadLocal<>();
    private final ThreadLocal<Long> readOrder=new ThreadLocal<>();
    @Autowired void observations(org.springframework.beans.factory.ObjectProvider<cc.ataglace.molebutter.coupang.api.CoupangObservations> provider) {observationProvider=provider;}
    private void observe(String kind,String product,String option,JsonNode raw,Product summary,ProductDetail detail,CurrentInventory current,String error) {
        if(observationProvider==null)return;
        var observer=observationProvider.getIfAvailable();if(observer==null)return;
        observer.record(new cc.ataglace.molebutter.coupang.api.CoupangObservations.Read(accountKey(),activeOperation.get()==null?observationActor.get():activeOperation.get().actor,
            Objects.requireNonNullElseGet(readOrder.get(),cc.ataglace.molebutter.common.api.BusinessIds::next),kind,product,option,
            raw==null?null:json.writeValueAsString(raw),summary,detail,current,error));
    }
    void observationActor(Long actor){observationActor.set(actor);}
    void deletedReceipt(String product,JsonNode receipt) {
        readOrder.set(cc.ataglace.molebutter.common.api.BusinessIds.next());
        observe("DELETE",product,null,receipt,null,null,null,null);
    }
    private ProductDetail observeProduct(byte[] bytes,String id) {
        JsonNode raw;
        try {
            var detail=parseDetail(bytes,id);raw=json.readTree(bytes).path("data");observe("DETAIL",id,null,raw,detail.product(),detail,null,null);return detail;
        } catch(CoupangListingMissing deleted) {
            raw=json.readTree(bytes).path("data");
            if(vendor.equals(raw.path("vendorId").asString()) && id.equals(raw.path("sellerProductId").asString()))
                observe("DETAIL",id,null,raw,new Product(id,text(raw,"sellerProductName"),text(raw,"brand"),text(raw,"statusName"),id(raw,"productId",false),text(raw,"createdAt")),null,null,null);
            throw deleted;
        } catch(CoupangFailure failure) {
            observe("DETAIL_ERROR",id,null,null,null,null,null,failure.kind().name());throw failure;
        }
    }

    // Reentrant for nested brand validation on the owning thread; other operations still fail BUSY.
    private final java.util.concurrent.locks.ReentrantLock sessionLock = new java.util.concurrent.locks.ReentrantLock();
    private long cooldownUntil;
    private final ThreadLocal<java.time.Duration> firstResponseGap = new ThreadLocal<>();
    private FinishedRequest lastResponse, lastFinished;
    private record FinishedRequest(java.time.Instant at, long nanos) {}
    private record RequestLog(String requestId, String operation, String method, int attempt,
            java.time.Instant startedAt, long startedNanos, long paceWaitMs,
            FinishedRequest previousResponse, FinishedRequest previousFinished) {}
    private static final java.time.format.DateTimeFormatter LOG_TIME = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .withZone(java.time.ZoneId.of("Asia/Seoul"));
    private final ThreadLocal<ReadOperation> activeOperation = new ThreadLocal<>();
    private final Map<String, ReadOperation> operations = new HashMap<>();
    private static final class ReadOperation {
        final Long actor;
        final long created = System.nanoTime();
        volatile boolean cancelled, active;
        ReadOperation(Long actor) {
            this.actor = actor;
        }
    }
    <T> T operation(Long actor, String requestId, java.util.function.Supplier<T> work) {
        if (requestId == null) {
            Long previousActor=observationActor.get();observationActor.set(actor);
            try{return work.get();}finally{if(previousActor==null)observationActor.remove();else observationActor.set(previousActor);}
        }
        checkRequestId(requestId);
        ReadOperation operation;
        synchronized (operations) {
            pruneOperations();
            operation = operations.get(requestId);
            if (operation == null) {
                if (operations.size() >= 256)
                    throw new CoupangFailure(BUSY);
                operation = new ReadOperation(actor);
                operations.put(requestId, operation);
            }
            if (!Objects.equals(operation.actor, actor))
                throw new InputValidationFailure("조회 요청을 확인해 주세요.");
            if (operation.active)
                throw new CoupangFailure(BUSY);
            operation.active = true;
        }
        activeOperation.set(operation);
        try {
            checkCancelled();
            return work.get();
        } finally {
            activeOperation.remove();
            synchronized (operations) {
                operation.active = false;
                operations.remove(requestId, operation);
            }
        }
    }
    void cancel(Long actor, String requestId) {
        checkRequestId(requestId);
        synchronized (operations) {
            pruneOperations();
            var operation = operations.get(requestId);
            if (operation == null) {
                if (operations.size() >= 256)
                    return;
                operation = new ReadOperation(actor);
                operations.put(requestId, operation);
            }
            if (Objects.equals(operation.actor, actor))
                operation.cancelled = true;
        }
    }
    private void pruneOperations() {
        operations.values().removeIf(
                o -> !o.active && System.nanoTime() - o.created > java.util.concurrent.TimeUnit.MINUTES.toNanos(10));
    }
    private static void checkRequestId(String value) {
        if (value == null
                || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new InputValidationFailure("조회 요청을 확인해 주세요.");
    }
    private boolean cancelled() {
        var operation = activeOperation.get();
        return Thread.currentThread().isInterrupted() || operation != null && operation.cancelled;
    }
    private void checkCancelled() {
        if (cancelled())
            throw new CoupangFailure(CANCELLED);
    }
    private void waitUntil(long deadline) {
        while (true) {
            checkCancelled();
            long left = deadline - System.nanoTime();
            if (left <= 0)
                return;
            try {
                java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(left, 100_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CoupangFailure(CANCELLED);
            }
        }
    }
    @Autowired
    public CoupangProductClient(@Value("${coupang.vendor-id:}") String vendor,
            @Value("${coupang.access-key:}") String access, @Value("${coupang.secret-key:}") String secret,
            ObjectMapper json) {
        this(vendor, access, secret, json, new CoupangHttpTransport(), Clock.systemUTC());
    }
    CoupangProductClient(String vendor, String access, String secret, ObjectMapper json, CoupangHttpTransport transport,
            Clock clock) {
        this.vendor = vendor.trim();
        this.access = access.trim();
        this.secret = secret.trim();
        this.json = json;
        this.transport = transport;
        this.clock = clock;
    }
    /**
     * Orders share the same account guard, pacing, fresh signatures and GET retry
     * policy.
     */
    byte[] orderRead(String path, String query) {
        return writeSession(() -> request(path, query));
    }
    byte[] orderWithdrawalRead(String payload) {
        String path = "/v2/providers/openapi/apis/api/v4/vendors/" + vendor + "/returnWithdrawList";
        return writeSession(() -> request("POST", path, "", payload.getBytes(StandardCharsets.UTF_8)));
    }
    boolean orderConfigured() {
        return !vendor.isBlank() && !access.isBlank() && !secret.isBlank();
    }
    String vendorId() {
        return vendor;
    }
    @Value("${coupang.vendor-user-id:}")
    private String vendorUserId = "";
    static final String REGISTRATION_USER_REQUIRED = "서버 설정에 coupang_vendor_user_id(쿠팡 Wing 로그인 ID)를 추가해 주세요. coupang_access_id(업체코드)와 다른 값입니다.";
    String registrationUserId() {
        return vendorUserId == null ? "" : vendorUserId.trim();
    }
    String accountKey() {
        return cc.ataglace.molebutter.coupang.internal.CoupangHashes.sha256(vendor);
    }
    java.time.Instant now() {
        return clock.instant();
    }
    <T> T writeSession(java.util.function.Supplier<T> work) {
        if (vendor.isBlank() || access.isBlank() || secret.isBlank())
            throw new CoupangFailure(CONFIGURATION);
        enter();
        try {
            return work.get();
        } finally {
            sessionLock.unlock();
        }
    }
    JsonNode rawProduct(String id) {
        if (id == null || !id.matches("[0-9]{1,30}"))
            throw new InputValidationFailure("잘못된 등록상품 ID입니다.");
        var bytes = request(PATH + "/" + id, "");
        observeProduct(bytes, id);
        return json.readTree(bytes).path("data").deepCopy();
    }
    /** A flow may extend the first dispatch's response gap without changing later calls/retries. */
    <T> T withFirstResponseGap(java.time.Duration gap, java.util.function.Supplier<T> work) {
        if (gap == null || gap.compareTo(REQUEST_GAP) < 0 || gap.compareTo(java.time.Duration.ofSeconds(30)) > 0)
            throw new IllegalArgumentException("Invalid response gap");
        var previous = firstResponseGap.get();
        firstResponseGap.set(previous == null || previous.compareTo(gap) < 0 ? gap : previous);
        try { return work.get(); }
        finally { if (previous == null) firstResponseGap.remove(); else firstResponseGap.set(previous); }
    }
    JsonNode rawInventory(String id) {
        if (id == null || !id.matches("[0-9]{1,30}"))
            throw new InputValidationFailure("잘못된 옵션 ID입니다.");
        var bytes = request("/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/" + id + "/inventories", "");
        var current=parseInventory(bytes);
        observe("INVENTORY",null,id,json.readTree(bytes).path("data"),null,null,current,null);
        return json.readTree(bytes).path("data").deepCopy();
    }
    /**
     * A selected edit can use one confirmed field even if another current field is
     * unavailable.
     */
    JsonNode rawInventory(String id, boolean requirePrice, boolean requireStock) {
        if (id == null || !id.matches("[0-9]{1,30}"))
            throw new InputValidationFailure("잘못된 옵션 ID입니다.");
        var bytes = request("/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/" + id + "/inventories", "");
        var selected=selectedInventory(bytes, requirePrice, requireStock);
        if(!selected.path("salePrice").isMissingNode() && !selected.path("amountInStock").isMissingNode())
            observe("INVENTORY",null,id,selected,null,null,parseInventory(bytes),null);
        return selected;
    }
    JsonNode selectedInventory(byte[] bytes, boolean requirePrice, boolean requireStock) {
        try {
            var root = json.readTree(bytes);
            if (root == null || !root.isObject() || !root.path("code").isString())
                throw new CoupangFailure(RESPONSE);
            if (!"SUCCESS".equals(root.path("code").asString()))
                throw new CoupangFailure(REJECTED);
            var data = root.path("data");
            if (!data.isObject() || !data.path("onSale").isBoolean())
                throw new CoupangFailure(RESPONSE);
            id(data, "sellerItemId", true);
            if (requirePrice || !data.path("salePrice").isMissingNode() && !data.path("salePrice").isNull())
                nonnegativeLong(data, "salePrice");
            if (requireStock || !data.path("amountInStock").isMissingNode() && !data.path("amountInStock").isNull())
                nonnegativeLong(data, "amountInStock");
            return data.deepCopy();
        } catch (CoupangFailure e) {
            throw e;
        } catch (RuntimeException malformed) {
            throw new CoupangFailure(RESPONSE);
        }
    }
    JsonNode rawSummary(String sku) {
        var root = json.readTree(request(PATH + "/external-vendor-sku-codes/" + encode(sku).replace("+", "%20"), ""));
        if (root == null || !root.isObject() || !"SUCCESS".equals(root.path("code").asString())
                || !root.path("data").isArray())
            throw new CoupangFailure(RESPONSE);
        for (var item : root.path("data"))
            if (!vendor.equals(requiredText(item, "vendorId")) || id(item, "sellerProductId", true) == null)
                throw new CoupangFailure(RESPONSE);
        return root.path("data");
    }
    /**
     * Only an explicit transient rejection can be repeated; all ambiguous write failures
     * remain ambiguous.
     */
    CoupangHttpTransport.Response write(String method, String path, String query, String payload, Runnable beforeRetry) {
        if (!Set.of("POST", "PUT", "DELETE").contains(method) || !path.startsWith(PATH)
                && !path.startsWith("/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/"))
            throw new InputValidationFailure("쿠팡 전송 경로를 확인해 주세요.");
        if(method.equals("DELETE") && !path.matches(java.util.regex.Pattern.quote(PATH)+"/[0-9]{1,30}"))
            throw new InputValidationFailure("쿠팡 삭제 경로를 확인해 주세요.");
        byte[] body = payload == null ? null : payload.getBytes(StandardCharsets.UTF_8);
        if (body != null && body.length > 5 * 1024 * 1024)
            throw new InputValidationFailure("쿠팡 전송 내용은 5MiB 이하로 줄여 주세요.");
        return exchange(method, path, query, body, false, beforeRetry);
    }
    CoupangHttpTransport.Response write(String method, String path, String query, String payload) {
        return write(method, path, query, payload, () -> {});
    }

    /** One shared budget (two total attempts), fresh signatures, cancellable delay and safe diagnostics. */
    private CoupangHttpTransport.Response exchange(String method, String path, String query, byte[] body,
            boolean read, Runnable beforeRetry) {
        String requestId = UUID.randomUUID().toString();
        for (int attempt = 1; attempt <= 2; attempt++) {
            long pacingStarted = System.nanoTime();
            paceNextRequest();
            long paceWaitMs = elapsedMs(System.nanoTime() - pacingStarted);
            checkCancelled();
            if (read) readOrder.set(cc.ataglace.molebutter.common.api.BusinessIds.next());
            String authorization = CoupangSignature.authorization(access, secret, clock.instant(), method, path, query);
            var trace = startRequest(requestId, method, path, attempt, paceWaitMs);
            CoupangHttpTransport.Response response = null;
            RuntimeException transportFailure = null;
            FinishedRequest finished;
            try {
                response = transport.send(method, path, query, authorization, body, this::cancelled);
            } catch (RuntimeException failure) {
                transportFailure = failure;
            } finally {
                // Every response (and transport failure) starts the shared account cooldown.
                // Parsing, session boundaries and retry guards must never bypass this gap.
                finished = new FinishedRequest(clock.instant(), System.nanoTime());
                synchronized (this) {
                    cooldownUntil = Math.max(cooldownUntil, finished.nanos() + REQUEST_GAP.toNanos());
                    lastFinished = finished;
                    if (response != null) lastResponse = finished;
                }
            }
            if (transportFailure != null) {
                var kind = transportFailure instanceof CoupangFailure failure ? failure.kind() : null;
                boolean retry = read && attempt == 1 && kind != null && Set.of(NETWORK, TIMEOUT).contains(kind);
                diagnostic(trace, finished, false, 0, kind == null ? "CLIENT_ERROR" : kind.name(), retry);
                if (!retry) throw transportFailure;
                continue;
            }
            String reason = CoupangRetryPolicy.reason(json, path, response, read);
            if (reason == null) {
                diagnostic(trace, finished, true, response.status(), responseCode(path, response), false);
                checkCancelled();
                return response;
            }
            var delay = response.status() == 429 || response.retryAfter() != null
                    ? retryDelay(response.retryAfter(), clock.instant())
                    : REQUEST_GAP;
            if (delay.compareTo(REQUEST_GAP) < 0) delay = REQUEST_GAP;
            boolean retry = attempt == 1 && delay.compareTo(java.time.Duration.ofSeconds(30)) <= 0;
            diagnostic(trace, finished, true, response.status(), reason, retry);
            checkCancelled();
            synchronized (this) {
                cooldownUntil = Math.max(cooldownUntil, System.nanoTime() + delay.toNanos());
            }
            if (!retry) return response;
            waitUntil(System.nanoTime() + delay.toNanos());
            if (!read) beforeRetry.run();
        }
        throw new IllegalStateException("Unreachable retry budget");
    }
    private String responseCode(String path, CoupangHttpTransport.Response response) {
        return switch (response.status()) {
            case 200 -> responseResult(path, response.body());
            case 401 -> "AUTHENTICATION";
            case 403 -> "PERMISSION";
            case 408 -> "TIMEOUT";
            case 429 -> "RATE_LIMIT";
            default -> response.status() >= 500 ? "UPSTREAM" : "HTTP_REJECTED";
        };
    }
    private String responseResult(String path, byte[] body) {
        try {
            var root = json.readTree(body);
            if (root == null || !root.isObject()) return "INVALID_RESPONSE";
            var result = "200".equals(root.path("code").asString()) ? root.path("data") : root;
            if ("200".equals(root.path("code").asString()) && Set.of("SUCCESS","FAILURE","INSUFFICIENT_INFORMATION").contains(result.path("autoCategorizationPredictionResultType").asString(""))) return "SUCCESS";
            // These documented read envelopes differ from the product SUCCESS/ERROR envelope.
            if (!result.has("code")) {
                if (path.contains("/shipping-place/outbound") && !root.has("code")
                        && root.path("content").isArray() && root.path("pagination").isObject()) return "SUCCESS";
                if ("200".equals(root.path("code").asString()) && path.contains("/returnShippingCenters")
                        && result.path("content").isArray() && result.path("pagination").isObject()) return "SUCCESS";
                if ("200".equals(root.path("code").asString()) && path.contains("/vendors/")
                        && !path.contains("/returnShippingCenters") && (result.isArray() || result.isObject())) return "SUCCESS";
            }
            return switch (result.path("code").asString("")) {
                case "SUCCESS" -> "SUCCESS";
                case "ERROR" -> "REJECTED";
                default -> "INVALID_RESPONSE";
            };
        } catch (RuntimeException ignored) { return "INVALID_RESPONSE"; }
    }
    private RequestLog startRequest(String id, String method, String path, int attempt, long paceWaitMs) {
        RequestLog trace;
        synchronized (this) {
            trace = new RequestLog(id, CoupangRetryPolicy.operation(method, path), method, attempt,
                    clock.instant(), System.nanoTime(), paceWaitMs, lastResponse, lastFinished);
        }
        // All values are generated here or fixed classifications. Never log provider/request values.
        LoggerFactory.getLogger(CoupangProductClient.class).info(
                "[COUPANG_API] event=START request={} execution={} step={} operation={} method={} attempt={} startedAt={} previousResponseAt={} sincePreviousResponseMs={} previousFinishedAt={} sincePreviousFinishMs={} paceWaitMs={}",
                trace.requestId(),
                Objects.toString(org.slf4j.MDC.get("coupangExecution"), "-"),
                Objects.toString(org.slf4j.MDC.get("coupangStep"), "-"),
                trace.operation(), method, attempt, logTime(trace.startedAt()),
                trace.previousResponse() == null ? "-" : logTime(trace.previousResponse().at()),
                gapMs(trace.startedNanos(), trace.previousResponse()),
                trace.previousFinished() == null ? "-" : logTime(trace.previousFinished().at()),
                gapMs(trace.startedNanos(), trace.previousFinished()), trace.paceWaitMs());
        return trace;
    }
    private static long elapsedMs(long nanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(Math.max(0, nanos));
    }
    private static Object gapMs(long start, FinishedRequest previous) {
        return previous == null ? "-" : elapsedMs(start - previous.nanos());
    }
    private static String logTime(java.time.Instant time) { return LOG_TIME.format(time); }
    private void diagnostic(RequestLog trace, FinishedRequest finished, boolean response, int http, String code, boolean retry) {
        var level = "SUCCESS".equals(code) ? org.slf4j.event.Level.INFO
                : retry || "CANCELLED".equals(code) ? org.slf4j.event.Level.WARN
                : http >= 500 || Set.of("NETWORK", "TIMEOUT", "RESPONSE", "INVALID_RESPONSE", "CLIENT_ERROR").contains(code)
                        ? org.slf4j.event.Level.ERROR : org.slf4j.event.Level.WARN;
        LoggerFactory.getLogger(CoupangProductClient.class).atLevel(level).log(
                "[COUPANG_API] event={} request={} execution={} step={} operation={} method={} attempt={} startedAt={} responseAt={} finishedAt={} durationMs={} http={} code={} decision={}",
                response ? "RESPONSE" : "FAILURE", trace.requestId(),
                Objects.toString(org.slf4j.MDC.get("coupangExecution"), "-"),
                Objects.toString(org.slf4j.MDC.get("coupangStep"), "-"),
                trace.operation(), trace.method(), trace.attempt(), logTime(trace.startedAt()),
                response ? logTime(finished.at()) : "-", logTime(finished.at()),
                elapsedMs(finished.nanos() - trace.startedNanos()), http, code, retry ? "RETRY" : "STOP");
    }
    public ProductPage products(int size, String token) {
        return products(size, token, ProductSearch.empty());
    }
    public ProductPage products(int size, String token, ProductSearch search) {
        return productCandidates(size, token, search, true).page();
    }
    private record CandidateResult(ProductPage page, cc.ataglace.molebutter.coupang.internal.RecentProductCandidates.Page candidates) {}
    cc.ataglace.molebutter.coupang.internal.RecentProductCandidates.Page candidates(String date,String token) {
        return productCandidates(100,token,new ProductSearch(null,null,null,date),false).candidates();
    }
    private CandidateResult productCandidates(int size,String token,ProductSearch search,boolean publish) {
        String filters = searchQuery(search);
        if (!Set.of(10, 50, 100).contains(size))
            throw new InputValidationFailure("페이지 크기는 10·50·100 중 선택해 주세요.");
        if (token == null)
            token = "";
        if (token.length() > 512 || token.codePoints().anyMatch(Character::isISOControl))
            throw new InputValidationFailure("잘못된 다음 페이지 토큰입니다.");
        if (vendor.isBlank() || access.isBlank() || secret.isBlank())
            throw new CoupangFailure(CONFIGURATION);
        enter();
        try {
            String query = "vendorId=" + encode(vendor) + "&maxPerPage=" + size
                    + (token.isEmpty() ? "" : "&nextToken=" + encode(token)) + filters;
            var body = request(PATH, query);
            ProductPage page = parse(body);
            var rows=json.readTree(body).path("data");
            var candidates=new ArrayList<cc.ataglace.molebutter.coupang.internal.RecentProductCandidates.Candidate>();
            String observedAt=clock.instant().toString();
            for(int i=0;i<page.items().size();i++) {
                var p=page.items().get(i);
                if(publish)observe("SUMMARY",p.sellerProductId(),null,rows.get(i),p,null,null,null);
                candidates.add(new cc.ataglace.molebutter.coupang.internal.RecentProductCandidates.Candidate(p,json.writeValueAsString(rows.get(i)),readOrder.get(),observedAt));
            }
            LoggerFactory.getLogger(CoupangProductClient.class).info(
                    "[COUPANG_PRODUCTS] http=200 code=SUCCESS count={} hasNext={}", page.items().size(),
                    page.hasNext());
            return new CandidateResult(page,new cc.ataglace.molebutter.coupang.internal.RecentProductCandidates.Page(candidates,page.nextToken(),page.hasNext()));
        } finally {
            sessionLock.unlock();
        }
    }
    private record LoadedProduct(ProductDetail detail, JsonNode source) {
    }
    public ProductDetail product(String productId) {
        return loadProduct(productId, false).detail();
    }
    public ProductDetail productForCollection(String productId) {
        return loadProduct(productId, true).detail();
    }
    private LoadedProduct loadProduct(String productId, boolean collection) {
        if (productId == null || !productId.matches("[0-9]{1,30}"))
            throw new InputValidationFailure("잘못된 등록상품 ID입니다.");
        if (vendor.isBlank() || access.isBlank() || secret.isBlank())
            throw new CoupangFailure(CONFIGURATION);
        enter();
        try {
            var bytes = request(PATH + "/" + productId, "");
            var detail = observeProduct(bytes, productId);
            var source = json.readTree(bytes).path("data");
            var items = new ArrayList<ProductOption>();
            var results = new HashMap<String, ProductOption>();
            String stoppedError = null;
            for (var item : detail.items()) {
                if (item.vendorItemId() == null) {
                    items.add(item.withCurrent(null, "옵션 ID가 없어 현재 값을 조회할 수 없습니다."));
                    continue;
                }
                if (results.containsKey(item.vendorItemId())) {
                    var previous = results.get(item.vendorItemId());
                    items.add(item.withCurrent(previous.current(), previous.currentError()));
                    continue;
                }
                if (stoppedError != null) {
                    items.add(item.withCurrent(null, stoppedError));
                    continue;
                }
                ProductOption updated;
                try {
                    var inventoryBytes=request("/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/"+item.vendorItemId()+"/inventories", "");
                    var current=parseInventory(inventoryBytes);
                    observe("INVENTORY",productId,item.vendorItemId(),json.readTree(inventoryBytes).path("data"),null,null,current,null);
                    updated = item.withCurrent(current, null);
                    LoggerFactory.getLogger(CoupangProductClient.class)
                            .info("[COUPANG_INVENTORY] http=200 code=SUCCESS");
                } catch (CoupangFailure failure) {
                    observe("INVENTORY_ERROR",productId,item.vendorItemId(),null,null,null,null,failure.kind().name());
                    if (Thread.currentThread().isInterrupted() || failure.kind() == CANCELLED)
                        throw failure;
                    if (collection && Set.of(AUTHENTICATION, PERMISSION, RATE_LIMIT, BUSY, CONFIGURATION).contains(failure.kind()))
                        throw failure;
                    if (Set.of(AUTHENTICATION, PERMISSION, RATE_LIMIT).contains(failure.kind()))
                        stoppedError = failure.getMessage();
                    updated = item.withCurrent(null, failure.getMessage());
                }
                results.put(item.vendorItemId(), updated);
                items.add(updated);
            }
            return new LoadedProduct(detail.withItems(items), source);
        } finally {
            sessionLock.unlock();
        }
    }
    public EditorDocument editor(String productId) {
        var loaded = loadProduct(productId, false);
        return editorDocument(loaded.detail(), loaded.source());
    }
    /**
     * Projects an already validated server response without making another external
     * request.
     */
    EditorDocument editorDocument(JsonNode source, Map<String, CurrentInventory> current, Map<String, String> errors) {
        var envelope = json.createObjectNode();
        envelope.put("code", "SUCCESS");
        envelope.set("data", source);
        var detail = parseDetail(json.writeValueAsBytes(envelope), id(source, "sellerProductId", true));
        var items = new ArrayList<ProductOption>();
        for (var item : detail.items())
            items.add(item.withCurrent(item.vendorItemId() == null ? null : current.get(item.vendorItemId()),
                    item.vendorItemId() == null ? "옵션 ID가 없어 현재 값을 조회할 수 없습니다." : errors.get(item.vendorItemId())));
        return editorDocument(detail.withItems(items), source);
    }
    private EditorDocument editorDocument(ProductDetail d, JsonNode source) {
        var p = d.product();
        var options = new ArrayList<EditOption>();
        for (int n = 0; n < d.items().size(); n++) {
            var i = d.items().get(n);
            var raw = source.path("items").get(n);
            var registration = new ArrayList<Field>(i.settings());
            registration.addAll(fields(raw, new String[]{"autoPricingInfo.minSalePrice", "autoPricingInfo.active"}));
            options.add(new EditOption(i.sellerProductItemId(), i.vendorItemId(), i.itemName(), i.current(),
                    i.currentError(), i.vendorItemId() != null, i.attributes(), i.images(), i.contents(), i.notices(),
                    List.copyOf(registration), i.certifications(), attributePermissions(raw)));
        }
        var documents = new ArrayList<Document>();
        for (var row : array(source, "requiredDocuments"))
            documents.add(new Document(text(row, "templateName"), text(row, "documentPath"),
                    text(row, "vendorDocumentPath")));
        return new EditorDocument(
                new Basic(p.sellerProductId(), p.productId(), p.sellerProductName(), d.displayProductName(),
                        d.generalProductName(), p.brand(), d.productGroup(), d.displayCategoryCode(), p.statusName()),
                new Limits(true, true, true), List.copyOf(options), d.delivery(), d.settings(), List.copyOf(documents));
    }
    static Boolean editable(JsonNode value) {
        if (value.isBoolean())
            return value.asBoolean();
        if (value.isString()) {
            if ("true".equalsIgnoreCase(value.asString().trim()))
                return true;
            if ("false".equalsIgnoreCase(value.asString().trim()))
                return false;
        }
        return null;
    }
    private List<AttributePermission> attributePermissions(JsonNode item) {
        var result = new ArrayList<AttributePermission>();
        for (var a : array(item, "attributes"))
            result.add(new AttributePermission(text(a, "attributeTypeName"), text(a, "exposed"),
                    editable(a.path("editable"))));
        return List.copyOf(result);
    }
    public cc.ataglace.molebutter.coupang.api.CoupangBrands.Page brands(String name, int page) {
        if (name == null || name.isBlank() || name.length() > 200 || page < 1 || page > 1_000_000)
            throw new InputValidationFailure("브랜드 검색어와 페이지를 확인해 주세요.");
        var payload = json.createObjectNode().put("brandName", name.trim()).put("countPerPage", 10).put("page", page);
        return writeSession(
                () -> parseBrands(request("POST", "/v2/providers/seller_api/apis/api/v1/marketplace/brands/search", "",
                        json.writeValueAsBytes(payload))));
    }
    cc.ataglace.molebutter.coupang.api.CoupangBrands.Page parseBrands(byte[] bytes) {
        try {
            var root = json.readTree(bytes);
            if (root == null || !root.isObject() || !"SUCCESS".equals(root.path("code").asString()))
                throw new CoupangFailure(REJECTED);
            var data = root.path("data");
            if (!data.isObject() || !data.path("items").isArray())
                throw new CoupangFailure(RESPONSE);
            long page = nonnegativeLong(data, "page"), size = nonnegativeLong(data, "countPerPage"),
                    total = nonnegativeLong(data, "totalCount");
            if (page < 1 || page > Integer.MAX_VALUE || size < 1 || size > 10 || data.path("items").size() > size)
                throw new CoupangFailure(RESPONSE);
            var items = new ArrayList<cc.ataglace.molebutter.coupang.api.CoupangBrands.Brand>();
            for (var b : data.path("items")) {
                if (!b.isObject() || !b.path("isUIDRequired").isBoolean() || !b.path("allowedUIDTypes").isArray())
                    throw new CoupangFailure(RESPONSE);
                var types = new ArrayList<String>();
                for (var t : b.path("allowedUIDTypes")) {
                    if (!t.isString())
                        throw new CoupangFailure(RESPONSE);
                    types.add(t.asString());
                }
                items.add(new cc.ataglace.molebutter.coupang.api.CoupangBrands.Brand(requiredText(b, "brandId"),
                        requiredText(b, "brandName"), b.path("isUIDRequired").asBoolean(), List.copyOf(types)));
            }
            return new cc.ataglace.molebutter.coupang.api.CoupangBrands.Page(List.copyOf(items), (int) page, total,
                    page * size < total);
        } catch (CoupangFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CoupangFailure(RESPONSE);
        }
    }
    public cc.ataglace.molebutter.coupang.api.CoupangShippingPlaces.Page outboundPlace(String code) {
        if (code == null || !code.matches("[0-9]{1,30}"))
            throw new InputValidationFailure("출고지 코드를 확인해 주세요.");
        return writeSession(() -> rawOutboundPlace(code));
    }
    cc.ataglace.molebutter.coupang.api.CoupangShippingPlaces.Page rawOutboundPlace(String code) {
        if (code == null || !code.matches("[0-9]{1,30}"))
            throw new InputValidationFailure("출고지 코드를 확인해 주세요.");
        return rememberOutbound(parseShippingPlaces(
                request("/v2/providers/marketplace_openapi/apis/api/v2/vendor/shipping-place/outbound",
                        "placeCodes=" + code),
                false));
    }
    public cc.ataglace.molebutter.coupang.api.CoupangShippingPlaces.Page shippingPlaces(boolean returns, int page) {
        if (page < 1 || page > 1_000_000)
            throw new InputValidationFailure("주소록 페이지를 확인해 주세요.");
        String path = returns
                ? "/v2/providers/openapi/apis/api/v5/vendors/" + encode(vendor) + "/returnShippingCenters"
                : "/v2/providers/marketplace_openapi/apis/api/v2/vendor/shipping-place/outbound";
        return writeSession(() -> {
            var result = parseShippingPlaces(request(path, "pageNum=" + page + "&pageSize=50"), returns);
            return returns ? result : rememberOutbound(result);
        });
    }
    cc.ataglace.molebutter.coupang.api.CoupangShippingPlaces.Page parseShippingPlaces(byte[] bytes, boolean returns) {
        try {
            var root = json.readTree(bytes);
            if (root == null || !root.isObject())
                throw new CoupangFailure(RESPONSE);
            if (returns && !"200".equals(root.path("code").asString()))
                throw new CoupangFailure(REJECTED);
            var data = returns ? root.path("data") : root;
            if (!data.path("content").isArray() || !data.path("pagination").isObject())
                throw new CoupangFailure(RESPONSE);
            var pagination = data.path("pagination");
            long page = nonnegativeLong(pagination, "currentPage"), pages = nonnegativeLong(pagination, "totalPages"),
                    total = nonnegativeLong(pagination, "totalElements");
            if (page < 1 || page > Integer.MAX_VALUE || data.path("content").size() > 50)
                throw new CoupangFailure(RESPONSE);
            var items = new ArrayList<cc.ataglace.molebutter.coupang.api.CoupangShippingPlaces.Place>();
            for (var place : data.path("content")) {
                if (!place.isObject() || !place.path("usable").isBoolean() || !place.path("placeAddresses").isArray()
                        || (returns && !vendor.equals(requiredText(place, "vendorId"))))
                    throw new CoupangFailure(RESPONSE);
                String code = id(place, returns ? "returnCenterCode" : "outboundShippingPlaceCode", true);
                if (code == null)
                    throw new CoupangFailure(RESPONSE);
                var addresses = new ArrayList<cc.ataglace.molebutter.coupang.api.CoupangShippingPlaces.Address>();
                for (var a : place.path("placeAddresses"))
                    addresses.add(new cc.ataglace.molebutter.coupang.api.CoupangShippingPlaces.Address(
                            requiredText(a, "addressType"), requiredText(a, "returnZipCode"),
                            requiredText(a, "returnAddress"), a.path("returnAddressDetail").asString(""),
                            a.path("companyContactNumber").asString("")));
                var remote = new ArrayList<cc.ataglace.molebutter.coupang.api.CoupangShippingPlaces.Remote>();
                for (var r : place.path("remoteInfos")) {
                    var fee = r.has("jejuFee") ? r.path("jejuFee") : r.path("jeju");
                    if (r.has("jejuFee") && r.has("jeju") && new java.math.BigDecimal(r.path("jejuFee").asString())
                            .compareTo(new java.math.BigDecimal(r.path("jeju").asString())) != 0)
                        throw new CoupangFailure(RESPONSE);
                    remote.add(new cc.ataglace.molebutter.coupang.api.CoupangShippingPlaces.Remote(
                            r.path("deliveryCode").asString(""), fee.asString(""), r.path("notJeju").asString(""),
                            r.path("usable").isBoolean() && r.path("usable").asBoolean()));
                }
                items.add(new cc.ataglace.molebutter.coupang.api.CoupangShippingPlaces.Place(code,
                        requiredText(place, "shippingPlaceName"), place.path("usable").asBoolean(),
                        List.copyOf(addresses), List.copyOf(remote)));
            }
            return new cc.ataglace.molebutter.coupang.api.CoupangShippingPlaces.Page(List.copyOf(items), (int) page,
                    total, page < pages);
        } catch (CoupangFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CoupangFailure(RESPONSE);
        }
    }
    public List<CategoryChoice> categories() {
        if (vendor.isBlank() || access.isBlank() || secret.isBlank())
            throw new CoupangFailure(CONFIGURATION);
        enter();
        try {
            return parseCategories(
                    request("/v2/providers/seller_api/apis/api/v1/marketplace/meta/display-categories", ""));
        } finally {
            sessionLock.unlock();
        }
    }
    List<CategoryChoice> parseCategories(byte[] bytes) {
        try {
            var root = json.readTree(bytes);
            if (root == null || !root.isObject() || !root.path("code").isString())
                throw new CoupangFailure(RESPONSE);
            if (!"SUCCESS".equals(root.path("code").asString()))
                throw new CoupangFailure(REJECTED);
            var result = new ArrayList<CategoryChoice>();
            categoryLeaves(root.path("data"), "", result, 0);
            return List.copyOf(result);
        } catch (CoupangFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CoupangFailure(RESPONSE);
        }
    }
    private void categoryLeaves(JsonNode row, String parent, List<CategoryChoice> result, int depth) {
        if (depth > 32 || result.size() > 50000 || !row.isObject() || !row.path("child").isArray())
            throw new CoupangFailure(RESPONSE);
        var code = id(row, "displayItemCategoryCode", true);
        var name = requiredText(row, "name");
        var status = requiredText(row, "status");
        if (!Set.of("ACTIVE", "READY", "DISABLED").contains(status))
            throw new CoupangFailure(RESPONSE);
        if (!"ACTIVE".equals(status))
            return;
        String path = depth == 0 && "0".equals(code) ? "" : parent.isEmpty() ? name : parent + " > " + name;
        if (row.path("child").isEmpty()) {
            if (!"0".equals(code))
                result.add(new CategoryChoice(code, path));
        } else
            for (var child : row.path("child"))
                categoryLeaves(child, path, result, depth + 1);
    }
    public Prediction predict(String name,String brand,String sku) {
        if(name==null||name.isBlank()||name.length()>1000)throw new InputValidationFailure("상품명을 확인해 주세요.");
        return writeSession(()->{
            var payload=json.createObjectNode().put("productName",name).put("brand",brand).put("sellerSkuCode",sku);
            var root=json.readTree(request("POST","/v2/providers/openapi/apis/api/v1/categorization/predict","",json.writeValueAsBytes(payload)));
            if(root==null||!"200".equals(root.path("code").asString())||!root.path("data").isObject())throw new CoupangFailure(RESPONSE);
            var data=root.path("data");String status=data.path("autoCategorizationPredictionResultType").asString("");
            if(!Set.of("SUCCESS","FAILURE","INSUFFICIENT_INFORMATION").contains(status))throw new CoupangFailure(RESPONSE);
            String code=data.path("predictedCategoryId").asString("");
            if(status.equals("SUCCESS")&&!code.matches("[0-9]{1,15}"))throw new CoupangFailure(RESPONSE);
            return new Prediction(status,status.equals("SUCCESS")?code:null,data.path("predictedCategoryName").asString(""));
        });
    }
    public boolean validCategory(String code) {
        if(code==null||!code.matches("[0-9]{1,15}"))throw new InputValidationFailure("카테고리 코드를 확인해 주세요.");
        return writeSession(()->{var root=json.readTree(request("/v2/providers/seller_api/apis/api/v1/marketplace/meta/display-categories/"+code+"/status",""));
            if(root==null||!"SUCCESS".equals(root.path("code").asString())||!root.path("data").isBoolean())throw new CoupangFailure(RESPONSE);
            return root.path("data").asBoolean();});
    }
    public CategoryRules category(String code) {
        if (code == null || !code.matches("[0-9]{1,15}"))
            throw new InputValidationFailure("유효한 카테고리 코드를 입력해 주세요.");
        if (vendor.isBlank() || access.isBlank() || secret.isBlank())
            throw new CoupangFailure(CONFIGURATION);
        enter();
        try {
            var rules = rawCategory(code);
            var checked = new CategoryRules(rules.categoryCode(), rules.allowSingleItem(), rules.attributes(),
                    rules.notices(), rules.certifications(), rules.documents(), rules.allowedOfferConditions(), now().toString());
            synchronized (creationRules) {
                creationRules.put(code, checked);
                while (creationRules.size() > 256) creationRules.remove(creationRules.keySet().iterator().next());
            }
            return checked;
        } finally {
            sessionLock.unlock();
        }
    }
    CategoryRules rawCategory(String code) {
        return parseCategory(request(
                "/v2/providers/seller_api/apis/api/v1/marketplace/meta/category-related-metas/display-category-codes/"
                        + code,
                ""), code);
    }
    CategoryRules parseCategory(byte[] bytes, String code) {
        String validation = "ROOT";
        try {
            var root = json.readTree(bytes);
            if (root == null || !root.isObject() || !root.path("code").isString())
                throw new CoupangFailure(RESPONSE);
            if ("ERROR".equals(root.path("code").asString()))
                throw new CoupangFailure(REJECTED);
            if (!"SUCCESS".equals(root.path("code").asString()))
                throw new CoupangFailure(RESPONSE);
            validation = "DATA";
            var d = root.path("data");
            if (!d.isObject() || !d.path("attributes").isArray() || !d.path("noticeCategories").isArray()
                    || !d.path("isAllowSingleItem").isBoolean())
                throw new CoupangFailure(RESPONSE);
            validation = "ATTRIBUTES";
            var attrs = new ArrayList<AttributeRule>();
            for (var a : array(d, "attributes"))
                attrs.add(new AttributeRule(requiredText(a, "attributeTypeName"), requiredText(a, "exposed"),
                        requiredText(a, "required"), text(a, "groupNumber"), requiredText(a, "dataType"),
                        text(a, "basicUnit"), strings(a, "usableUnits")));
            validation = "NOTICE_CATEGORIES";
            var notices = new ArrayList<NoticeCategory>();
            for (var c : array(d, "noticeCategories")) {
                if (!c.path("noticeCategoryDetailNames").isArray()) throw new CoupangFailure(RESPONSE);
                var names = new ArrayList<NoticeRule>();
                for (var f : array(c, "noticeCategoryDetailNames"))
                    names.add(new NoticeRule(requiredText(f, "noticeCategoryDetailName"), requiredText(f, "required")));
                notices.add(new NoticeCategory(requiredText(c, "noticeCategoryName"), List.copyOf(names)));
            }
            validation = "CERTIFICATIONS";
            categoryArray(d, "certifications");
            var certs = new ArrayList<CertificateRule>();
            for (var c : array(d, "certifications"))
                certs.add(new CertificateRule(text(c, "certificationType"), requiredText(c, "name"),
                        requiredText(c, "required"), text(c, "dataType")));
            validation = "DOCUMENTS";
            categoryArray(d, "requiredDocumentNames");
            var docs = new ArrayList<DocumentRule>();
            for (var f : array(d, "requiredDocumentNames"))
                docs.add(new DocumentRule(requiredText(f, "templateName"), requiredText(f, "required")));
            validation = "OFFER_CONDITIONS";
            categoryArray(d, "allowedOfferConditions");
            return new CategoryRules(code, d.path("isAllowSingleItem").asBoolean(), List.copyOf(attrs),
                    List.copyOf(notices), List.copyOf(certs), List.copyOf(docs), strings(d, "allowedOfferConditions"));
        } catch (CoupangFailure e) {
            if (e.kind() == RESPONSE) categoryDiagnostic(validation);
            throw e;
        } catch (RuntimeException e) {
            categoryDiagnostic(validation);
            throw new CoupangFailure(RESPONSE);
        }
    }
    private static void categoryArray(JsonNode data, String field) {
        if (data.has(field) && !data.path(field).isArray()) throw new CoupangFailure(RESPONSE);
    }
    private void categoryDiagnostic(String validation) {
        // Only fixed stages are logged; no provider field values or identifiers.
        LoggerFactory.getLogger(CoupangProductClient.class).warn(
                "[COUPANG_CATALOG] execution={} step={} operation=CATEGORY_RULES code=INVALID_RESPONSE validation={}",
                Objects.toString(org.slf4j.MDC.get("coupangExecution"), "-"),
                Objects.toString(org.slf4j.MDC.get("coupangStep"), "-"), validation);
    }
    private static List<String> strings(JsonNode row, String key) {
        var a = row.path(key);
        if (a.isMissingNode() || a.isNull())
            return List.of();
        if (!a.isArray())
            throw new CoupangFailure(RESPONSE);
        var result = new ArrayList<String>();
        for (var v : a) {
            if (!v.isString())
                throw new CoupangFailure(RESPONSE);
            result.add(v.asString());
        }
        return List.copyOf(result);
    }
    private void paceNextRequest() {
        var gap = firstResponseGap.get();
        firstResponseGap.remove();
        long deadline;
        synchronized (this) {
            deadline = Math.max(System.nanoTime(), cooldownUntil);
            if (gap != null && lastFinished != null)
                deadline = Math.max(deadline, lastFinished.nanos() + gap.toNanos());
        }
        if (deadline - System.nanoTime() > java.util.concurrent.TimeUnit.SECONDS.toNanos(30))
            throw new CoupangFailure(RATE_LIMIT);
        waitUntil(deadline);
    }
    CurrentInventory parseInventory(byte[] body) {
        try {
            var root = json.readTree(body);
            if (root == null || !root.isObject() || !root.path("code").isString())
                throw new CoupangFailure(RESPONSE);
            if (!"SUCCESS".equals(root.path("code").asString()))
                throw new CoupangFailure(REJECTED);
            var data = root.path("data");
            if (!data.isObject() || !data.path("onSale").isBoolean())
                throw new CoupangFailure(RESPONSE);
            return new CurrentInventory(id(data, "sellerItemId", true), nonnegativeLong(data, "salePrice"),
                    nonnegativeLong(data, "amountInStock"), data.path("onSale").asBoolean());
        } catch (CoupangFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CoupangFailure(RESPONSE);
        }
    }
    private static Long nonnegativeLong(JsonNode row, String key) {
        var value = row.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0)
            throw new CoupangFailure(RESPONSE);
        return value.asLong();
    }
    private void readFailure(String path,String error) {
        if(path.matches(java.util.regex.Pattern.quote(PATH)+"/[0-9]{1,30}"))
            observe("DETAIL_ERROR",path.substring(PATH.length()+1),null,null,null,null,null,error);
        if(path.matches("/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/[0-9]{1,30}/inventories"))
            observe("INVENTORY_ERROR",null,path.substring(path.indexOf("/vendor-items/")+14,path.length()-12),null,null,null,null,error);
    }
    private byte[] request(String path, String query) {
        return request("GET", path, query, null);
    }
    private byte[] request(String method, String path, String query, byte[] payload) {
        CoupangHttpTransport.Response response;
        try {
            response = exchange(method, path, query, payload, true, () -> {});
        } catch (CoupangFailure failure) {
            readFailure(path, failure.kind().name());
            throw failure;
        }
        int status = response.status();
        if (status == 200) return response.body();
            boolean detailRequest=path.matches(java.util.regex.Pattern.quote(PATH)+"/[0-9]{1,30}");
            boolean inventoryRequest=path.matches("/v2/providers/seller_api/apis/api/v1/marketplace/vendor-items/[0-9]{1,30}/inventories");
            String productId=detailRequest?path.substring(PATH.length()+1):null;
            cc.ataglace.molebutter.coupang.api.CoupangFailure.Kind productReason=null;
            if(detailRequest && status==400)try {
                String message=json.readTree(response.body()).path("message").asString("");
                if(message.replaceAll("\\s+","").equals("상품("+productId+")의데이터가없습니다."))productReason=PRODUCT_ABSENT;
                else if(message.startsWith("상품 정보가 등록 또는 수정되고 있습니다."))productReason=PRODUCT_PROCESSING;
            }catch(RuntimeException ignored){}
            String error=productReason!=null?productReason.name():status==401?"AUTHENTICATION":status==403?"PERMISSION":status==429?"RATE_LIMIT":status>=500?"UPSTREAM":"REJECTED";
            if(detailRequest)observe("DETAIL_ERROR",productId,null,null,null,null,null,error);
            if(inventoryRequest)observe("INVENTORY_ERROR",null,path.substring(path.indexOf("/vendor-items/")+14,path.length()-12),null,null,null,null,error);
            if(productReason!=null)throw new CoupangFailure(productReason);
            boolean orderRequest = path.contains("/vendors/")
                    && (path.endsWith("/ordersheets") || path.contains("/returnRequests")
                            || path.contains("/exchangeRequests") || path.contains("/returnWithdraw"));
            var reason = orderRequest && status == 400 ? orderRejection(response.body()) : null;
            LoggerFactory.getLogger(CoupangProductClient.class).warn(
                    "[COUPANG_READ] operation={} http={} code={}", orderRequest ? "ORDER" : "PRODUCT",
                    status, reason == null ? "UNAVAILABLE" : reason.name());
            if (reason != null)
                throw new CoupangFailure(reason);
        throw new CoupangFailure(switch (status) {
            case 401 -> AUTHENTICATION;
            case 403 -> PERMISSION;
            case 429 -> RATE_LIMIT;
            default -> status >= 500 ? UPSTREAM : orderRequest ? ORDER_REJECTED : REJECTED;
        });
    }
    // Parse only a documented fixed message. Never retain or expose the external
    // body.
    private static final ObjectMapper REJECTION_JSON = new ObjectMapper();
    static CoupangFailure.Kind orderRejection(byte[] body) {
        try {
            var root = REJECTION_JSON.readTree(body);
            var message = root.path("message");
            if (message.isString()) {
                return switch (message.asString().trim()) {
                    case "해당 주문이 취소 또는 반품 되었습니다." -> ORDER_UNAVAILABLE;
                    case "유효하지 않은 주문번호 입니다." -> ORDER_INVALID;
                    case "다른 판매자의 주문을 조회할 수 없습니다." -> ORDER_ACCOUNT_MISMATCH;
                    default -> ORDER_REJECTED;
                };
            }
        } catch (RuntimeException ignored) {
        }
        return ORDER_REJECTED;
    }
    static java.time.Duration retryDelay(String header, java.time.Instant now) {
        if (header != null)
            try {
                String value = header.trim();
                long seconds;
                if (value.matches("[0-9]+"))
                    seconds = Long.parseLong(value);
                else
                    seconds = Math.max(0,
                            java.time.Duration.between(now, java.time.ZonedDateTime
                                    .parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                                    .getSeconds());
                // A long server cooldown is respected by failing promptly rather than waiting
                // in the UI.
                return java.time.Duration.ofSeconds(Math.min(seconds, 86400));
            } catch (RuntimeException ignored) {
            }
        return REQUEST_GAP;
    }
    ProductDetail parseDetail(byte[] body, String requestedId) {
        try {
            var root = json.readTree(body);
            if (root == null || !root.isObject() || !root.path("code").isString())
                throw new CoupangFailure(RESPONSE);
            if (!"SUCCESS".equals(root.path("code").asString()))
                throw new CoupangFailure(REJECTED);
            var row = root.path("data");
            if (!row.isObject() || !vendor.equals(requiredText(row, "vendorId"))
                    || !requestedId.equals(id(row, "sellerProductId", true)))
                throw new CoupangFailure(RESPONSE);
            if (Set.of("상품삭제", "DELETED").contains(Objects.toString(text(row, "statusName"), "")))
                throw new CoupangListingMissing("COUPANG_DELETED");
            if (!row.path("items").isArray())
                throw new CoupangFailure(RESPONSE);
            var items = new ArrayList<ProductOption>();
            var seenItems=new HashSet<String>();
            for (var item : row.path("items")) {
                if(!seenItems.add(id(item,"sellerProductItemId",true)))throw new CoupangFailure(RESPONSE);
                if (!item.isObject())
                    throw new CoupangFailure(RESPONSE);
                items.add(parseOption(item));
            }
            var product = new Product(id(row, "sellerProductId", true), requiredText(row, "sellerProductName"),
                    text(row, "brand"), text(row, "statusName"), id(row, "productId", false), text(row, "createdAt"));
            return new ProductDetail(product, text(row, "displayProductName"), text(row, "generalProductName"),
                    text(row, "productGroup"), id(row, "displayCategoryCode", false), id(row, "categoryId", false),
                    text(row, "saleStartedAt"), text(row, "saleEndedAt"), items, fields(row, DELIVERY_FIELDS),
                    fields(row, PRODUCT_FIELDS));
        } catch (CoupangListingMissing e) {
            throw e;
        } catch (CoupangFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CoupangFailure(RESPONSE);
        }
    }
    private static final String[] DELIVERY_FIELDS = {"deliveryMethod", "deliveryCompanyCode", "deliveryChargeType",
            "deliveryCharge", "freeShipOverAmount", "deliveryChargeOnReturn", "remoteAreaDeliverable",
            "unionDeliveryType", "outboundShippingPlaceCode", "returnCenterCode", "returnChargeName",
            "companyContactNumber", "returnZipCode", "returnAddress", "returnAddressDetail", "returnCharge"};
    private static final String[] PRODUCT_FIELDS = {"saleStartedAt", "saleEndedAt", "manufacture", "brandId",
            "productGroup", "vendorUserId", "requested", "extraInfoMessage", "bundleInfo.bundleType"};
    private static final String[] OPTION_FIELDS = {"externalVendorSku", "originalPrice", "salePrice", "maximumBuyCount",
            "maximumBuyForPerson", "maximumBuyForPersonPeriod", "outboundShippingTimeDay", "unitCount", "adultOnly",
            "taxType", "parallelImported", "overseasPurchased", "pccNeeded", "bestPriceGuaranteed3P", "barcode",
            "emptyBarcode", "emptyBarcodeReason", "modelNo", "autoPricingInfoView.minSalePrice",
            "autoPricingInfoView.active", "offerCondition", "offerDescription", "searchTags"};
    // Only requested scalar fields and option search tags cross the module
    // contract.
    private static List<Field> fields(JsonNode row, String[] keys) {
        var result = new ArrayList<Field>();
        for (String key : keys) {
            var v = row;
            for (String segment : key.split("\\."))
                v = v.path(segment);
            if ("searchTags".equals(key)) {
                if (v.isArray()) {
                    var values = new ArrayList<String>();
                    for (var tag : v)
                        if (tag.isString())
                            values.add(tag.asString());
                    if (!values.isEmpty())
                        result.add(new Field(key, String.join(", ", values)));
                }
            } else if (v.isString() || v.isNumber() || v.isBoolean())
                result.add(new Field(key, v.isString() ? v.asString() : v.toString()));
        }
        return List.copyOf(result);
    }
    private static List<JsonNode> array(JsonNode row, String key) {
        var v = row.path(key);
        if (v.isMissingNode() || v.isNull())
            return List.of();
        if (!v.isArray())
            throw new CoupangFailure(RESPONSE);
        var values = new ArrayList<JsonNode>();
        for (var item : v) {
            if (!item.isObject())
                throw new CoupangFailure(RESPONSE);
            values.add(item);
        }
        return values;
    }
    private static List<Image> images(JsonNode row, String key) {
        var result = new ArrayList<Image>();
        for (var image : array(row, key)) {
            var order = image.path("imageOrder");
            Integer n = order.isIntegralNumber() && order.canConvertToInt() ? order.asInt() : null;
            result.add(new Image(n, text(image, "imageType"),
                    imageUrl(text(image, "cdnPath"), text(image, "vendorPath"))));
        }
        result.sort(Comparator.comparing((Image image) -> !"REPRESENTATION".equals(image.type()))
                .thenComparing(Image::order, Comparator.nullsLast(Integer::compareTo)));
        return List.copyOf(result);
    }
    static String imageUrl(String cdn, String vendor) {
        if (cdn != null && !cdn.isBlank()) {
            if (cdn.startsWith("https://")) {
                String valid = https(cdn);
                if (valid != null && java.net.URI.create(valid).getHost().endsWith(".coupangcdn.com"))
                    return valid;
            } else if (cdn.matches("[a-zA-Z0-9_/-]+\\.[a-zA-Z0-9]+") && !cdn.contains(".."))
                return "https://img1a.coupangcdn.com/image/" + cdn.replaceFirst("^/+", "");
        }
        return https(vendor);
    }
    private static String https(String value) {
        if (value == null)
            return null;
        try {
            var uri = java.net.URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    ? uri.toASCIIString()
                    : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
    private static ProductOption parseOption(JsonNode item) {
        var attrs = new ArrayList<Attribute>();
        for (var a : array(item, "attributes"))
            attrs.add(new Attribute(text(a, "attributeTypeName"), text(a, "attributeValueName"), text(a, "exposed")));
        var contents = new ArrayList<Content>();
        for (var c : array(item, "contents"))
            for (var d : array(c, "contentDetails"))
                contents.add(new Content(text(c, "contentsType"), text(d, "detailType"), text(d, "content")));
        var notices = new ArrayList<Notice>();
        for (var n : array(item, "notices"))
            notices.add(
                    new Notice(text(n, "noticeCategoryName"), text(n, "noticeCategoryDetailName"), text(n, "content")));
        var certs = new ArrayList<Certification>();
        for (var c : array(item, "certifications"))
            certs.add(new Certification(text(c, "certificationType"), text(c, "certificationCode"),
                    images(c, "certificationAttachments")));
        return new ProductOption(id(item, "sellerProductItemId", true), id(item, "vendorItemId", false),
                text(item, "itemName"), null, null, List.copyOf(attrs), images(item, "images"), List.copyOf(contents),
                List.copyOf(notices), fields(item, OPTION_FIELDS), List.copyOf(certs));
    }
    static String searchQuery(ProductSearch search) {
        if (search == null)
            search = ProductSearch.empty();
        var values = new LinkedHashMap<String, String>();
        values.put("sellerProductId", clean(search.sellerProductId()));
        values.put("sellerProductName", clean(search.sellerProductName()));
        values.put("status", clean(search.status()));
        values.put("createdAt", clean(search.createdAt()));
        String id = values.get("sellerProductId"), name = values.get("sellerProductName"),
                status = values.get("status"), date = values.get("createdAt");
        if (!id.isEmpty() && !id.matches("[0-9]{1,30}"))
            throw new InputValidationFailure("잘못된 등록상품 ID입니다.");
        if (name.codePointCount(0, name.length()) > 20)
            throw new InputValidationFailure("등록상품명은 20자 이하로 입력해 주세요.");
        if (!status.isEmpty()
                && !Set.of("IN_REVIEW", "SAVED", "APPROVING", "APPROVED", "PARTIAL_APPROVED", "DENIED", "DELETED")
                        .contains(status))
            throw new InputValidationFailure("잘못된 등록상태입니다.");
        if (!date.isEmpty())
            try {
                if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
                    throw new IllegalArgumentException();
                java.time.LocalDate.parse(date);
            } catch (RuntimeException e) {
                throw new InputValidationFailure("등록일을 yyyy-MM-dd 형식의 유효한 날짜로 입력해 주세요.");
            }
        var query = new StringBuilder();
        values.forEach((key, value) -> {
            if (!value.isEmpty())
                query.append('&').append(key).append('=').append(encode(value));
        });
        return query.toString();
    }
    private static String clean(String value) {
        if (value == null)
            return "";
        value = value.trim();
        if (value.length() > 512 || value.codePoints().anyMatch(Character::isISOControl))
            throw new InputValidationFailure("검색 조건을 확인해 주세요.");
        return value;
    }
    private void enter() {
        checkCancelled();
        if (!sessionLock.tryLock())
            throw new CoupangFailure(BUSY);
    }
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
    ProductPage parse(byte[] body) {
        try {
            var root = json.readTree(body);
            if (root == null || !root.isObject() || !root.path("code").isString())
                throw new CoupangFailure(RESPONSE);
            if (!"SUCCESS".equals(root.path("code").asString()))
                throw new CoupangFailure(REJECTED);
            if (!root.path("data").isArray() || !root.path("nextToken").isString())
                throw new CoupangFailure(RESPONSE);
            var products = new ArrayList<Product>();
            for (var row : root.path("data")) {
                if (!row.isObject() || !vendor.equals(requiredText(row, "vendorId")))
                    throw new CoupangFailure(RESPONSE);
                products.add(new Product(id(row, "sellerProductId", true), requiredText(row, "sellerProductName"),
                        text(row, "brand"), text(row, "statusName"), id(row, "productId", false),
                        text(row, "createdAt")));
            }
            String token = root.path("nextToken").asString();
            if (token.length() > 512 || token.codePoints().anyMatch(Character::isISOControl))
                throw new CoupangFailure(RESPONSE);
            return new ProductPage(products, token, !token.isEmpty());
        } catch (CoupangFailure e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CoupangFailure(RESPONSE);
        }
    }
    private static String requiredText(JsonNode row, String key) {
        String value = text(row, key);
        if (value == null || value.isBlank())
            throw new CoupangFailure(RESPONSE);
        return value;
    }
    private static String text(JsonNode row, String key) {
        var v = row.path(key);
        if (v.isMissingNode() || v.isNull())
            return null;
        if (!v.isString())
            throw new CoupangFailure(RESPONSE);
        return v.asString();
    }
    private static String id(JsonNode row, String key, boolean required) {
        var v = row.path(key);
        if (!required && (v.isMissingNode() || v.isNull()))
            return null;
        String value = v.isIntegralNumber() ? v.asString() : v.isString() ? v.asString() : "";
        if (!value.matches("[0-9]+"))
            throw new CoupangFailure(RESPONSE);
        return value;
    }
}
