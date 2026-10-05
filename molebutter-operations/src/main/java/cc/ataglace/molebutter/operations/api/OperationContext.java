package cc.ataglace.molebutter.operations.api;


import java.util.*;
import cc.ataglace.molebutter.identity.api.UserRole;

/** The executor and correlation of an attempt; contains no credentials or request payload. */
public record OperationContext(Source source, String operationId, Long actorId, UserRole actorRole, String actorEmail) {
    public enum Source { HTTP, BACKGROUND }
    public OperationContext {
        Objects.requireNonNull(source);
        operationId=UUID.fromString(operationId).toString();
        if(source==Source.HTTP) { Objects.requireNonNull(actorId);Objects.requireNonNull(actorRole);Objects.requireNonNull(actorEmail); }
    }
    public static OperationContext http(String operationId,Long actorId,UserRole role,String email) {
        return new OperationContext(Source.HTTP,operationId,actorId,role,email);
    }
    public static OperationContext background() {
        return new OperationContext(Source.BACKGROUND,UUID.randomUUID().toString(),null,null,null);
    }
}
