package cc.ataglace.molebutter.marketplacenaver.api;

import java.util.Map;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;
import cc.ataglace.molebutter.marketplace.api.NaverEditor;

/** Server-only source documents and authenticated requests. Never expose this interface to the browser. */
public interface NaverGateway {
    record Response(int status, byte[] body, String retryAfter, Map<String,String> headers) {
        public Response(int status, byte[] body) { this(status,body,null,Map.of()); }
    }
    String accountKey();
    <T> T session(Supplier<T> work);
    JsonNode product(String originProductNo);
    JsonNode channel(String channelProductNo);
    JsonNode search(JsonNode request);
    JsonNode metadata(String kind,Map<String,String> query);
    Response write(String method,String path,JsonNode payload);
    String uploadImage(Long actor,NaverEditor.Image image);
    JsonNode parse(Response response);
}
