package cc.ataglace.molebutter.media.internal;

import cc.ataglace.molebutter.common.api.InputValidationFailure;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.storage.api.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class R2ImageLibraryTest {
    final ObjectStorage storage=mock(ObjectStorage.class);
    final BusinessAccess access=mock(BusinessAccess.class);
    final R2ImageLibrary library=new R2ImageLibrary(storage,access);
    R2ImageLibraryTest(){when(storage.publicUrl(eq(StorageArea.PUBLIC),anyString())).thenAnswer(i->Optional.of(new URI("https","assets.example.test","/"+i.getArgument(1),null)));}
    ObjectMetadata meta(String key,long size){return new ObjectMetadata(StorageArea.PUBLIC,key,size,"image/png","etag",Instant.EPOCH,Map.of());}
    byte[] image()throws Exception{var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(600,600,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
    @Test void groupsLegacyAndNewPathsAndBindsPaginationToProduct(){
        var keys=List.of("products/CODE/official/01.jpg","products/CODE/processed/상품정보.png","products/CODE/original/a.png","products/CODE/uuid/02.jpg","products/CODE/03.jpg","products/CODE/readme.txt");
        when(storage.list(StorageArea.PUBLIC,"products/CODE/",null,100)).thenReturn(new ObjectPage(keys.stream().map(k->meta(k,100)).toList(),"opaque-token"));
        var page=library.list(1L," code ",null);assertThat(page.productCode()).isEqualTo("CODE");assertThat(page.images()).extracting(i->i.folder()).containsExactly("official","processed","original","other","other");assertThat(page.images().get(1).url()).contains("%EC%83%81");
        when(storage.list(StorageArea.PUBLIC,"products/CODE/","opaque-token",100)).thenReturn(new ObjectPage(List.of(),null));assertThat(library.list(1L,"CODE",page.nextCursor()).nextCursor()).isNull();
        assertThatThrownBy(()->library.list(1L,"OTHER",page.nextCursor())).isInstanceOf(InputValidationFailure.class);
        verify(access,times(3)).productActor(1L,true);verify(storage,never()).create(any(),any(),any(),any(),any());
    }
    @Test void listsActualNestedFoldersIncludingNonImagesAndFolderMarkers(){
        var keys=List.of("products/CODE/ABC/하위/deep/photo.png","products/CODE/ABC/readme.txt","products/CODE/empty/","products/OTHER/foreign/a.jpg");
        when(storage.list(StorageArea.PUBLIC,"products/CODE/",null,100)).thenReturn(new ObjectPage(keys.stream().map(k->meta(k,100)).toList(),null));
        var page=library.list(1L,"CODE",null);
        assertThat(page.folders()).containsExactly("ABC","ABC/하위","ABC/하위/deep","empty");
        assertThat(page.images()).extracting(Entry->Entry.key()).containsExactly("products/CODE/ABC/하위/deep/photo.png");
    }
    @Test void resolvesActualBytesAndIgnoresClaimedContentType()throws Exception{
        String key="products/CODE/processed/상품정보.png";byte[] bytes=image();var metadata=meta(key,bytes.length);
        when(storage.head(StorageArea.PUBLIC,key)).thenReturn(Optional.of(metadata));when(storage.get(StorageArea.PUBLIC,key)).thenReturn(Optional.of(new StoredObject(metadata,bytes)));
        var value=library.resolve(1L,storage.publicUrl(StorageArea.PUBLIC,key).orElseThrow().toASCIIString());assertThat(value.width()).isEqualTo(600);assertThat(value.mimeType()).isEqualTo("image/png");assertThat(value.bytes()).isEqualTo(bytes.length);
        when(storage.get(StorageArea.PUBLIC,key)).thenReturn(Optional.of(new StoredObject(metadata,"not an image".getBytes())));assertThatThrownBy(()->library.resolve(1L,value.url())).isInstanceOf(InputValidationFailure.class);
    }
    @Test void rejectsWrongOriginsTraversalMissingObjectsAndBadCodesBeforeFetch(){
        for(String url:List.of("https://evil.test/products/CODE/a.jpg","https://assets.example.test/products/CODE/../a.jpg","https://assets.example.test/products/CODE/%2e%2e/a.jpg","https://assets.example.test/products/CODE/a.jpg?version=1","https://assets.example.test/products/CODE/%5ca.jpg","http://assets.example.test/products/CODE/a.jpg"))assertThatThrownBy(()->library.resolve(1L,url)).isInstanceOf(InputValidationFailure.class);
        verify(storage,never()).get(any(),any());
        when(storage.head(StorageArea.PUBLIC,"products/CODE/missing.jpg")).thenReturn(Optional.empty());assertThatThrownBy(()->library.resolve(1L,"https://assets.example.test/products/CODE/missing.jpg")).hasMessageContaining("삭제");
        for(String code:List.of("../CODE","ABC","A/B/C","CODE?"))assertThatThrownBy(()->library.list(1L,code,null)).isInstanceOf(InputValidationFailure.class);
    }
    @Test void unchangedCdnDescriptionsSurviveButLocalAndNewForeignImagesDoNot(){
        String old="<img src='https://old-market.test/image.jpg'>";library.validateDescription(1L,old,old);library.validateDescription(1L,old+"<p>텍스트 변경</p>",old);verify(storage,never()).get(any(),any());
        assertThatThrownBy(()->library.validateDescription(1L,"<img src='/api/coupang/assets/id'>","")).hasMessageContaining("다시 선택");
        assertThatThrownBy(()->library.validateDescription(1L,"<img src='https://foreign.test/image.jpg'>",old)).isInstanceOf(InputValidationFailure.class);
    }
}
