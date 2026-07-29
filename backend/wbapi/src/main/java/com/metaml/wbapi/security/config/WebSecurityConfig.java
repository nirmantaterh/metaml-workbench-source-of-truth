package com.metaml.wbapi.security.config;

import org.springframework.lang.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class WebSecurityConfig {
    // authorizeRequests is deprecated for removal and shouted a WARN on every startup. Same
    // permitAll as before - the Camunda webapp does its own auth and we're loopback-only.
    //
    // Worth being blunt about what permitAll now costs, since it got worse when the Camunda
    // properties panel went into the modeler: Implementation type on a service task offers
    // camunda:class and camunda:delegateExpression from the UI, saveProcessModel deploys
    // whatever BPMN it is handed straight to the embedded engine, and CSRF is off. So anyone
    // who can POST to this API can run arbitrary code in this JVM without hand-writing any XML.
    // The only thing keeping that acceptable is server.address=127.0.0.1 in
    // application.properties. If this ever needs to listen on anything else, this filter chain
    // has to grow real authentication first.
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(@NonNull CorsRegistry registry) {
                registry.addMapping("/**") // Apply to all endpoints
                        .allowedOrigins("http://localhost:3000", "http://127.0.0.1:3000") // Allow these origins
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS") // Allow these HTTP methods
                        .allowedHeaders("*") // Allow all headers
                        .allowCredentials(true); // Allow credentials
            }
        };
    }
}
