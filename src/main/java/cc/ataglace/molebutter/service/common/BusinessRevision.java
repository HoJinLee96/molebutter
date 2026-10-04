package cc.ataglace.molebutter.service.common;
import cc.ataglace.molebutter.exception.*;
public final class BusinessRevision {
    private BusinessRevision() {}
    public static void check(long current, Long requested) {
        if(requested==null) throw new InputValidationFailure("수정 버전을 확인해 주세요.");
        if(current!=requested) throw new OperationFailure("다른 작업에서 변경되었습니다. 새로 조회해 주세요.");
    }
}
