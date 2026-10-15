package cc.ataglace.molebutter.marketplaceelevenst.internal;

import java.nio.charset.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ElevenstReferenceDataTest {
    @TempDir Path temp;
    @Test void missingFileIsExplicitAndDoesNotInventManagedBrandCodes(){
        var page=new ElevenstReferenceData().brands("","닥스",1);
        assertThat(page.available()).isFalse();assertThat(page.items()).isEmpty();assertThat(page.warning()).contains("CSV","텍스트");
    }
    @Test void utf8BomQuotedCommaAndCp949PreserveBrandNames(){
        var text="\ufeffapiPrdAttrBrandCd,brand\r\n123,\"닥스, DAKS\"\r\n124,브랜드\r\n";
        assertThat(ElevenstReferenceData.parse(text.getBytes(StandardCharsets.UTF_8)).getFirst().name()).isEqualTo("닥스, DAKS");
        assertThat(ElevenstReferenceData.parse("브랜드코드,브랜드명\n123,닥스\n".getBytes(Charset.forName("MS949"))).getFirst().name()).isEqualTo("닥스");
    }
    @Test void duplicateConflictsInvalidColumnsAndTruncatedQuotesFailClosed(){
        for(String csv:new String[]{"x,y\n123,닥스", "brandCode,brandName\n123,닥스\n123,다름", "brandCode,brandName\n123,\"미완성", "brandCode,brandName\n1,닥스,여분"})
            assertThatThrownBy(()->ElevenstReferenceData.parse(csv.getBytes(StandardCharsets.UTF_8))).isInstanceOf(cc.ataglace.molebutter.common.api.InputValidationFailure.class);
    }
    @Test void searchesAllRowsPagesResultsAndReloadsChangedFile()throws Exception{
        Path file=temp.resolve("brands.csv");var text=new StringBuilder("brandCode,brandName\n");for(int n=1;n<=205;n++)text.append(n).append(",닥스 ").append(n).append('\n');Files.writeString(file,text);
        var data=new ElevenstReferenceData();var first=data.brands(file.toString(),"닥스",1);var last=data.brands(file.toString(),"닥스",3);
        assertThat(first.items()).hasSize(100);assertThat(first.hasNext()).isTrue();assertThat(last.items()).hasSize(5);assertThat(last.hasNext()).isFalse();
        assertThat(data.brands(file.toString(),"205",1).items().getFirst().id()).isEqualTo("205");
        Files.writeString(file,"brandCode,brandName\n999,갱신된 브랜드\n");assertThat(data.brands(file.toString(),"갱신",1).items().getFirst().id()).isEqualTo("999");
    }
    @Test void credentialDirectoryCannotBecomeABrandCatalog()throws Exception{
        Path secret=Files.createDirectories(temp.resolve("application-secret")).resolve("brands.csv");Files.writeString(secret,"brandCode,brandName\n1,닥스");
        assertThatThrownBy(()->new ElevenstReferenceData().brands(secret.toString(),"닥스",1)).hasMessageContaining("비밀 설정");
    }
    @Test void deliveryCodesKeepLeadingZerosAndExplicitOfficialLabels(){
        var rows=new ElevenstReferenceData().deliveryCompanies();assertThat(rows).anySatisfy(row->{assertThat(row.id()).isEqualTo("00034");assertThat(row.name()).isEqualTo("CJ대한통운");});
    }
}
