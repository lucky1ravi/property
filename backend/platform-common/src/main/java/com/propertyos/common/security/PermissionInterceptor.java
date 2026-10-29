package com.propertyos.common.security;

import com.propertyos.common.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Enforces {@link RequiresPermission} on controller methods and classes. */
@Component
public class PermissionInterceptor implements HandlerInterceptor {
    /**
     * Rejects a call with 403 unless the user holds the permission named by {@link RequiresPermission} on the method or class. Methods without the annotation pass through.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) return true;
        RequiresPermission rp = hm.getMethodAnnotation(RequiresPermission.class);
        if (rp == null) rp = hm.getBeanType().getAnnotation(RequiresPermission.class);
        if (rp == null) return true;
        UserContext ctx = UserContextHolder.require();
        for (String p : rp.value()) {
            if (ctx.has(p)) return true;
        }
        throw ApiException.forbidden("You don't have permission to do this (" + String.join(" or ", rp.value()) + ")");
    }
}
