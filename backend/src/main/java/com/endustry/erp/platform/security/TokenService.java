package com.endustry.erp.platform.security;

import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Issues the signed tokens that carry who the caller is, which client they belong to and their role. */
@Service
@RequiredArgsConstructor
public class TokenService {

	public static final String CLAIM_TENANT = "tenant";
	public static final String CLAIM_ROLE = "role";
	public static final String CLAIM_NAME = "name";
	/** Present only on a support session: the product admin acting as this user. */
	public static final String CLAIM_IMPERSONATOR = "imp";

	private final JwtEncoder encoder;

	public String issue(String email, String fullName, Role role, String tenant, String impersonator, Duration validFor) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject(email).issuedAt(now).expiresAt(now.plus(validFor))
				.claim(CLAIM_TENANT, tenant).claim(CLAIM_ROLE, role.name()).claim(CLAIM_NAME, fullName);
		if (impersonator != null) {
			claims.claim(CLAIM_IMPERSONATOR, impersonator);
		}
		return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
	}
}
