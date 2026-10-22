package cc.ataglace.molebutter.coupang.internal;
import org.springframework.stereotype.Service;
import cc.ataglace.molebutter.coupang.api.*;
import cc.ataglace.molebutter.identity.api.BusinessAccess;
@Service
public final class DefaultCoupangBrandLogos implements CoupangBrandLogos {
    private final CoupangRegistrationDefaults defaults;private final BusinessAccess access;
    public DefaultCoupangBrandLogos(CoupangRegistrationDefaults defaults,BusinessAccess access){this.defaults=defaults;this.access=access;}
    public Result select(Long actor,Input input){access.productActor(actor,true);return CoupangBrandLogos.match(input==null?null:input.title(),defaults.get(actor));}
}
