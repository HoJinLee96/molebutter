package cc.ataglace.molebutter.infra.product;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;
import static cc.ataglace.molebutter.infra.product.NaverSearchPayload.*;
import cc.ataglace.molebutter.infra.product.NaverPriceSearch.SearchBlocked;

/** 매입처별 응답에서 명시적인 옵션 ID/수량/구매 가능 여부만 추출한다. 합계·첫 옵션·누락값 0 대체는 금지한다. */
@Component
@lombok.extern.slf4j.Slf4j
public class MallOptionGateway implements SupplierProductGateway {
    private final ObjectMapper json;
    private final HttpClient http;
    private final Duration requestTimeout;
    @org.springframework.beans.factory.annotation.Autowired
    public MallOptionGateway(ObjectMapper json){this(json,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build());}
    MallOptionGateway(ObjectMapper json,HttpClient http){this(json,http,Duration.ofSeconds(20));}
    MallOptionGateway(ObjectMapper json,HttpClient http,Duration timeout){
        if(timeout.isZero()||timeout.isNegative())throw new IllegalArgumentException("Request timeout must be positive");
        this.json=json;this.http=http;this.requestTimeout=timeout;
    }
    @Override public String validateUrl(ProcurementMall mall,String url) {
        try {URI u=URI.create(url);
            if(mall==null||ProductSourceMetadata.mall(url)!=mall||url.length()>2000)throw new IllegalArgumentException();
            return u.toASCIIString();
        }catch(Exception e){throw new IllegalArgumentException("선택한 매입처의 HTTP 또는 HTTPS 상품 링크를 입력해 주세요.");}
    }
    private static String id(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("매입처 상품 ID를 확인해 주세요.");return value;}
    @Override public SourceDetails inspect(ProcurementMall mall,String productId,String url) {
        try{return inspectDetails(mall,productId,url);}
        catch(tools.jackson.core.JacksonException e){throw new SupplierLookupFailure(SupplierLookupFailure.Code.RESPONSE_FORMAT,"PARSE",null,e);}
    }
    private SourceDetails inspectDetails(ProcurementMall mall,String productId,String url) {
        validateUrl(mall,url);String p=id(productId);
        if(mall==ProcurementMall.NAVER_SMART_STORE){
            if(NaverChannelPolicy.fromUrl(url).type()==NaverChannelType.SMARTSTORE)throw new IllegalArgumentException("일반 스마트스토어는 조회 지원 대상이 아닙니다.");
            String linked=ProductSourceMetadata.productId(mall,url,"");
            if(!linked.isBlank()&&!linked.equals(p))throw new SupplierLookupFailure(SupplierLookupFailure.Code.PRODUCT_MISMATCH,"IDENTITY",null,null);
        }
        String target=switch(mall) {
            case LFMALL -> "https://nxapi.lfmall.co.kr/product/detail/v1/options/"+p+"?stockCheckYn=N";
            case HAZZYS -> "https://www.hazzys.com/product.do?cmd=getProductDetail&PROD_CD="+p;
            case NAVER_SMART_STORE -> "https://shopping.naver.com/v2/channel-products/"+p;
            case LOTTE_ON -> "https://pbf.lotteon.com/product/v2/detail/search/base/sitm/"+p;
            case LOTTE_IMALL -> "https://www.lotteimall.com/goods/viewGoodsDetail.lotte?goods_no="+p;
            case HI_THEHYUNDAI -> "https://hi.thehyundai.com/product/"+p;
            case HMALL -> "https://api.hmall.com/api/hf/od/v1/baskt/attr-list?attrReq.slitmCd=&slitmCd="+p+"&uitmAttrTypeSeq=0&notSell=true";
        };
        String payload=fetch(mall,p,target);
        if(mall==ProcurementMall.NAVER_SMART_STORE&&payload.isBlank())return new SourceDetails("","","",List.of(),"",null,false,NaverChannelPolicy.unknown());
        if((mall==ProcurementMall.NAVER_SMART_STORE||mall==ProcurementMall.LOTTE_ON)&&!payload.isBlank()){
            var root=json.readTree(payload);
            if(root==null||!root.isObject())throw new SupplierLookupFailure(SupplierLookupFailure.Code.RESPONSE_FORMAT,"PARSE",null,null);
            boolean matches=mall==ProcurementMall.NAVER_SMART_STORE?SupplierMetadataParser.naverMatches(root,p):LotteProductPayload.matches(root,p);
            if(!matches)throw new SupplierLookupFailure(SupplierLookupFailure.Code.PRODUCT_MISMATCH,"IDENTITY",null,null);
        }
        if(mall==ProcurementMall.LFMALL) {
            var root=json.readTree(payload);
            if(root.path("body").isObject()) {
                var body=(tools.jackson.databind.node.ObjectNode)root.path("body");
                try {
                    var basic=json.readTree(fetch(mall,p,"https://nxapi.lfmall.co.kr/product/v1/basic/init/"+p+"?affiliateCode=2000&usePopup=Y"));
                    body.set("productBasicDTO",basic.path("body").path("productBasicDTO"));
                }catch(SearchBlocked e){throw e;}catch(Exception ignored){/* 상품 설명 실패 시에도 확인한 옵션은 보존한다. */}
                payload=json.writeValueAsString(root);
            }
        }
        return new MallOptionParser(json).details(mall,payload,p);
    }
    private String fetch(ProcurementMall mall,String productId,String url) {
        for(int attempt=0;attempt<2;attempt++)try {
            var request=HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).header("Accept","application/json,text/html").header("User-Agent",ProductPublicHeaders.userAgent()).header("Accept-Language","ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7").GET().build();
            var response=receive(request);
            if(response.statusCode()==403||response.statusCode()==418||response.statusCode()==429)throw blocked(mall,productId,url,"HTTP_RESTRICTED",response.statusCode());
            if(response.statusCode()==204)return "";
            if(response.statusCode()>=500&&attempt==0)continue;
            if(response.statusCode()!=200)throw new SupplierLookupFailure(SupplierLookupFailure.Code.HTTP,"FETCH",response.statusCode(),null);
            String body=new String(response.body(),StandardCharsets.UTF_8);
            for(String marker:List.of("비정상적인 접근","접근이 제한","보안 확인"))
                if(body.contains(marker))throw blocked(mall,productId,url,"SECURITY_CHECK",null);
            return body;
        }catch(SearchBlocked e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SupplierLookupFailure(SupplierLookupFailure.Code.INTERNAL,"FETCH",null,e);}catch(java.io.IOException e){if(attempt==1)throw new SupplierLookupFailure(e instanceof HttpTimeoutException?SupplierLookupFailure.Code.TIMEOUT:SupplierLookupFailure.Code.NETWORK,"FETCH",null,e);}
        throw new IllegalStateException("옵션 조회에 실패했습니다.");
    }
    private HttpResponse<byte[]> receive(HttpRequest request)throws IOException,InterruptedException {
        var body=new LimitedBodySubscriber();
        var response=http.sendAsync(request,info->{body.read=info.statusCode()==200;return body;});
        try {return response.get(requestTimeout.toNanos(),TimeUnit.NANOSECONDS);}
        catch(TimeoutException e) {
            var timeout=new HttpTimeoutException("Supplier response deadline exceeded");
            body.abort(timeout);response.cancel(true);throw timeout;
        }catch(InterruptedException e) {
            body.abort(e);response.cancel(true);throw e;
        }catch(ExecutionException e) {
            if(e.getCause() instanceof IOException failure)throw failure;
            if(e.getCause() instanceof RuntimeException failure)throw failure;
            throw new IOException("Supplier response failed",e.getCause());
        }
    }
    /** Bound memory during receipt and cancel the HTTP subscription on timeout or overflow. */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private boolean read=true;
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public synchronized void onSubscribe(Flow.Subscription value) {
            if(subscription!=null){value.cancel();return;}
            subscription=value;
            if(result.isDone()||!read){result.complete(new byte[0]);value.cancel();}
            else value.request(1);
        }
        @Override public synchronized void onNext(List<ByteBuffer> buffers) {
            if(result.isDone())return;
            for(var buffer:buffers) {
                if(buffer.remaining()>5_000_000-bytes.size()) {
                    abort(new SupplierLookupFailure(SupplierLookupFailure.Code.RESPONSE_FORMAT,"READ",null,null));return;
                }
                byte[] chunk=new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public synchronized void onError(Throwable error){abort(error);}
        @Override public synchronized void onComplete(){result.complete(bytes.toByteArray());}
        synchronized void abort(Throwable error){result.completeExceptionally(error);if(subscription!=null)subscription.cancel();}
    }
    private SearchBlocked blocked(ProcurementMall mall,String productId,String url,String code,Integer httpStatus) {
        var target=URI.create(url);
        log.warn("[PRODUCT_SUPPLIER_BLOCKED] mall={} productId={} target={}{} reason={}",mall,productId,target.getHost(),target.getPath(),code+" httpStatus="+httpStatus);
        return new SupplierAccessRestricted(mall,code,httpStatus);
    }
    public List<SourceOption> parse(ProcurementMall mall,String payload,String productId) {
        return new MallOptionParser(json).parse(mall,payload,productId);
    }
    @Override public List<SourceOption> options(ProcurementMall mall,String productId,String url) {return inspect(mall,productId,url).options();}
}
