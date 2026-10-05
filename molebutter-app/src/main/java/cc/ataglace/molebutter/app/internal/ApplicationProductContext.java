package cc.ataglace.molebutter.app.internal;

import cc.ataglace.molebutter.common.api.BusinessTime;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
import cc.ataglace.molebutter.catalog.api.CatalogConsistencyGuard;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

/** Application coordination only; catalog and procurement own persistence and validation. */
@Component
@RequiredArgsConstructor
public class ApplicationProductContext {
    final BusinessTime time;
    private final ObjectMapper json;
    private final BusinessAccess access;
    private final CatalogConsistencyGuard guard;
    public String encode(Object value) { return json.writeValueAsString(value); }
    public void lock() { guard.exclusive(); }
    public void authorize(Long actor, boolean adminOnly) { access.productActor(actor, adminOnly); }
}
