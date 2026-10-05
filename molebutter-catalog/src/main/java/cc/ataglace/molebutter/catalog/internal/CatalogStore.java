package cc.ataglace.molebutter.catalog.internal;

import cc.ataglace.molebutter.catalog.api.CatalogConsistencyGuard;
import cc.ataglace.molebutter.common.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class CatalogStore {
    final JdbcTemplate jdbc;
    final BusinessTime time;
    private final BusinessAccess access;
    private final CatalogConsistencyGuard guard;
    public static long id() { return BusinessIds.next(); }
    public void lock() { guard.exclusive(); }
    public void authorize(Long actor, boolean adminOnly) { access.productActor(actor, adminOnly); }
    public static void revision(long current, Long requested) { BusinessRevision.check(current, requested); }
    public static String text(String value, int max, boolean required) {
        return BusinessText.checked(value, max, required, "필수 입력값과 입력 길이를 확인해 주세요.");
    }
}
