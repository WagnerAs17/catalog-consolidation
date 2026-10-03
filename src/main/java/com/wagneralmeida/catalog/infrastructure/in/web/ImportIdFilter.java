package com.wagneralmeida.catalog.infrastructure.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public final class ImportIdFilter extends OncePerRequestFilter {

    static final String REQUEST_ATTRIBUTE = ImportIdFilter.class.getName() + ".importId";
    private static final String MDC_KEY = "importId";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String importPath = request.getContextPath() + "/imports";
        return !"POST".equals(request.getMethod()) || !importPath.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        UUID importId = UUID.randomUUID();
        request.setAttribute(REQUEST_ATTRIBUTE, importId);
        MDC.put(MDC_KEY, importId.toString());
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static UUID currentImportId() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        Object importId = attributes.getRequest().getAttribute(REQUEST_ATTRIBUTE);
        if (importId instanceof UUID uuid) {
            return uuid;
        }

        throw new IllegalStateException("Import request has no importId");
    }
}
