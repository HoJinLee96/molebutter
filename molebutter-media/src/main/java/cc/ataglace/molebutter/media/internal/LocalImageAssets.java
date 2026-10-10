package cc.ataglace.molebutter.media.internal;

import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.common.api.OperationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.media.api.ImageAssets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.net.URI;
import java.security.SecureRandom;

@Service
public class LocalImageAssets implements ImageAssets {
    private final JdbcTemplate db;
    private final BusinessAccess access;
    private final Path directory;
    private final TransactionTemplate transactions;
    private final String publicBaseUrl;
    static final int MAX_BYTES=10*1024*1024;
    static final long MAX_PIXELS=25_000_000L;
    public LocalImageAssets(JdbcTemplate db, BusinessAccess access, PlatformTransactionManager manager,
            @Value("${marketplace.assets.directory:./data/marketplace-assets}") String directory,
            @Value("${marketplace.assets.public-base-url:}") String publicBaseUrl){
        this.db=db;this.access=access;this.transactions=new TransactionTemplate(manager);
        this.directory=Path.of(directory).toAbsolutePath().normalize();
        this.publicBaseUrl=publicBaseUrl;
    }
    @Override @Transactional
    public Asset upload(Long actor,byte[] bytes){
        access.productActor(actor,true);
        var image=inspect(bytes);String id=UUID.randomUUID().toString(),extension=image.mimeType().equals("image/png")?"png":"jpg";
        Path file=directory.resolve(id+"."+extension);
        try{
            Files.createDirectories(directory);
            Path staging=Files.createTempFile(directory,"upload-",".part");
            try{Files.write(staging,bytes);Files.move(staging,file,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(staging);}
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                public void afterCompletion(int status){if(status!=STATUS_COMMITTED)removeFile(file);}
            });
            db.update("INSERT INTO marketplace_asset(id,relative_path,media_type,width,height,bytes,created_by,created_at,pending_delete_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
                id,file.getFileName().toString(),image.mimeType(),image.width(),image.height(),bytes.length,actor);
            return new Asset(id,"/api/marketplaces/assets/"+id,image.mimeType(),image.width(),image.height(),bytes.length);
        }catch(IOException e){removeFile(file);throw new OperationFailure("이미지를 저장하지 못했습니다. 다시 시도해 주세요.");}
    }
    record Inspected(String mimeType,int width,int height) {}
    static Inspected inspect(byte[] bytes){
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw new InputValidationFailure("이미지는 10MiB 이하 JPG·PNG 파일을 선택해 주세요.");
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext())throw new InputValidationFailure("JPG·PNG 이미지가 필요합니다.");
            var reader=readers.next();
            try{
                String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                if(!Set.of("png","jpeg","jpg").contains(format))throw new InputValidationFailure("JPG·PNG 이미지가 필요합니다.");
                reader.setInput(input,true,true);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<=0||height<=0||(long)width*height>MAX_PIXELS)throw new InputValidationFailure("이미지는 2,500만 픽셀 이하로 선택해 주세요.");
                var decoded=reader.read(0);if(decoded==null)throw new InputValidationFailure("이미지를 읽지 못했습니다.");decoded.flush();
                return new Inspected(format.equals("png")?"image/png":"image/jpeg",width,height);
            }finally{reader.dispose();}
        }catch(IOException|IllegalArgumentException e){if(e instanceof InputValidationFailure input)throw input;throw new InputValidationFailure("이미지를 읽지 못했습니다. JPG·PNG 파일을 확인해 주세요.");}
    }
    @Override public AssetContent read(Long actor,String id){
        var stored=stored(actor,id);
        try{Path file=resolveFile(stored.path());if(Files.isSymbolicLink(file))throw new IOException();return new AssetContent(stored.asset(),Files.readAllBytes(file));}
        catch(IOException e){throw new OperationFailure("이미지를 읽지 못했습니다. 다시 업로드해 주세요.");}
    }
    @Override public Asset metadata(Long actor,String id){return stored(actor,id).asset();}
    /** Only a saved draft image may be selected for external delivery. URLs never contain its private ID. */
    @Override public String publicUrl(Long actor,String id){return publicUrl(actor,id,false);}
    /** Submission preparation may use the actor's upload without modifying the common reference. */
    @Override public String submissionUrl(Long actor,String id){return publicUrl(actor,id,true);}
    private String publicUrl(Long actor,String id,boolean submission){
        stored(actor,id);
        String base=publicBase(publicBaseUrl);
        return transactions.execute(tx->{
            db.queryForList("SELECT id FROM marketplace_asset WHERE id=? FOR UPDATE",String.class,id);
            Long references=db.queryForObject("SELECT COUNT(*) FROM marketplace_draft_asset WHERE asset_id=?",Long.class,id);
            if(references==null||references==0){
                Long owned=db.queryForObject("SELECT COUNT(*) FROM marketplace_asset WHERE id=? AND created_by=?",Long.class,id,actor);
                Long pins=db.queryForObject("SELECT COUNT(*) FROM marketplace_execution_asset WHERE asset_id=?",Long.class,id);
                if(!submission||((owned==null||owned==0)&&(pins==null||pins==0)))throw new InputValidationFailure("전송할 이미지의 소유자와 저장 참조를 확인해 주세요.");
            }
            var tokens=db.queryForList("SELECT token FROM marketplace_asset_publication WHERE asset_id=?",String.class,id);
            String token;
            if(tokens.isEmpty()){
                byte[] random=new byte[32];new SecureRandom().nextBytes(random);token=HexFormat.of().formatHex(random);
                db.update("INSERT INTO marketplace_asset_publication(asset_id,token,created_at) VALUES(?,?,CURRENT_TIMESTAMP(6))",id,token);
            }else token=tokens.getFirst();
            return base+"/marketplace-images/"+token;
        });
    }
    static String publicBase(String value){
        try{
            URI uri=URI.create(value==null?"":value.trim());String host=uri.getHost();
            if(!"https".equalsIgnoreCase(uri.getScheme())||host==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
                    ||(uri.getPort()!=-1&&uri.getPort()!=443)||!(uri.getPath()==null||uri.getPath().isEmpty()||uri.getPath().equals("/"))
                    ||host.equalsIgnoreCase("localhost")||host.toLowerCase(Locale.ROOT).endsWith(".localhost")||host.endsWith(".local")
                    ||host.contains(":")||host.matches("[0-9.]+")||!host.contains("."))throw new IllegalArgumentException();
            return "https://"+host.toLowerCase(Locale.ROOT);
        }catch(IllegalArgumentException e){throw new InputValidationFailure("로컬 이미지 전송에는 외부에서 접근 가능한 HTTPS 이미지 주소 설정이 필요합니다.");}
    }
    @Override public AssetContent published(String token){
        if(token==null||!token.matches("[a-f0-9]{64}"))throw new InputValidationFailure("이미지를 찾을 수 없습니다.");
        var rows=db.query("SELECT a.id,a.relative_path,a.media_type,a.width,a.height,a.bytes FROM marketplace_asset_publication p JOIN marketplace_asset a ON a.id=p.asset_id WHERE p.token=?",
            (rs,n)->new Stored(rs.getString(2),new Asset(rs.getString(1),"",rs.getString(3),rs.getInt(4),rs.getInt(5),rs.getLong(6))),token);
        if(rows.isEmpty())throw new InputValidationFailure("이미지를 찾을 수 없습니다.");
        try{Path file=resolveFile(rows.getFirst().path());if(Files.isSymbolicLink(file))throw new IOException();return new AssetContent(rows.getFirst().asset(),Files.readAllBytes(file));}
        catch(IOException e){throw new OperationFailure("이미지를 찾을 수 없습니다.");}
    }
    private Stored stored(Long actor,String id){
        access.productActor(actor,true);checkId(id);
        var rows=db.query("SELECT relative_path,media_type,width,height,bytes FROM marketplace_asset WHERE id=?",
            (rs,n)->new Stored(rs.getString(1),new Asset(id,"/api/marketplaces/assets/"+id,rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getLong(5))),id);
        if(rows.isEmpty())throw new InputValidationFailure("이미지를 찾을 수 없습니다.");return rows.getFirst();
    }
    record Stored(String path,Asset asset) {}
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void replaceReferences(Long actor,String draftId,Set<String> ids){
        // Save and reaping lock the same asset rows, so a referenced image cannot be deleted concurrently.
        var sorted=new TreeSet<>(ids);
        var previous=db.queryForList("SELECT asset_id FROM marketplace_draft_asset WHERE draft_id=?",String.class,Long.parseLong(draftId));
        var locked=new TreeSet<>(sorted);locked.addAll(previous);
        for(String id:locked)db.queryForList("SELECT id FROM marketplace_asset WHERE id=? FOR UPDATE",String.class,id);
        for(String id:sorted){checkId(id);var owners=db.queryForList("SELECT created_by FROM marketplace_asset WHERE id=? FOR UPDATE",Long.class,id);
            if(owners.isEmpty())throw new InputValidationFailure("저장할 이미지를 찾을 수 없습니다.");
            Long count=db.queryForObject("SELECT COUNT(*) FROM marketplace_draft_asset WHERE asset_id=?",Long.class,id);
            if(!Objects.equals(owners.getFirst(),actor)&&count==0)throw new InputValidationFailure("다른 사용자의 미저장 이미지를 사용할 수 없습니다.");
        }
        db.update("DELETE FROM marketplace_draft_asset WHERE draft_id=?",Long.parseLong(draftId));
        for(String id:sorted){db.update("INSERT INTO marketplace_draft_asset(draft_id,asset_id) VALUES(?,?)",Long.parseLong(draftId),id);db.update("UPDATE marketplace_asset SET pending_delete_at=NULL WHERE id=?",id);}
        for(String id:previous)if(!sorted.contains(id))db.update("UPDATE marketplace_asset a SET pending_delete_at=CURRENT_TIMESTAMP(6) WHERE id=? AND NOT EXISTS(SELECT 1 FROM marketplace_draft_asset r WHERE r.asset_id=a.id) AND NOT EXISTS(SELECT 1 FROM marketplace_execution_asset r WHERE r.asset_id=a.id)",id);
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void pinReferences(Long actor,long submissionId,String draftId,Set<String> ids){
        // Pins and collection use the same rows and lock order as draft reference replacement.
        for(String id:new TreeSet<>(ids)){
            checkId(id);
            var found=db.queryForList("SELECT id FROM marketplace_asset WHERE id=? FOR UPDATE",String.class,id);
            if(found.isEmpty())throw new InputValidationFailure("전송할 이미지를 찾을 수 없습니다.");
            Long count=db.queryForObject("SELECT COUNT(*) FROM marketplace_draft_asset WHERE draft_id=? AND asset_id=?",Long.class,Long.parseLong(draftId),id);
            if(count==null||count!=1){
                Long owned=db.queryForObject("SELECT COUNT(*) FROM marketplace_asset WHERE id=? AND created_by=?",Long.class,id,actor);
                if(owned==null||owned!=1)throw new InputValidationFailure("전송할 이미지의 소유자를 확인해 주세요.");
            }
            db.update("INSERT INTO marketplace_execution_asset(submission_id,asset_id) VALUES(?,?)",submissionId,id);
            db.update("UPDATE marketplace_asset SET pending_delete_at=NULL WHERE id=?",id);
        }
    }
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public void releaseReferences(long submissionId){
        var ids=db.queryForList("SELECT asset_id FROM marketplace_execution_asset WHERE submission_id=? ORDER BY asset_id",String.class,submissionId);
        for(String id:ids)db.queryForList("SELECT id FROM marketplace_asset WHERE id=? FOR UPDATE",String.class,id);
        db.update("DELETE FROM marketplace_execution_asset WHERE submission_id=?",submissionId);
        for(String id:ids)db.update("UPDATE marketplace_asset a SET pending_delete_at=COALESCE(pending_delete_at,CURRENT_TIMESTAMP(6)) WHERE id=? AND NOT EXISTS(SELECT 1 FROM marketplace_draft_asset r WHERE r.asset_id=a.id) AND NOT EXISTS(SELECT 1 FROM marketplace_execution_asset r WHERE r.asset_id=a.id)",id);
    }
    @Override @Scheduled(fixedDelay=3600000,initialDelay=3600000)
    public void reapUnreferenced(){
        // Bounded batches; failed deletion keeps metadata for a later attempt.
        var ids=db.queryForList("SELECT id FROM marketplace_asset a WHERE a.pending_delete_at < CURRENT_TIMESTAMP(6)-INTERVAL 24 HOUR AND NOT EXISTS(SELECT 1 FROM marketplace_draft_asset r WHERE r.asset_id=a.id) AND NOT EXISTS(SELECT 1 FROM marketplace_execution_asset r WHERE r.asset_id=a.id) LIMIT 100",String.class);
        for(String id:ids)transactions.executeWithoutResult(tx->{
            var paths=db.queryForList("SELECT relative_path FROM marketplace_asset WHERE id=? AND pending_delete_at < CURRENT_TIMESTAMP(6)-INTERVAL 24 HOUR FOR UPDATE",String.class,id);
            if(paths.isEmpty())return;
            Long count=db.queryForObject("SELECT COUNT(*) FROM marketplace_draft_asset WHERE asset_id=?",Long.class,id);if(count!=0)return;
            Long executions=db.queryForObject("SELECT COUNT(*) FROM marketplace_execution_asset WHERE asset_id=?",Long.class,id);if(executions!=0)return;
            try{Files.deleteIfExists(resolveFile(paths.getFirst()));db.update("DELETE FROM marketplace_asset WHERE id=?",id);}
            catch(IOException e){/* Preserve metadata; the next cleanup can retry without disclosing filesystem paths. */}
        });
    }
    private Path resolveFile(String value){
        if(!value.matches("[a-f0-9-]{36}\\.(?:png|jpg)"))throw new OperationFailure("이미지 정보를 확인해 주세요.");
        Path file=directory.resolve(value).normalize();if(!file.getParent().equals(directory))throw new OperationFailure("이미지 정보를 확인해 주세요.");return file;
    }
    private static void checkId(String id){if(id==null||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new InputValidationFailure("이미지 ID를 확인해 주세요.");}
    private static void removeFile(Path path){try{Files.deleteIfExists(path);}catch(IOException ignored){}}
}
