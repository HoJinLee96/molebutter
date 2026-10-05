package cc.ataglace.molebutter.identity.api;
import java.time.LocalDate;
import cc.ataglace.molebutter.identity.api.UserAuthEventType;
import cc.ataglace.molebutter.common.api.PageResponse;
import cc.ataglace.molebutter.identity.api.AuthLogResponse;
public interface AuthLogQueryService {
    PageResponse<AuthLogResponse> search(LocalDate from, LocalDate to, UserAuthEventType eventType,
            String email, String ip, int page, int size);
}
