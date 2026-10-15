package cc.ataglace.molebutter.marketplace.internal;

import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.media.api.ImageInspection;
import cc.ataglace.molebutter.marketplace.api.MarketplaceEditing.Change;
import static org.assertj.core.api.Assertions.*;

class ComparisonMediaTest {
    ImageInspection.Fingerprint file(String key,String sha,String pixels){return new ImageInspection.Fingerprint(key,sha,pixels,750,750,100L,null);}
    ComparisonMedia.Shape image(String key,String sha,String pixels){return new ComparisonMedia.Shape(List.of("REPRESENTATION"),List.of(file(key,sha,pixels)));}
    @Test void identityUsesFilesAndPixelsRatherThanUrlsOrAssetIds(){
        var a=image("asset:local","same","px");var b=image("url:cdn","same","px");
        assertThat(ComparisonMedia.decide(a,b,null).state()).isEqualTo("SAME");
        assertThat(ComparisonMedia.decide(a,image("url:reencoded","different","px"),null).state()).isEqualTo("SAME");
        assertThat(ComparisonMedia.decide(a,image("url:unproven","different","other"),null).state()).isEqualTo("UNVERIFIED");
        var evidence=new ComparisonMedia.Evidence(Map.of(),a,b,List.of("source"),"COMMON");
        assertThat(ComparisonMedia.decide(a,image("url:cdn","changed","changed"),evidence).state()).isEqualTo("DIFFERENT");
    }
    @Test void rolesOrderAndTextAreMeaningfulButAccessFailuresAreNotChanges(){
        var a=image("asset:local","same","px");
        assertThat(ComparisonMedia.decide(a,new ComparisonMedia.Shape(List.of("DETAIL"),a.files()),null).state()).isEqualTo("DIFFERENT");
        assertThat(ComparisonMedia.decide(a,new ComparisonMedia.Shape(a.structure(),Collections.singletonList(null)),null).state()).isEqualTo("UNVERIFIED");
        var service=new ComparisonMedia(null,new ObjectMapper(),null);
        var files=Map.of("url:https://source/a",file("source","same","px"),"url:https://cdn/b",file("cdn","same","px"));
        var app=service.shape(new Change("media.contents","option",List.of(Map.of("type","HTML","value","<p>본문<img src=\"https://source/a\"></p>"))),files);
        var remote=service.shape(new Change("media.contents","option",List.of(Map.of("type","HTML","value","<p>본문<img src=\"https://cdn/b\"></p>"))),files);
        assertThat(ComparisonMedia.decide(app,remote,null).state()).isEqualTo("SAME");
        var changed=service.shape(new Change("media.contents","option",List.of(Map.of("type","HTML","value","<p>바뀐 본문<img src=\"https://cdn/b\"></p>"))),files);
        assertThat(ComparisonMedia.decide(app,changed,null).state()).isEqualTo("DIFFERENT");
    }
}
