package com.tp.TargetPlatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Lets the Workbench frontend call this generated app's own REST endpoints (proxy/twin /start, the generic /api/v1/process status endpoint) directly from the browser - this app is launched on a fresh, unpredictable port every time (see SpringBootProjectLauncher.findFreePort), so there is no fixed origin of ITS OWN to configure; what's fixed is the Workbench frontend's own origin, the caller, which is what gets allowed here. Scoped to /api/** only - the Camunda webapps (Cockpit etc.) are same-origin browser navigation, not fetched cross-origin, so they need no CORS config of their own.
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:3000", "http://127.0.0.1:3000")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
