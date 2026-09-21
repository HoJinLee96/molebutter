package cc.ataglace.molebutter.infra.web;

import jakarta.servlet.http.HttpServletRequest;

public record ClientInfo(
    String ip, 
    String userAgent
) {

    public static ClientInfo from(HttpServletRequest request) {
        if (request == null) {
            return new ClientInfo(null, null);
        }
        return new ClientInfo(resolveIp(request), request.getHeader("User-Agent"));
    }

    private static String resolveIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? null : remote.trim();
    }
}