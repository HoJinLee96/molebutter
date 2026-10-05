package cc.ataglace.molebutter.operations.api;
import java.time.*;
import java.util.*;
import org.springframework.transaction.annotation.*;


public interface NotificationService {
    public record Notice(String id,String type,String severity,String title,String message,LocalDateTime occurredAt,boolean accessible) {}
    public record Inbox(List<Notice> items,String nextCursor) {}
    public record Summary(long count,String latestId) {}
    public record Target(String url,String reason) {}
    void publish(String key,String type,String severity,String title,String message,String target,Long targetId,Collection<Long> recipients);
    void correction(long correction,String state,long owner,Long reviewer,LocalDate date);
    Inbox list(long user,String cursor,int size);
    Summary summary(long user);
    Target target(long user,long id);
    void dismiss(long user,long id);
}
