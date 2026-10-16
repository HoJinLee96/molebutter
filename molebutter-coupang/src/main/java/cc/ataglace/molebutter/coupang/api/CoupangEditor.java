package cc.ataglace.molebutter.coupang.api;

import java.util.List;
import cc.ataglace.molebutter.coupang.api.CoupangCatalog.*;

/** Read-only inputs for the editor; never a Coupang write request. */
public interface CoupangEditor {
    EditorDocument edit(Long actor, String productId);
    CategoryRules category(Long actor, String categoryCode);
    default EditorDocument edit(Long actor,String id,String requestId){return edit(actor,id);}
    default CategoryRules category(Long actor,String code,String requestId){return category(actor,code);}
    List<CategoryChoice> categories(Long actor,String requestId);
    record CategoryChoice(String id,String name) {}
    record Basic(String sellerProductId, String productId, String sellerProductName,
                 String displayProductName, String generalProductName, String brand,
                 String productGroup, String displayCategoryCode, String statusName) {}
    record Limits(boolean categoryReadOnly, boolean optionStructureReadOnly, boolean purchaseAttributesReadOnly) {}
    record AttributePermission(String name, String exposed, Boolean editable) {}
    record EditOption(String sellerProductItemId, String vendorItemId, String itemName,
                      CurrentInventory current, String currentError, boolean separateCurrentChanges,
                      List<Attribute> attributes, List<Image> images, List<Content> contents,
                      List<Notice> notices, List<Field> registration, List<Certification> certifications,
                      List<AttributePermission> attributePermissions) {
        public EditOption(String sellerProductItemId, String vendorItemId, String itemName,
                          CurrentInventory current, String currentError, boolean separateCurrentChanges,
                          List<Attribute> attributes, List<Image> images, List<Content> contents,
                          List<Notice> notices, List<Field> registration, List<Certification> certifications) {
            this(sellerProductItemId,vendorItemId,itemName,current,currentError,separateCurrentChanges,
                 attributes,images,contents,notices,registration,certifications,List.of());
        }
    }
    record Document(String templateName, String path, String vendorPath) {}
    record EditorDocument(Basic basic, Limits limits, List<EditOption> options,
                          List<Field> delivery, List<Field> settings, List<Document> documents) {}
    record AttributeRule(String name, String exposed, String required, String groupNumber,
                         String dataType, String basicUnit, List<String> usableUnits) {}
    record NoticeRule(String name, String required) {}
    record NoticeCategory(String name, List<NoticeRule> fields) {}
    record CertificateRule(String type, String name, String required, String dataType) {}
    record DocumentRule(String name, String required) {}
    record CategoryRules(String categoryCode, Boolean allowSingleItem, List<AttributeRule> attributes,
                         List<NoticeCategory> notices, List<CertificateRule> certifications,
                         List<DocumentRule> documents, List<String> allowedOfferConditions) {}
}
