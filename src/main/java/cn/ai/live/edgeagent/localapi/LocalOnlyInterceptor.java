package cn.ai.live.edgeagent.localapi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import java.net.URI;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LocalOnlyInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        InetAddress address = InetAddress.getByName(request.getRemoteAddr());
        if (address.isLoopbackAddress()) {
            String origin = request.getHeader("Origin");
            if (origin != null && !isAllowedOrigin(origin, request)) {
                reject(response, "LOCAL_SAME_ORIGIN_ONLY", "仅允许本机同源 Origin");
                return false;
            }
            return true;
        }
        reject(response, "LOCAL_ACCESS_ONLY", "仅允许本机访问");
        return false;
    }

    private void reject(HttpServletResponse response, String code, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"errorCode\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private boolean isAllowedOrigin(String origin, HttpServletRequest request) {
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            int port = uri.getPort() == -1 ? ("https".equals(uri.getScheme()) ? 443 : 80) : uri.getPort();
            return ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))
                    && port == request.getServerPort();
        } catch (Exception ex) {
            return false;
        }
    }
}
