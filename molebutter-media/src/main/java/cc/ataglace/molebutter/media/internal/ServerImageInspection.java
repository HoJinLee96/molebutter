package cc.ataglace.molebutter.media.internal;

import cc.ataglace.molebutter.media.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Service
public final class ServerImageInspection implements ImageInspection {
    private final R2Images r2;private final ImageAssets assets;private final BusinessAccess access;
    private final HttpClient http;
    @org.springframework.beans.factory.annotation.Autowired
    public ServerImageInspection(R2Images r2,ImageAssets assets,BusinessAccess access){this(r2,assets,access,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build());}
    ServerImageInspection(R2Images r2,ImageAssets assets,BusinessAccess access,HttpClient http){this.r2=r2;this.assets=assets;this.access=access;this.http=http;}
    private record File(String mime,int width,int height,long bytes,String sha256,boolean existingOnly) {}
    public List<Fingerprint> fingerprints(Long actor,List<Input> images){
        access.productActor(actor,true);if(images==null||images.size()>500)throw new InputValidationFailure("비교할 이미지는 500개 이하로 선택해 주세요.");
        var unique=new LinkedHashMap<String,Input>();for(var i:images)unique.putIfAbsent(ImageInspection.fingerprintKey(i),i);
        var work=new ArrayList<>(unique.entrySet());var out=new ArrayList<Fingerprint>();
        var executor=java.util.concurrent.Executors.newFixedThreadPool(4);
        try{var tasks=work.stream().<java.util.concurrent.Callable<Fingerprint>>map(e->()->fingerprint(actor,e.getKey(),e.getValue())).toList();var futures=executor.invokeAll(tasks,15,java.util.concurrent.TimeUnit.SECONDS);
            for(int n=0;n<futures.size();n++){try{out.add(futures.get(n).get());}catch(Exception unavailable){out.add(new Fingerprint(work.get(n).getKey(),null,null,null,null,null,"이미지 내용 비교 시간이 초과되었거나 파일을 읽지 못했습니다."));}}
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}finally{executor.shutdownNow();}return List.copyOf(out);
    }
    private Fingerprint fingerprint(Long actor,String key,Input input){try{
        byte[] bytes;
        if(input.assetId()!=null)bytes=assets.read(actor,input.assetId()).bytes();
        else {try{bytes=r2.read(actor,input.url()).bytes();}catch(InputValidationFailure invalid){if(!input.existing()||!cdn(input.url()))throw invalid;bytes=remoteBytes(input.url(),true);}}
        var metadata=LocalImageAssets.inspect(bytes,input.existing());var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));String pixelHash=null;
        if(image!=null){var digest=java.security.MessageDigest.getInstance("SHA-256");digest.update(java.nio.ByteBuffer.allocate(8).putInt(image.getWidth()).putInt(image.getHeight()).array());var buffer=java.nio.ByteBuffer.allocate(image.getWidth()*4);for(int y=0;y<image.getHeight();y++){buffer.clear();for(int x=0;x<image.getWidth();x++)buffer.putInt(image.getRGB(x,y));digest.update(buffer.array());}pixelHash=HexFormat.of().formatHex(digest.digest());}
        return new Fingerprint(key,hash(bytes),pixelHash,metadata.width(),metadata.height(),(long)bytes.length,null);
    }catch(Exception unavailable){return new Fingerprint(key,null,null,null,null,null,"이미지 실제 내용을 확인하지 못했습니다. 파일 접근과 원본을 확인해 주세요.");}}
    public List<Check> inspect(Long actor,List<Input> images){
        access.productActor(actor,true);if(images==null||images.size()>500)throw new InputValidationFailure("검사할 이미지는 500개 이하로 선택해 주세요.");
        var cache=new HashMap<String,File>();var publicFailures=new HashMap<String,String>();var published=new HashSet<String>();var failures=new HashMap<String,String>();var checks=new ArrayList<Check>();
        for(var image:images){String key=image.assetId()!=null?"asset:"+image.assetId():"url:"+image.url(),failureKey=key+"|"+image.existing();File file=null;
            try{
                if(failures.containsKey(failureKey))throw new InputValidationFailure(failures.get(failureKey));
                file=cache.get(key);if(file==null){file=read(actor,image);cache.put(key,file);}
                if(file.existingOnly()&&!image.existing())throw new InputValidationFailure("새 이미지에는 R2 원본 또는 앱 편집본을 선택해 주세요.");
                if(image.assetId()!=null&&(image.market().equals("COUPANG")||image.market().equals("ELEVENST")||image.market().equals("LOTTEON")||image.role().equals("DESCRIPTION"))){if(publicFailures.containsKey(image.assetId()))throw new InputValidationFailure(publicFailures.get(image.assetId()));if(published.add(image.assetId()))try{if(!Objects.equals(file.sha256(),remote(assets.submissionUrl(actor,image.assetId()),false,false).sha256()))throw new InputValidationFailure("공개 이미지가 보관한 편집본과 다릅니다. 공개 이미지 경로를 확인해 주세요.");}catch(RuntimeException failure){publicFailures.put(image.assetId(),failure.getMessage());throw failure;}}
                var errors=ImageRules.errors(image.market(),image.role(),file.mime(),file.width(),file.height(),file.bytes(),file.existingOnly());
                var warnings="NAVER".equals(image.market())&&!"DESCRIPTION".equals(image.role())&&(file.width()!=1000||file.height()!=1000)?List.of("스마트스토어 상품 이미지 권장 크기는 1000×1000px입니다. 필수 조건은 아닙니다."):List.<String>of();
                checks.add(check(image,errors.isEmpty()?"PASS":"FAIL",file,errors,warnings));
            }catch(RuntimeException failure){String message=failure instanceof InputValidationFailure?failure.getMessage():"이미지 실제 파일을 읽지 못했습니다. 다시 조회해 주세요.";if(file==null)failures.put(failureKey,message);checks.add(check(image,"UNVERIFIED",file,List.of(message),List.of()));}
        }
        return List.copyOf(checks);
    }
    private File read(Long actor,Input image){
        if(image.assetId()!=null){var c=assets.readEdited(actor,image.assetId());var a=LocalImageAssets.inspect(c.bytes());
            return new File(a.mimeType(),a.width(),a.height(),c.bytes().length,hash(c.bytes()),false);
        }
        try{var a=r2.resolve(actor,image.url());return new File(a.mimeType(),a.width(),a.height(),a.bytes(),null,false);}
        catch(InputValidationFailure invalid){if(!image.existing()||!cdn(image.url()))throw invalid;return remote(image.url(),true,image.market().equals("ELEVENST"));}
    }
    private File remote(String value,boolean cdn,boolean webp){
        byte[] bytes=remoteBytes(value,cdn);var a=LocalImageAssets.inspect(bytes,cdn&&webp);return new File(a.mimeType(),a.width(),a.height(),bytes.length,hash(bytes),cdn);
    }
    private byte[] remoteBytes(String value,boolean cdn){
        try{
            var uri=URI.create(value);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getPort()!=-1&&uri.getPort()!=443)throw new IllegalArgumentException();
            if(cdn&&!cdn(value))throw new IllegalArgumentException();
            for(var address:InetAddress.getAllByName(uri.getHost()))if(address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress())throw new IllegalArgumentException();
            var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("User-Agent","molebutter-image-check").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()){if(response.statusCode()!=200)throw new IllegalArgumentException();byte[] bytes=stream.readNBytes(LocalImageAssets.MAX_BYTES+1);if(bytes.length>LocalImageAssets.MAX_BYTES)throw new IllegalArgumentException();return bytes;}
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new InputValidationFailure("이미지 조회가 중단되었습니다.");}catch(Exception e){throw new InputValidationFailure("외부에서 이미지 주소를 읽지 못했습니다. 공개 HTTPS 주소와 접근 설정을 확인해 주세요.");}
    }
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static boolean cdn(String value){try{String host=URI.create(value).getHost();return host!=null&&(host.endsWith(".coupangcdn.com")||host.endsWith(".phinf.naver.net")||host.endsWith(".pstatic.net")||host.endsWith(".011st.com")||host.equals("image.11st.co.kr")||host.equals("contents.lotteon.com")||host.equals("image.lotteon.com")||host.equals("doc-pub.lotteon.com"));}catch(RuntimeException e){return false;}}
    private static Check check(Input i,String status,File f,List<String> reasons,List<String> warnings){return new Check(i.market(),i.role(),i.optionId(),i.imageId(),i.url(),status,f==null?null:f.width(),f==null?null:f.height(),f==null?null:f.bytes(),reasons,warnings);}
}
