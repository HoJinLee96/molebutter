package cc.ataglace.molebutter.media.internal;
import cc.ataglace.molebutter.media.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ServerImageInspectionTest {
 @Test void comparisonReadsOneFilePerReferenceWithoutPublication()throws Exception{
  var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(750,750,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);var bytes=out.toByteArray();var r2=mock(R2Images.class);var assets=mock(ImageAssets.class);String url="https://assets.test/original";when(r2.read(1L,url)).thenReturn(new R2Images.Content(new R2Images.Image("key",url,"image/png",750,750,bytes.length),bytes));
  var checks=new ServerImageInspection(r2,assets,mock(BusinessAccess.class)).fingerprints(1L,List.of(new ImageInspection.Input("COUPANG","REPRESENTATION","option","one",url,null,true),new ImageInspection.Input("COUPANG","DESCRIPTION","option","two",url,null,true)));
  assertThat(checks).hasSize(1);assertThat(checks.getFirst().sha256()).hasSize(64);assertThat(checks.getFirst().pixels()).hasSize(64);verify(r2,times(1)).read(1L,url);verifyNoInteractions(assets);
 }
 @Test void retainedWebpDecoderReadsActualPixelsButNewUploadsStillRejectWebp()throws Exception{byte[] bytes;try(var file=getClass().getResourceAsStream("/elevenst-retained.webp")){bytes=file.readAllBytes();}var decoded=LocalImageAssets.inspect(bytes,true);assertThat(decoded.mimeType()).isEqualTo("image/webp");assertThat(decoded.width()).isEqualTo(2);assertThat(decoded.height()).isEqualTo(3);assertThatThrownBy(()->LocalImageAssets.inspect(bytes)).isInstanceOf(InputValidationFailure.class);}

 @Test void elevenstRetainedWebpAndDescriptionHaveTheirOwnPolicy(){assertThat(ImageRules.errors("ELEVENST","REPRESENTATION","image/webp",600,750,1000,true)).isEmpty();assertThat(ImageRules.errors("ELEVENST","REPRESENTATION","image/webp",600,750,1000,false)).isNotEmpty();assertThat(ImageRules.errors("ELEVENST","REPRESENTATION","image/jpeg",599,750,1000,true)).isNotEmpty();assertThat(ImageRules.errors("ELEVENST","DESCRIPTION","image/png",100,100,1000)).isEmpty();}
 @Test void onePortraitFileIsCheckedOnceButByEachRole(){
  var r2=mock(R2Images.class);var service=new ServerImageInspection(r2,mock(ImageAssets.class),mock(BusinessAccess.class));String url="https://assets.example.test/products/CODE/official/01.jpg";
  when(r2.resolve(1L,url)).thenReturn(new R2Images.Image("key",url,"image/jpeg",750,962,88040));
  var checks=service.inspect(1L,List.of(new ImageInspection.Input("COUPANG","REPRESENTATION","option","one",url,null,false),new ImageInspection.Input("COUPANG","DESCRIPTION","option","two",url,null,false),new ImageInspection.Input("NAVER","REPRESENTATION",null,"three",url,null,false)));
  assertThat(checks).extracting(ImageInspection.Check::status).containsExactly("FAIL","PASS","PASS");assertThat(checks.get(2).warnings()).hasSize(1);verify(r2,times(1)).resolve(1L,url);
 }
 @Test void missingObjectIsUnverifiedRatherThanAValidFormat(){var r2=mock(R2Images.class);when(r2.resolve(1L,"url")).thenThrow(new InputValidationFailure("이미지가 삭제되었습니다."));var checks=new ServerImageInspection(r2,mock(ImageAssets.class),mock(BusinessAccess.class)).inspect(1L,List.of(new ImageInspection.Input("COUPANG","DETAIL",null,"one","url",null,false)));assertThat(checks.getFirst().status()).isEqualTo("UNVERIFIED");}
 @Test void untrustedLocalFilesAreNeverPublished(){var assets=mock(ImageAssets.class);when(assets.editedMetadata(1L,"legacy")).thenThrow(new InputValidationFailure("기존 로컬 이미지"));var checks=new ServerImageInspection(mock(R2Images.class),assets,mock(BusinessAccess.class)).inspect(1L,List.of(new ImageInspection.Input("COUPANG","REPRESENTATION",null,"one","/api/marketplaces/assets/legacy","legacy",false)));assertThat(checks.getFirst().status()).isEqualTo("UNVERIFIED");verify(assets,never()).submissionUrl(any(),any());}
 @Test void squareAndBoundaryRulesAreSharedAndDescriptionsRemainIndependent(){assertThat(ImageInspection.descriptionInputs("NAVER","<p style=\"background:#fff\">본문</p>","")).isEmpty();assertThatThrownBy(()->ImageInspection.descriptionInputs("NAVER","<p style=\"background-image:url(https://assets.test/a)\">본문</p>","")).isInstanceOf(InputValidationFailure.class);assertThat(ImageRules.errors("COUPANG","REPRESENTATION","image/png",750,750,88040)).isEmpty();assertThat(ImageRules.errors("COUPANG","DETAIL","image/png",499,499,100)).isNotEmpty();assertThat(ImageRules.errors("COUPANG","DESCRIPTION","image/png",750,962,88040)).isEmpty();assertThat(ImageRules.errors("COUPANG","DETAIL","image/png",5000,5000,3*1024*1024)).isEmpty();assertThat(ImageRules.errors("COUPANG","DETAIL","image/png",5001,5001,100)).isNotEmpty();}

 @Test void unavailablePublicCropDoesNotBlockNaverFileUploadValidation()throws Exception{
  var buffer=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(750,750,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",buffer);byte[] bytes=buffer.toByteArray();var assets=mock(ImageAssets.class);String id="crop",url="/api/marketplaces/assets/crop";var metadata=new ImageAssets.Asset(id,url,"image/png",750,750,bytes.length);
  when(assets.readEdited(1L,id)).thenReturn(new ImageAssets.AssetContent(metadata,bytes));when(assets.submissionUrl(1L,id)).thenThrow(new InputValidationFailure("공개 HTTPS 주소가 없습니다."));
  var checks=new ServerImageInspection(mock(R2Images.class),assets,mock(BusinessAccess.class)).inspect(1L,List.of(new ImageInspection.Input("COUPANG","REPRESENTATION",null,"cp",url,id,false),new ImageInspection.Input("NAVER","REPRESENTATION",null,"nv",url,id,false)));
  assertThat(checks).extracting(ImageInspection.Check::status).containsExactly("UNVERIFIED","PASS");verify(assets,times(1)).readEdited(1L,id);
 }

 @Test void cachedExistingCdnBytesNeverAuthorizeANewImageReference()throws Exception{
  var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(750,750,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);var r2=mock(R2Images.class);String url="https://img1a.coupangcdn.com/image/existing.png";when(r2.resolve(1L,url)).thenThrow(new InputValidationFailure("R2 원본이 아닙니다."));
  var http=mock(java.net.http.HttpClient.class);var response=mock(java.net.http.HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(new java.io.ByteArrayInputStream(out.toByteArray()));doReturn(response).when(http).send(any(),any());
  var address=java.net.InetAddress.getByAddress(new byte[]{8,8,8,8});try(var dns=mockStatic(java.net.InetAddress.class)){dns.when(()->java.net.InetAddress.getAllByName("img1a.coupangcdn.com")).thenReturn(new java.net.InetAddress[]{address});
   var service=new ServerImageInspection(r2,mock(ImageAssets.class),mock(BusinessAccess.class),http);var checks=service.inspect(1L,List.of(new ImageInspection.Input("COUPANG","REPRESENTATION",null,"existing",url,null,true),new ImageInspection.Input("COUPANG","DETAIL",null,"new",url,null,false)));
   assertThat(checks).extracting(ImageInspection.Check::status).containsExactly("PASS","UNVERIFIED");verify(http,times(1)).send(any(),any());
  }
 }
}
