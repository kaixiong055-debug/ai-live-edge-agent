package cn.ai.live.edgeagent.localapi;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LocalOnlyInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        InetAddress address = InetAddress.getByName(request.getRemoteAddr());
        if (address.isLoopbackAddress()) {
            return true;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "local access only");
        return false;
    }
}
