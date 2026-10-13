package com.endustry.erp.platform.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import com.endustry.erp.platform.tenancy.TenantFilter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.endustry.erp.platform.common.ApiError;
import com.nimbusds.jose.jwk.source.ImmutableSecret;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableJpaAuditing
@EnableJpaRepositories(basePackages = "com.endustry.erp", considerNestedRepositories = true)
@RequiredArgsConstructor
public class SecurityConfig implements WebMvcConfigurer {

	private static final int MIN_SECRET_BYTES = 32;

	private final PermissionInterceptor permissionInterceptor;
	private final TenantFilter tenantFilter;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(permissionInterceptor).addPathPatterns("/api/**");
	}

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
		AuthenticationEntryPoint unauthenticated = (request, response, e) -> {
			response.setStatus(401);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write(mapper.writeValueAsString(
					ApiError.of(401, "UNAUTHENTICATED", "Please sign in to continue")));
		};
		http.csrf(csrf -> csrf.disable())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(a -> a
						.requestMatchers("/api/auth/login", "/api/public/**").permitAll()
						.requestMatchers("/api/**").authenticated()
						.anyRequest().permitAll())
				.exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated))
				.oauth2ResourceServer(o -> o.authenticationEntryPoint(unauthenticated)
						.jwt(j -> j.jwtAuthenticationConverter(jwtAuthenticationConverter())))
				// The client must be fixed after the token is verified and before any database work.
				.addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class);
		return http.build();
	}

	private JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(
				jwt -> List.of(new SimpleGrantedAuthority("ROLE_" + jwt.getClaimAsString("role"))));
		return converter;
	}

	/** The tenant filter runs inside the security chain only, not a second time as a plain servlet filter. */
	@Bean
	FilterRegistrationBean<TenantFilter> tenantFilterRegistration(TenantFilter filter) {
		FilterRegistrationBean<TenantFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	@Bean
	SecretKey jwtKey(@Value("${erp.security.jwt-secret}") String secret) {
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < MIN_SECRET_BYTES) {
			throw new IllegalStateException("erp.security.jwt-secret must be at least " + MIN_SECRET_BYTES + " bytes");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}

	@Bean
	JwtEncoder jwtEncoder(SecretKey jwtKey) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey));
	}

	@Bean
	JwtDecoder jwtDecoder(SecretKey jwtKey) {
		return NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	AuditorAware<String> auditorAware() {
		return () -> Optional.of(CurrentUser.actor());
	}
}
