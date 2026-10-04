package cc.ataglace.molebutter.service.product;
import cc.ataglace.molebutter.exception.InputValidationFailure;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import cc.ataglace.molebutter.dto.product.ProductDtos.*;

/** 등록에 필요한 셀만 읽는다. 원본 파일이나 판매용 값은 저장하지 않는다. */
@Component
public class ProductRegistrationWorkbook {
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    public record Row(int index,String code,String title) {}
    public record Parsed(List<Row> rows,List<String> warnings) {}
    private record Archive(Document sheet,List<String> strings) {}
    public Parsed parse(byte[] bytes) {
        Archive a=archive(bytes);var source=rows(a.sheet());var header=source.get(3);
        if(header==null)throw invalid("3행 헤더를 찾지 못했습니다.");
        var h=values(header,a.strings());
        if(!h.get(5).replaceAll("\\s+", "").equals("업체상품코드")||!h.get(7).replaceAll("\\s+", "").equals("업체등록상품명")||!h.get(6).replaceAll("\\s+", "").equals("쿠팡노출상품명"))
            throw invalid("상품코드(F열)·상품명(G/H열)이 있는 기존 등록 양식을 사용해 주세요.");
        List<Row> result=new ArrayList<>();List<String> warnings=new ArrayList<>();
        for(var e:source.entrySet()) {
            if(e.getKey()<4)continue;
            var v=values(e.getValue(),a.strings());
            if(v.stream().allMatch(String::isBlank))continue;
            if(v.get(5).isBlank())warnings.add(e.getKey()+"행: 상품코드가 없어 제외했습니다.");
            result.add(new Row(e.getKey(),v.get(5),v.get(7).isBlank()?v.get(6):v.get(7)));
            if(result.size()>5000)throw invalid("한 번에 최대 5,000행을 등록할 수 있습니다.");
        }
        return new Parsed(List.copyOf(result),List.copyOf(warnings));
    }
    private Archive archive(byte[] bytes) {
        if(bytes==null||bytes.length==0||bytes.length>10*1024*1024) throw invalid("파일은 10MB 이하의 XLSX여야 합니다.");
        Map<String,byte[]> files=new LinkedHashMap<>();
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;int total=0;
            while((e=zip.getNextEntry())!=null) {
                if(e.isDirectory()) continue;
                byte[] data=zip.readNBytes(30*1024*1024+1);total+=data.length;
                if(data.length>30*1024*1024||total>100*1024*1024||files.size()>=1000||files.containsKey(e.getName())) throw invalid("압축된 파일의 크기 또는 구조가 허용 범위를 넘었습니다.");
                files.put(e.getName(),data);
            }
            if(files.keySet().stream().anyMatch(k->k.contains("vbaProject")||k.startsWith("xl/externalLinks/"))) throw invalid("매크로와 외부 연결이 없는 XLSX를 사용해 주세요.");
            Document book=doc(files.get("xl/workbook.xml"));String rel=null;
            for(Element sheet:elements(book.getDocumentElement(),"sheet")) if(sheet.getAttribute("name").equals("data")) rel=sheet.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id");
            if(rel==null) throw invalid("data 시트가 없습니다.");
            Document relations=doc(files.get("xl/_rels/workbook.xml.rels"));String path=null;
            NodeList rs=relations.getDocumentElement().getChildNodes();
            for(int i=0;i<rs.getLength();i++) if(rs.item(i) instanceof Element r && r.getAttribute("Id").equals(rel)) {
                String target=r.getAttribute("Target");path=target.startsWith("/")?target.substring(1):"xl/"+target;
            }
            if(path==null||!files.containsKey(path)) throw invalid("data 시트 연결이 올바르지 않습니다.");
            List<String> strings=new ArrayList<>();
            if(files.containsKey("xl/sharedStrings.xml")) for(Element si:elements(doc(files.get("xl/sharedStrings.xml")).getDocumentElement(),"si")) strings.add(texts(si));
            return new Archive(doc(files.get(path)),strings);
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw invalid("지원하는 XLSX 등록 파일을 읽을 수 없습니다.");}
    }
    private static Map<Integer,Element> rows(Document d) {
        Map<Integer,Element> result=new TreeMap<>();
        for(Element r:elements(d.getDocumentElement(),"row")) {
            int index=Integer.parseInt(r.getAttribute("r"));
            if(index<1||index>1048576||result.putIfAbsent(index,r)!=null)throw invalid("원본 행 번호가 중복되거나 올바르지 않습니다.");
        }
        return result;
    }
    private static List<String> values(Element row,List<String> strings) {
        List<String> result=new ArrayList<>(Collections.nCopies(19,""));
        Set<String> cells=new HashSet<>();
        for(Element c:elements(row,"c")) {
            String address=c.getAttribute("r");
            if(!address.matches("[A-Z]{1,3}"+row.getAttribute("r"))||!cells.add(address))throw invalid("셀 주소가 중복되거나 행 번호와 맞지 않습니다.");
            String ref=address.replaceAll("[0-9]","");if(ref.length()!=1||ref.charAt(0)>'S') continue;
            if(c.getElementsByTagNameNS(NS,"f").getLength()>0) { if(List.of("F","G","H").contains(ref))throw invalid("상품코드·등록명에는 수식 대신 값을 입력해 주세요."); else continue; }
            String type=c.getAttribute("t"),value="";NodeList vs=c.getElementsByTagNameNS(NS,"v");
            if(type.equals("inlineStr")) value=texts(c);
            else if(vs.getLength()>0) {value=vs.item(0).getTextContent();if(type.equals("s")){int index=Integer.parseInt(value);if(index<0||index>=strings.size())throw invalid("문자열 셀의 참조가 올바르지 않습니다.");value=strings.get(index);}}
            result.set(ref.charAt(0)-'A',value.trim());
        }
        return result;
    }
    private static String texts(Element e) {StringBuilder s=new StringBuilder();for(Element t:elements(e,"t"))s.append(t.getTextContent());return s.toString();}
    private static List<Element> elements(Element e,String name) {NodeList ns=e.getElementsByTagNameNS(NS,name);List<Element> r=new ArrayList<>();for(int i=0;i<ns.getLength();i++)r.add((Element)ns.item(i));return r;}
    private static Document doc(byte[] data) throws Exception {
        if(data==null)throw invalid("엑셀 내부 파일이 없습니다.");
        var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(data));
    }
    private static IllegalArgumentException invalid(String message) {return new InputValidationFailure(message);}
}
