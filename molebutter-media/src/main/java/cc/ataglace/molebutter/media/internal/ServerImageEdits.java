package cc.ataglace.molebutter.media.internal;

import cc.ataglace.molebutter.media.api.*;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.security.MessageDigest;

@Service
public final class ServerImageEdits implements ImageEdits {
    private final R2Images r2;private final ImageAssets assets;private final BusinessAccess access;private final JdbcTemplate db;private final TransactionTemplate tx;
    private record Opened(Long actor,String url,byte[] bytes,byte[] preview,int width,int height,Instant expires) {}
    private final Map<String,Opened> sources=new LinkedHashMap<>();
    public ServerImageEdits(R2Images r2,ImageAssets assets,BusinessAccess access,JdbcTemplate db,PlatformTransactionManager manager){this.r2=r2;this.assets=assets;this.access=access;this.db=db;tx=new TransactionTemplate(manager);}
    public Source open(Long actor,String url,String assetId){
        access.productActor(actor,true);byte[] bytes;Rect rect=null;
        if(assetId!=null&&!assetId.isBlank()){
            assets.editedMetadata(actor,assetId);
            var row=db.queryForMap("SELECT original_asset_id,source_url,source_sha256,x,y,width,height FROM marketplace_image_edit WHERE asset_id=?",assetId);
            url=(String)row.get("source_url");bytes=assets.read(actor,(String)row.get("original_asset_id")).bytes();if(!hash(bytes).equals(row.get("source_sha256")))throw invalid("보관한 원본 이미지가 변경되었습니다. R2에서 다시 선택해 주세요.");
            rect=new Rect(((Number)row.get("x")).intValue(),((Number)row.get("y")).intValue(),((Number)row.get("width")).intValue(),((Number)row.get("height")).intValue());
        }else bytes=r2.read(actor,url).bytes();
        var info=LocalImageAssets.inspect(bytes);byte[] preview=encode(decode(bytes));String token=UUID.randomUUID().toString()+UUID.randomUUID();
        synchronized(sources){sources.values().removeIf(v->v.expires().isBefore(Instant.now()));long used=sources.values().stream().mapToLong(v->v.bytes().length+v.preview().length).sum();if(sources.size()>=32||used+bytes.length+preview.length>64*1024*1024)throw invalid("열린 이미지 편집 창이 너무 많습니다. 잠시 후 다시 시도해 주세요.");sources.put(token,new Opened(actor,url,bytes,preview,info.width(),info.height(),Instant.now().plusSeconds(600)));}
        return new Source(token,"/api/marketplaces/image-edits/source/"+token,url,info.width(),info.height(),rect);
    }
    public ImageAssets.AssetContent preview(Long actor,String token){var s=require(actor,token);return new ImageAssets.AssetContent(new ImageAssets.Asset("", "","image/png",s.width(),s.height(),s.preview().length),s.preview());}
    public ImageAssets.Asset crop(Long actor,Request request){
        if(request==null||request.rect()==null)throw invalid("자를 범위를 선택해 주세요.");var s=require(actor,request.token());var r=request.rect();
        if(r.x()<0||r.y()<0||r.width()<1||r.height()<1||(long)r.x()+r.width()>s.width()||(long)r.y()+r.height()>s.height())throw invalid("이미지 안에서 자를 범위를 선택해 주세요.");
        var original=decode(s.bytes());byte[] cropped;try{cropped=encode(original.getSubimage(r.x(),r.y(),r.width(),r.height()));}finally{original.flush();}
        LocalImageAssets.inspect(cropped);
        return tx.execute(status->{var source=assets.upload(actor,s.bytes());var output=assets.upload(actor,cropped);
            db.update("INSERT INTO marketplace_image_edit(asset_id,original_asset_id,source_url,source_sha256,x,y,width,height,processing_version) VALUES(?,?,?,?,?,?,?,?,1)",output.id(),source.id(),s.url(),hash(s.bytes()),r.x(),r.y(),r.width(),r.height());return output;});
    }
    private Opened require(Long actor,String token){access.productActor(actor,true);synchronized(sources){var value=sources.get(token);if(value==null||!Objects.equals(value.actor(),actor)||value.expires().isBefore(Instant.now()))throw invalid("이미지 편집 시간이 만료되었습니다. 편집 창을 다시 열어 주세요.");return value;}}
    private static BufferedImage decode(byte[] bytes){try{var image=ImageIO.read(new ByteArrayInputStream(bytes));if(image==null)throw invalid("이미지를 읽지 못했습니다.");return image;}catch(IOException e){throw invalid("이미지를 읽지 못했습니다.");}}
    private static byte[] encode(BufferedImage image){try{var out=new ByteArrayOutputStream();if(!ImageIO.write(image,"png",out))throw invalid("이미지를 만들지 못했습니다.");return out.toByteArray();}catch(IOException e){throw invalid("이미지를 만들지 못했습니다.");}}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
    private static InputValidationFailure invalid(String text){return new InputValidationFailure(text);}
}
