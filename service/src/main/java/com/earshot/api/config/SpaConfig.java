package com.earshot.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Client-side routes of the React app resolve to index.html. */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		for (String route : new String[] { "/day/**", "/meeting/**", "/import", "/people", "/search", "/settings" }) {
			registry.addViewController(route).setViewName("forward:/index.html");
		}
	}
}
