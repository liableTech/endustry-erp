package com.endustry.erp.platform.security;

import com.endustry.erp.platform.common.BaseEntity;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "app_user")
@Getter
@Setter
public class AppUser extends BaseEntity {

	@NotBlank
	@Email
	@Column(unique = true, nullable = false)
	private String email;

	@NotBlank
	@Size(max = 100)
	private String fullName;

	@NotNull
	@Enumerated(EnumType.STRING)
	private Role role;

	private boolean active = true;

	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	private String passwordHash;

	/** Plain password supplied on create or reset; never stored or returned. */
	@Transient
	@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
	@Size(min = 8, max = 72, message = "must be 8 to 72 characters")
	private String password;
}
