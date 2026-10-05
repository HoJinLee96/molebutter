package cc.ataglace.molebutter.catalog.internal;
import cc.ataglace.molebutter.common.fixture.WorkbookFixture;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import cc.ataglace.molebutter.catalog.internal.DefaultProductRegistrationWorkbook;

class ProductCoreTest {
    final DefaultProductRegistrationWorkbook excel=new DefaultProductRegistrationWorkbook();
    @ParameterizedTest @ValueSource(ints={286,1500,1501,5000}) void readsEveryRowBeyondWrongDimension(int n) {
        var result=excel.parse(WorkbookFixture.create(n));assertThat(result.rows()).hasSize(n);assertThat(result.rows().getLast().index()).isEqualTo(n+3);assertThat(result.warnings()).hasSize(1);
    }
    @Test void onlyCodeAndRegistrationNameMatterAndLeadingZerosRemain() {
        var p=excel.parse(WorkbookFixture.create(2,Map.of("F4","00012345678901234567890","F5","00012345678901234567890","H4","헤지스 가방","H5","","G5","닥스 가방","C4","","C5","","O4","승인대기","P4","50000","Q4","123")));
        assertThat(p.rows()).hasSize(2);assertThat(p.rows().getFirst().code()).isEqualTo("00012345678901234567890");assertThat(p.rows().getLast().title()).isEqualTo("닥스 가방");assertThat(p.warnings()).isEmpty();
    }
    @Test void ignoresSalesFormulasButRejectsRegistrationFormulasAndUnsafeXml() {
        var files=WorkbookFixture.unzip(WorkbookFixture.create(1,Map.of("F4","ABCD6F123BK")));String sheet=new String(files.get("xl/worksheets/sheet1.xml"),StandardCharsets.UTF_8);
        files.put("xl/worksheets/sheet1.xml",sheet.replace("<c r=\"P4\" t=\"inlineStr\">","<c r=\"P4\" t=\"inlineStr\"><f>1+1</f>").getBytes(StandardCharsets.UTF_8));assertThat(excel.parse(WorkbookFixture.zip(files)).rows()).hasSize(1);
        files.put("xl/worksheets/sheet1.xml",sheet.replace("<c r=\"F4\" t=\"inlineStr\">","<c r=\"F4\" t=\"inlineStr\"><f>1+1</f>").getBytes(StandardCharsets.UTF_8));assertThatThrownBy(()->excel.parse(WorkbookFixture.zip(files))).hasMessageContaining("수식");
        files.put("xl/worksheets/sheet1.xml",("<!DOCTYPE x [<!ENTITY xx SYSTEM 'file:///etc/passwd'>]>"+sheet).getBytes(StandardCharsets.UTF_8));assertThatThrownBy(()->excel.parse(WorkbookFixture.zip(files))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void refusesOversizedAndExternalWorkbooks(){assertThatThrownBy(()->excel.parse(WorkbookFixture.create(5001))).hasMessageContaining("5,000");var f=WorkbookFixture.unzip(WorkbookFixture.create(1));f.put("xl/externalLinks/externalLink1.xml",new byte[0]);assertThatThrownBy(()->excel.parse(WorkbookFixture.zip(f))).hasMessageContaining("외부 연결");}
    @Test void rejectsDuplicateCellsAndWrongRowReferences(){var f=WorkbookFixture.unzip(WorkbookFixture.create(1));String sheet=new String(f.get("xl/worksheets/sheet1.xml"),StandardCharsets.UTF_8);for(String bad:List.of(sheet.replace("r=\"G4\"","r=\"F4\""),sheet.replace("r=\"F4\"","r=\"F99\""))){f.put("xl/worksheets/sheet1.xml",bad.getBytes(StandardCharsets.UTF_8));assertThatThrownBy(()->excel.parse(WorkbookFixture.zip(f))).hasMessageContaining("셀 주소");}}
}
