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
    record Content(String id, String type, String detailType, String content, String assetId) {
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
            Long appliedRevision) {
    }
    record Save(String sessionId, Long expectedRevision, Document document, List<CoupangEditing.Change> changes) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void unknown(String key, Object value) {
            throw new cc.ataglace.molebutter.common.api.InputValidationFailure("지원하지 않는 작업본 필드입니다: " + key);
        }
    }
    Entry open(Long actor, String productId, String workingCopyId);
    Entry save(Long actor, String workingCopyId, Save input);
    PageResponse<Summary> list(Long actor, String query, int page, int size);
}
