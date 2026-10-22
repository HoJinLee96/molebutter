package cc.ataglace.molebutter.coupang.api;
/** Projects a saved, validated response without HTTP. */
public interface CoupangProductSource {
    CoupangEditor.EditorDocument project(String raw, java.util.Map<String,CoupangCatalog.CurrentInventory> current,
                                         java.util.Map<String,String> errors);
}
