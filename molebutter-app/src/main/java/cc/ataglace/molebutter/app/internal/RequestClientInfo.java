package cc.ataglace.molebutter.app.internal;


import jakarta.servlet.http.HttpServletRequest;

public final class RequestClientInfo {
    private RequestClientInfo() {}

    public static cc.ataglace.molebutter.identity.api.ClientInfo from(HttpServletRequest request) {
        if (request == null) {
            return new cc.ataglace.molebutter.identity.api.ClientInfo(null, null);
        }
        return new cc.ataglace.molebutter.identity.api.ClientInfo(resolveIp(request), request.getHeader("User-Agent"));
    }

    private static String resolveIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? null : remote.trim();
    }
}
