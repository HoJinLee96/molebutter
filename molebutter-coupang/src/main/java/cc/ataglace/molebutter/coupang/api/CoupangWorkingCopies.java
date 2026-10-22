package cc.ataglace.molebutter.coupang.api;

import java.util.*;
import cc.ataglace.molebutter.common.api.PageResponse;

/** Locally saved Coupang inputs. Saving never dispatches a seller API write. */
public interface CoupangWorkingCopies {
    record Image(String id, String assetId, String url, String type, int order) {
        public String imageType() {
            return type == null ? "DETAIL" : type;
        }
        public boolean representative() {
            return "REPRESENTATION".equals(imageType());
        }
    }
    record Content(String id, String type, String detailType, String content, String assetId,
                   @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String purpose) {
        public Content(String id,String type,String detailType,String content,String assetId){this(id,type,detailType,content,assetId,null);}
    }
    record Option(String id, String itemName, Map<String, String> registration,
            List<CoupangCatalog.Attribute> attributes, List<Image> images, List<Content> contents,
            List<CoupangCatalog.Notice> notices, List<CoupangCatalog.Certification> certifications,
            String noticeCategory) {
    }
    record Document(Map<String, String> basic, List<Option> options, Map<String, String> delivery,
            Map<String, String> settings, List<CoupangEditor.Document> documents) {
    }
    record Issue(String path, String message) {
    }
    record Summary(String id, long revision, String name, String externalProductId, String mode, String updatedAt,
            boolean blocked) {
    }
    record Entry(String mode, String workingCopyId, Long revision, String productId, Document document,
            String sessionId, String expiresAt, CoupangEditor.EditorDocument observation, boolean blocked,
            List<CoupangEditing.Change> changes, String accountKey, Document recoveryBase, String recoveryMessage,
            Long appliedRevision,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) CoupangEditor.CategoryRules categoryRules) {
        public Entry(String mode,String workingCopyId,Long revision,String productId,Document document,
                String sessionId,String expiresAt,CoupangEditor.EditorDocument observation,boolean blocked,
                List<CoupangEditing.Change> changes,String accountKey,Document recoveryBase,String recoveryMessage,Long appliedRevision) {
            this(mode,workingCopyId,revision,productId,document,sessionId,expiresAt,observation,blocked,changes,accountKey,recoveryBase,recoveryMessage,appliedRevision,null);
        }
    }
    record Save(String sessionId, Long expectedRevision, Document document, List<CoupangEditing.Change> changes) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void unknown(String key, Object value) {
            throw new cc.ataglace.molebutter.common.api.InputValidationFailure("지원하지 않는 작업본 필드입니다: " + key);
        }
    }
    Entry open(Long actor, String productId, String workingCopyId);
    /** Reuse already persisted provider observations after submission completion. */
    default Entry openObserved(Long actor, String workingCopyId) { throw new UnsupportedOperationException(); }
    default Entry prefill(Long actor, Document document) { throw new UnsupportedOperationException(); }
    Entry save(Long actor, String workingCopyId, Save input);
    default PageResponse<Summary> list(Long actor,String query,int page,int size){return list(actor,query,null,page,size);}
    PageResponse<Summary> list(Long actor, String query, String mode, int page, int size);
}
