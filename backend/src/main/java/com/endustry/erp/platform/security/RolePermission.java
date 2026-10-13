package com.endustry.erp.platform.security;

import com.endustry.erp.platform.common.BaseEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** One cell of the role x module access matrix. Editable at runtime by an administrator. */
@Entity
@Table(name = "role_permission", uniqueConstraints = @UniqueConstraint(columnNames = { "tenant_id", "role", "module" }))
@Getter
@Setter
public class RolePermission extends BaseEntity {

	@NotNull
	@Enumerated(EnumType.STRING)
	private Role role;

	@NotBlank
	private String module;

	@NotNull
	@Enumerated(EnumType.STRING)
	private AccessLevel level;

	public static RolePermission of(Role role, String module, AccessLevel level) {
		RolePermission permission = new RolePermission();
		permission.role = role;
		permission.module = module;
		permission.level = level;
		return permission;
	}
}
