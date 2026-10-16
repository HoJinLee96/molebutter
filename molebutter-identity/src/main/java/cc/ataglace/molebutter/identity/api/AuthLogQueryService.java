package cc.ataglace.molebutter.identity.api;
import java.time.LocalDate;
import cc.ataglace.molebutter.common.api.PageResponse;
public interface AuthLogQueryService {
    PageResponse<AuthLogResponse> search(LocalDate from, LocalDate to, UserAuthEventType eventType,
            String email, String ip, int page, int size);
}
