package cc.ataglace.molebutter.marketplace.api;
import cc.ataglace.molebutter.common.api.InputValidationFailure;
/** A safe validation explanation tied to the editable setting rather than an image. */
public final class MarketplaceSettingsFailure extends InputValidationFailure {
    private final String path;
    public MarketplaceSettingsFailure(String path,String message){super(message);this.path=path;}
    public String path(){return path;}
}
