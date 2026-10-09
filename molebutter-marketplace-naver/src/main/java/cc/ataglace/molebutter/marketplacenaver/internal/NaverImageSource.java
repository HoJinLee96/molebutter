package cc.ataglace.molebutter.marketplacenaver.internal;
import cc.ataglace.molebutter.media.api.ImageAssets;

import java.net.*;
import java.net.http.*;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Component;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.*;
import static cc.ataglace.molebutter.marketplace.api.MarketplaceFailure.Kind.*;

/** Private draft assets need no public publication. Remote inputs use an operator-owned host allowlist. */
@Component
final class NaverImageSource {
    record Content(byte[] bytes,String mime,String extension) {}
    private final ImageAssets assets;
    private final Set<String> hosts;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    @Autowired NaverImageSource(ImageAssets assets,
            @Value("${marketplace.naver.image-source-hosts:assets.molebutter.link,shop-phinf.pstatic.net,shopping-phinf.pstatic.net,img.lfmall.co.kr,image.lfmall.co.kr}") String configuredHosts){
        this.assets=assets;var allowed=new HashSet<String>();
        for(String host:configuredHosts.split(",")){String value=host.trim().toLowerCase(Locale.ROOT);if(value.matches("[a-z0-9.-]+")&&value.contains("."))allowed.add(value);}
        this.hosts=Set.copyOf(allowed);
    }
    Content read(Long actor,NaverEditor.Image image){
        if(image.assetId()!=null&&!image.assetId().isBlank())return inspect(assets.read(actor,image.assetId()).bytes());
        URI uri=remoteUri(image.url());
        try{for(var address:InetAddress.getAllByName(uri.getHost()))if(!publicAddress(address))throw invalid();}
        catch(UnknownHostException e){throw new MarketplaceFailure(NETWORK,"NAVER");}
        var subscriber=new NaverHttpTransport.LimitedBody(10*1024*1024);
        var future=http.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25)).header("Accept","image/jpeg,image/png,image/gif,image/bmp").GET().build(),info->subscriber);
        try{var response=future.get(25,TimeUnit.SECONDS);if(response.statusCode()!=200)throw new MarketplaceFailure(REJECTED,"NAVER");return inspect(response.body());}
        catch(TimeoutException e){throw new MarketplaceFailure(TIMEOUT,"NAVER");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new MarketplaceFailure(CANCELLED,"NAVER");}
        catch(ExecutionException e){if(e.getCause() instanceof MarketplaceFailure f)throw f;throw new MarketplaceFailure(NETWORK,"NAVER");}
        finally{subscriber.cancel();future.cancel(true);}
    }
    URI remoteUri(String value){
        try{URI uri=URI.create(value);String host=uri.getHost();
            if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||!hosts.contains(host.toLowerCase(Locale.ROOT))||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null
                    ||(uri.getPort()!=-1&&uri.getPort()!=443))throw invalid();return uri;
        }catch(IllegalArgumentException|NullPointerException e){throw invalid();}
    }
    static boolean publicAddress(InetAddress address){
        if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress())return false;
        byte[] bytes=address.getAddress();
        if(bytes.length==16)return (bytes[0]&0xfe)!=0xfc;
        int first=bytes[0]&255,second=bytes[1]&255;
        return first!=0&&first!=127&&first<224&&!(first==100&&second>=64&&second<=127)&&!(first==192&&second==0)&&!(first==198&&(second==18||second==19));
    }
    static Content inspect(byte[] bytes){
        if(bytes==null||bytes.length<1||bytes.length>10*1024*1024)throw new InputValidationFailure("이미지는 10MiB 이하로 선택해 주세요.");
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw invalid();var reader=readers.next();
            try{String format=reader.getFormatName().toLowerCase(Locale.ROOT);if(!Set.of("jpeg","jpg","png","gif","bmp").contains(format))throw invalid();
                reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);if(width<1||height<1||(long)width*height>25_000_000L)throw invalid();
                var decoded=reader.read(0);if(decoded==null)throw invalid();decoded.flush();String extension=format.equals("jpeg")?"jpg":format;
                return new Content(bytes,"image/"+(extension.equals("jpg")?"jpeg":extension),extension);
            }finally{reader.dispose();}
        }catch(java.io.IOException|IllegalArgumentException e){throw invalid();}
    }
    private static InputValidationFailure invalid(){return new InputValidationFailure("이미지 파일을 업로드하거나 허용된 공개 HTTPS 이미지 주소를 입력해 주세요.");}
}
