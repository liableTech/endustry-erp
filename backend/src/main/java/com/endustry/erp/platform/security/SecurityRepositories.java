package com.endustry.erp.platform.security;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.endustry.erp.platform.common.BaseRepository;

public final class SecurityRepositories {

	private SecurityRepositories() {
	}

	public interface AppUserRepository extends BaseRepository<AppUser> {
		Optional<AppUser> findByEmailIgnoreCase(String email);

		long countByRoleAndActiveTrue(Role role);

		long countByActiveTrue();
	}

	public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {
		Optional<RolePermission> findByRoleAndModule(Role role, String module);
	}
}
