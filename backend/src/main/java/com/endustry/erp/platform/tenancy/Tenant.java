package com.endustry.erp.platform.tenancy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * A client of the product. This table is the one place that is not partitioned by client: it is
 * the list of partitions, managed only by product admins.
 */
@Entity
@Table(name = "tenant")
@Getter
@Setter
public class Tenant {

	public enum Status {
		ACTIVE, SUSPENDED
	}

	/** How the client is served. Recorded for the product team; hosting itself is set up outside the application. */
	public enum Hosting {
		SHARED, DEDICATED
	}

	/** Business areas that can be switched on or off per client. */
	public static final List<String> MODULES = List.of("sales", "purchase", "inventory", "production", "quality", "reports");

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Version
	private Long version;

	/** Short permanent identifier, used as the partition key on every row and in the sign-in link. */
	@Column(unique = true, nullable = false, updatable = false, length = 40)
	private String slug;

	@Column(nullable = false)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Industry industry;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status = Status.ACTIVE;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Hosting hosting = Hosting.SHARED;

	/** When on, the client may use its own name, colours and theme. When off, it sees the product's branding. */
	private boolean whiteLabel;

	/** Zero means no limit. */
	private int maxUsers;

	@JsonIgnore
	@Column(nullable = false, length = 200)
	private String moduleList = String.join(",", MODULES);

	@Column(length = 400)
	private String notes;

	@Column(updatable = false)
	private Instant createdAt = Instant.now();

	private String createdBy;

	/** The subscription plan of a client that signed up by itself; empty when the product team set the client up. */
	@Column(length = 40)
	private String plan;

	/** The day the current subscription period ends. */
	private LocalDate paidUntil;

	public Set<String> getModules() {
		Set<String> modules = new LinkedHashSet<>();
		Arrays.stream(moduleList.split(",")).filter(m -> !m.isBlank()).forEach(modules::add);
		return modules;
	}

	public void setModules(Set<String> modules) {
		// Stored in the canonical order so two equal selections always compare equal.
		this.moduleList = String.join(",", MODULES.stream().filter(modules::contains).toList());
	}

	public boolean isActive() {
		return status == Status.ACTIVE;
	}
}
