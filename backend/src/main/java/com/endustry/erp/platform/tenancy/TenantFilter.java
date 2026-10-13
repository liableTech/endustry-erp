package com.endustry.erp.platform.tenancy;

import java.io.IOException;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.endustry.erp.platform.common.ApiError;
import com.endustry.erp.platform.security.TokenService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * Decides which client a request belongs to, before any database work starts. The client comes
 * from the signed token, never from anything the caller can set freely. A suspended client is
 * turned away here, which also cuts off tokens issued before the suspension.
 */
@Component
@RequiredArgsConstructor
public class TenantFilter extends OncePerRequestFilter {

	private static final String BRANDING_PATH = "/api/public/branding";

	private final TenantRegistry registry;
	private final ObjectMapper mapper;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String tenant = TenantContext.NONE;
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth instanceof JwtAuthenticationToken jwt) {
			tenant = Optional.ofNullable(jwt.getToken().getClaimAsString(TokenService.CLAIM_TENANT)).orElse(TenantContext.NONE);
			if (!TenantContext.PLATFORM.equals(tenant) && registry.find(tenant).filter(Tenant::isActive).isEmpty()) {
				response.setStatus(403);
				response.setContentType(MediaType.APPLICATION_JSON_VALUE);
				response.getWriter().write(mapper.writeValueAsString(ApiError.of(403, "TENANT_SUSPENDED",
						"This account is suspended. Please contact your provider.")));
				return;
			}
		} else if (BRANDING_PATH.equals(request.getRequestURI())) {
			// The sign-in page is shown before anyone is known: a white-label client's own link
			// carries its slug, everyone else gets the product's branding.
			String client = request.getParameter("client");
			tenant = client != null && registry.find(client).filter(t -> t.isActive() && t.isWhiteLabel()).isPresent() ? client : TenantContext.PLATFORM;
		}
		TenantContext.set(tenant);
		try {
			chain.doFilter(request, response);
		} finally {
			TenantContext.clear();
		}
	}
}
