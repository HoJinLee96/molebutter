package cc.ataglace.molebutter.common.fixture;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Synthetic business data; the user's workbook is never committed. */
public final class WorkbookFixture {
    public static byte[] create(int count) {return create(count,Map.of());}
    public static byte[] create(int count,Map<String,String> overrides) {
        String[] headers={"업체상품 ID","Product ID","옵션 ID","상품상태","바코드","업체상품코드","쿠팡 노출 상품명","업체 등록 상품명","등록 옵션명","판매가격","할인율기준가","판매상태","잔여수량(재고)","판매수량","승인상태","판매가격","할인율기준가","판매상태","잔여수량"};
        StringBuilder sheet=new StringBuilder("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><dimension ref=\"A1:S3\"/><sheetData><row r=\"1\">");
        sheet.append(cell("A1","가격은 10원 단위, 최대 1500개")).append("</row><row r=\"2\">").append(cell("A2","현재 상품 정보")).append(cell("P2","수정 요청")).append("</row><row r=\"3\">");
        for(int c=0;c<19;c++)sheet.append(cell(""+(char)('A'+c)+3,headers[c]));sheet.append("</row>");
        for(int n=0;n<count;n++) {
            int r=n+4;String[] values={"1234567890123456789","1",Long.toString(9007199254740993L+n),"정상","","SAME-CODE","테스트 상품","테스트 상품","블랙 / "+n,"100000","","판매중","5","1","승인완료","","","",""};
            if(n==count-1)values[5]="";
            sheet.append("<row r=\"").append(r).append("\" ht=\"30\" customHeight=\"1\">");
            for(int c=0;c<19;c++){String ref=""+(char)('A'+c)+r;sheet.append(cell(ref,overrides.getOrDefault(ref,values[c])));}
            sheet.append("</row>");
        }
        sheet.append("</sheetData><mergeCells count=\"3\"><mergeCell ref=\"A1:S1\"/><mergeCell ref=\"A2:O2\"/><mergeCell ref=\"P2:S2\"/></mergeCells></worksheet>");
        Map<String,byte[]> files=new LinkedHashMap<>();
        put(files,"[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"xml\" ContentType=\"application/xml\"/><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
        put(files,"_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        put(files,"xl/workbook.xml","<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"data\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        put(files,"xl/_rels/workbook.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
        put(files,"xl/worksheets/sheet1.xml",sheet.toString());return zip(files);
    }
    static String cell(String ref,String value){return "<c r=\""+ref+"\" t=\"inlineStr\"><is><t>"+value.replace("&","&amp;").replace("<","&lt;")+"</t></is></c>";}
    static void put(Map<String,byte[]> files,String name,String text){files.put(name,text.getBytes(StandardCharsets.UTF_8));}
    public static Map<String,byte[]> unzip(byte[] bytes){try(var z=new ZipInputStream(new ByteArrayInputStream(bytes))){Map<String,byte[]> out=new LinkedHashMap<>();ZipEntry e;while((e=z.getNextEntry())!=null)out.put(e.getName(),z.readAllBytes());return out;}catch(IOException e){throw new UncheckedIOException(e);}}
    public static byte[] zip(Map<String,byte[]> files){try{var out=new ByteArrayOutputStream();try(var z=new ZipOutputStream(out)){for(var e:files.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}}return out.toByteArray();}catch(IOException e){throw new UncheckedIOException(e);}}
}
