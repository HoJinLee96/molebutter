package cc.ataglace.molebutter.catalog.api;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.w3c.dom.*;
public interface ProductRegistrationWorkbook {
    public record Row(int index,String code,String title) {}
    public record Parsed(List<Row> rows,List<String> warnings) {}
    Parsed parse(byte[] bytes);
}
