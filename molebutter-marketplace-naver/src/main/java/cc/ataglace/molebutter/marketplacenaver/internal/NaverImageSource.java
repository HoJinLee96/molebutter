package cc.ataglace.molebutter.marketplacenaver.internal;
import cc.ataglace.molebutter.media.api.R2Images;

import java.io.ByteArrayInputStream;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.marketplace.api.*;

/** New product images are read through the verified PUBLIC R2 library, never arbitrary URLs. */
@Component
final class NaverImageSource {
    record Content(byte[] bytes,String mime,String extension) {}
    private final R2Images r2;private final cc.ataglace.molebutter.media.api.ImageAssets assets;
    NaverImageSource(R2Images r2){this(r2,null);}
    @org.springframework.beans.factory.annotation.Autowired
    NaverImageSource(R2Images r2,cc.ataglace.molebutter.media.api.ImageAssets assets){this.r2=r2;this.assets=assets;}
    Content read(Long actor,NaverEditor.Image image){
        if(image.assetId()!=null&&!image.assetId().isBlank()){if(assets==null)throw new InputValidationFailure("기존 로컬 이미지를 R2 이미지로 다시 선택해 주세요.");return inspect(assets.readEdited(actor,image.assetId()).bytes());}
        return inspect(r2.read(actor,image.url()).bytes());
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
    private static InputValidationFailure invalid(){return new InputValidationFailure("R2에서 JPG·PNG 이미지를 다시 선택해 주세요.");}
}
