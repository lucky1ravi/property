package com.propertyos.common.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps a list that was answered one page at a time as {@code {rows, total, page, size}}, so the screen can show
 * "21-40 of 312" and page controls. Lists that were not paged pass through untouched.
 */
@RestControllerAdvice
public class PagedResponseAdvice implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType, Class<? extends HttpMessageConverter<?>> converterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (!(body instanceof List<?>) || !(request instanceof ServletServerHttpRequest sr)) return body;
        Object total = sr.getServletRequest().getAttribute(PageParams.TOTAL);
        if (total == null) return body;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", body);
        out.put("total", total);
        out.put("page", sr.getServletRequest().getAttribute(PageParams.PAGE));
        out.put("size", sr.getServletRequest().getAttribute(PageParams.SIZE));
        return out;
    }
}
