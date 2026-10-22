package cc.ataglace.molebutter.coupang.internal.client;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import cc.ataglace.molebutter.coupang.api.*;
@Component
final class DefaultCoupangProductSource implements CoupangProductSource {
    private final CoupangProductClient client;private final ObjectMapper json;
    DefaultCoupangProductSource(CoupangProductClient client,ObjectMapper json){this.client=client;this.json=json;}
    public CoupangEditor.EditorDocument project(String raw,java.util.Map<String,CoupangCatalog.CurrentInventory> current,java.util.Map<String,String> errors){return client.editorDocument(json.readTree(raw),current,errors);}
}
