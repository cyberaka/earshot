package com.earshot.api.config;

import java.io.IOException;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Any website the user visits can send requests to localhost (PRD §9.4). Rejects a foreign
 * Host (DNS rebinding) and a foreign Origin on anything that changes state or opens a socket.
 */
@Component
@Order(1)
public class LocalOnlyFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(LocalOnlyFilter.class);
	private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
	private final Set<String> origins;

	public LocalOnlyFilter(EarshotProperties props) {
		this.origins = Set.copyOf(props.allowedOrigins());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
			throws ServletException, IOException {
		String host = req.getServerName();
		if (!host.equals("localhost") && !host.equals("127.0.0.1")) {
			log.warn("event=rejected reason=host host={}", host);
			res.sendError(403);
			return;
		}
		String origin = req.getHeader("Origin");
		boolean upgrade = "websocket".equalsIgnoreCase(req.getHeader("Upgrade"));
		// Requests without an Origin come from non-browser clients (curl, tests), which a web
		// page cannot impersonate; browsers always send one on these requests.
		if (origin != null && (upgrade || !SAFE.contains(req.getMethod())) && !origins.contains(origin)) {
			log.warn("event=rejected reason=origin origin={}", origin);
			res.sendError(403);
			return;
		}
		chain.doFilter(req, res);
	}
}
