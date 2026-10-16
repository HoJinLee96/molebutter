package cc.ataglace.molebutter.coupang.api;
import java.util.List;
/** Server-owned observations and explicitly edited, UUID-scoped fields. */
public interface CoupangEditing {
    record Change(String path, String optionId, Object value,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<String> rowKey,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) String operation) {
        // V47 and immutable historical evidence retain their three-field shape.
        public Change(String path, String optionId, Object value) {
            this(path, optionId, value, null, null);
        }
        public String action() {
            return operation == null ? "SET" : operation;
        }
    }
    record Session(String id, String workingCopyId, long revision, String expiresAt, String mode,
            CoupangWorkingCopies.Document document, CoupangEditor.EditorDocument observation,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<Change> pendingChanges) {
        public Session(String id, String workingCopyId, long revision, String expiresAt, String mode,
                CoupangWorkingCopies.Document document, CoupangEditor.EditorDocument observation) {
            this(id, workingCopyId, revision, expiresAt, mode, document, observation, null);
        }
    }
    record PrepareRequest(String workingCopyId, Long revision, String sessionId, List<Change> changes) {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void unknown(String key, Object value) {
            throw new cc.ataglace.molebutter.common.api.InputValidationFailure("지원하지 않는 쿠팡 전송 필드입니다: " + key);
        }
    }
}
