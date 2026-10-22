package cn.aidevops.assets.common;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@Component
public class TraceFilter extends OncePerRequestFilter {
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String id = req.getHeader("X-Request-ID");
    if (id == null || !id.matches("[A-Za-z0-9-]{1,64}")) id = UUID.randomUUID().toString();
    MDC.put("traceId", id);
    res.setHeader("X-Request-ID", id);
    try {
      chain.doFilter(req, res);
    } finally {
      MDC.remove("traceId");
    }
  }
}
