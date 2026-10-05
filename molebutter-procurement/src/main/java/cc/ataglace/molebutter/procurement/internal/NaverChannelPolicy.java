package cc.ataglace.molebutter.procurement.internal;
import cc.ataglace.molebutter.procurement.api.ProductDtos.*;
import cc.ataglace.molebutter.procurement.internal.NaverSearchPayload;
import cc.ataglace.molebutter.procurement.internal.SupplierMetadataParser;
import cc.ataglace.molebutter.procurement.internal.ProductSourceMetadata;

import java.net.URI;
import java.util.*;
import tools.jackson.databind.JsonNode;

import static cc.ataglace.molebutter.procurement.api.ProductDtos.NaverChannelType.*;

/**
 * Channel capability is independent of merchant identity and common
 * preferences.
 */
public final class NaverChannelPolicy {
    private NaverChannelPolicy() {
    }

    public static NaverChannel unknown() {
        return new NaverChannel(UNKNOWN, null);
    }

    public static NaverChannel fromUrl(String url) {
        try {
            URI u = URI.create(url);
            if (!ProductSourceMetadata.isWebUrl(u) || u.getPort() != -1)
                return unknown();
            String host = u.getHost().toLowerCase(Locale.ROOT), path = u.getPath();
            if (host.equals("smartstore.naver.com") || host.endsWith(".smartstore.naver.com"))
                return new NaverChannel(SMARTSTORE, null);
            if (host.equals("shopping.naver.com")) {
                if (path.matches("/window-products/department/[0-9]+/?"))
                    return new NaverChannel(WINDOW, "DEPARTMENT");
                if (path.matches("/window-products/brandfashion/[0-9]+/?"))
                    return new NaverChannel(WINDOW, "BRAND_FASHION");
            }
        } catch (RuntimeException ignored) {
        }
        return unknown();
    }

    public static NaverChannel response(JsonNode root, String id) {
        if (!SupplierMetadataParser.naverMatches(root, id))
            return unknown();
        var body = root.path("contents").isObject() ? root.path("contents") : root;
        String service = NaverSearchPayload.text(body, "channelServiceType");
        String outer = NaverSearchPayload.text(root, "channelServiceType");
        if (!outer.isBlank() && !service.isBlank() && !outer.equals(service))
            return new NaverChannel(CONFLICT, null);
        if (service.isBlank())
            service = outer;
        String vertical = NaverSearchPayload.text(root.path("channel"), "verticalType");
        String nested = NaverSearchPayload.text(body.path("channel"), "verticalType");
        if (!nested.isBlank() && !vertical.isBlank() && !nested.equals(vertical))
            return new NaverChannel(CONFLICT, null);
        if (vertical.isBlank())
            vertical = nested;
        return new NaverChannel("WINDOW".equals(service) ? WINDOW : "STOREFARM".equals(service) ? SMARTSTORE : UNKNOWN,
                vertical.isBlank() ? null : vertical);
    }

    public static NaverChannel merge(NaverChannel a, NaverChannel b) {
        if (a == null)
            a = unknown();
        if (b == null)
            b = unknown();
        if (a.type() == CONFLICT || b.type() == CONFLICT)
            return new NaverChannel(CONFLICT, null);
        if (a.type() == UNKNOWN)
            return b;
        if (b.type() == UNKNOWN)
            return a;
        if (a.type() != b.type() || a.vertical() != null && b.vertical() != null && !a.vertical().equals(b.vertical()))
            return new NaverChannel(CONFLICT, null);
        return new NaverChannel(a.type(), b.vertical() != null ? b.vertical() : a.vertical());
    }

    public static boolean probeAllowed(Offer offer) {
        var c = merge(fromUrl(offer.url()), offer.naverChannel());
        if (offer.mall() == ProcurementMall.NAVER_SMART_STORE) {
            String linked = ProductSourceMetadata.productId(offer.mall(), offer.url(), "");
            if (!linked.isBlank() && !linked.equals(offer.mallProductId()))
                return false;
        }
        return offer.mall() != ProcurementMall.NAVER_SMART_STORE || c.type() != SMARTSTORE && c.type() != CONFLICT;
    }

    public static NaverChannel inspected(Offer offer, SourceDetails detail) {
        var evidence = detail.naverChannel();
        // New responses must explicitly confirm WINDOW; URL-only fallback is for stored
        // legacy results only.
        if (evidence == null || evidence.type() == UNKNOWN)
            return unknown();
        return merge(merge(fromUrl(offer.url()), offer.naverChannel()), evidence);
    }

    public static boolean comparable(Offer offer) {
        if (offer == null)
            return false;
        if (offer.mall() != ProcurementMall.NAVER_SMART_STORE)
            return true;
        if (!probeAllowed(offer))
            return false;
        // Missing legacy field may use an unambiguous Window URL; explicit UNKNOWN
        // never does.
        var c = offer.naverChannel() == null ? fromUrl(offer.url()) : offer.naverChannel();
        return c.type() == WINDOW && merge(c, fromUrl(offer.url())).type() == WINDOW;
    }
}
