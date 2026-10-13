package com.endustry.erp.platform.signup;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.endustry.erp.platform.audit.AuditService;
import com.endustry.erp.platform.common.ApiException;
import com.endustry.erp.platform.security.CurrentUser;
import com.endustry.erp.platform.tenancy.Industry;
import com.endustry.erp.platform.tenancy.PlatformService;
import com.endustry.erp.platform.tenancy.PlatformService.CreateClient;
import com.endustry.erp.platform.tenancy.Tenant;
import com.endustry.erp.platform.tenancy.TenantContext;
import com.endustry.erp.platform.tenancy.TenantRegistry;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Self sign-up: a visitor picks a plan, pays and asks for a workspace. The product admin decides
 * whether sign-up is open at all, whether a request becomes a client at once or waits for
 * approval, how many months of free membership a new business gets, and which coupon codes give
 * a discount. Payment is a demonstration for now: no gateway is called and no money moves.
 */
@Service
@Transactional
public class SignupService {

	static final String ENABLED = "signup.enabled";
	static final String AUTO_APPROVE = "signup.autoApprove";
	static final String FREE_MONTHS = "signup.freeMonths";
	/** The longest free membership a product admin can offer. */
	static final int MAX_FREE_MONTHS = 3;
	private static final String PENDING = "PENDING";
	private static final String APPROVED = "APPROVED";
	private static final String REJECTED = "REJECTED";
	private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
	private static final String ENTITY = "Signup";
	private static final String COUPON = "Coupon";

	/** What a product admin controls: the two switches and the months of free membership (0 for none). */
	public record Settings(boolean enabled, boolean autoApprove, int freeMonths) {
	}

	public record SignupForm(
			@NotBlank(message = "is required") @Size(max = 120) String companyName,
			@NotNull(message = "is required") Industry industry,
			@NotBlank(message = "is required") @Pattern(regexp = "\\d{2}", message = "must be a 2-digit GST state code") String stateCode,
			@NotBlank(message = "is required") @Size(max = 100) String ownerName,
			@NotBlank(message = "is required") @Email(message = "is not a valid email address") String ownerEmail,
			@Pattern(regexp = "[0-9+ -]{7,15}", message = "is not a valid phone number") String phone,
			@NotBlank(message = "is required") @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String password,
			@NotNull(message = "is required") Plan plan,
			@Size(max = 20) String couponCode) {
	}

	/** What the visitor sees after paying: whether the workspace is ready or waiting for approval. */
	public record Receipt(String status, String reference, String email, String plan, BigDecimal amount, String couponCode, BigDecimal discount,
			int freeMonths) {
	}

	/** One request on the product admin's list. Amount is what was charged at sign-up; discount is per month. */
	public record Request(Long id, String companyName, String industry, String stateCode, String ownerName, String ownerEmail, String phone,
			String plan, BigDecimal amount, String paymentRef, String paymentStatus, String status, String note, String tenantSlug,
			Instant createdAt, Instant decidedAt, String decidedBy, String couponCode, BigDecimal discount, int freeMonths) {
	}

	public record Coupon(String code, int percentOff, int maxUses, int used, LocalDate validUntil, boolean active, String note, Instant createdAt,
			String createdBy) {
	}

	/** A coupon as the product admin fills it in. With no code given, one is generated. Zero uses means no limit. */
	public record NewCoupon(
			@Pattern(regexp = "[A-Za-z0-9-]{4,20}", message = "must be 4 to 20 letters, digits or dashes") String code,
			@NotNull(message = "is required") @Min(value = 1, message = "must be between 1 and 100") @Max(value = 100, message = "must be between 1 and 100") Integer percentOff,
			@Min(value = 0, message = "cannot be negative") @Max(value = 100000, message = "is too large") Integer maxUses,
			LocalDate validUntil,
			@Size(max = 200) String note) {
	}

	/** What a coupon does to a plan's monthly price, shown to the visitor before paying. */
	public record Quote(String code, int percentOff, BigDecimal discount, BigDecimal monthlyPrice) {
	}

	private final JdbcTemplate jdbc;
	private final PlatformService platform;
	private final TenantRegistry registry;
	private final PasswordEncoder passwordEncoder;
	private final AuditService audit;
	private final SecureRandom random = new SecureRandom();

	@PersistenceContext
	private EntityManager em;

	public SignupService(DataSource dataSource, PlatformService platform, TenantRegistry registry, PasswordEncoder passwordEncoder, AuditService audit) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.platform = platform;
		this.registry = registry;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
	}

	// ---- settings ----

	@Transactional(readOnly = true)
	public Settings settings() {
		return new Settings(flag(ENABLED), flag(AUTO_APPROVE), freeMonths());
	}

	public Settings saveSettings(Settings settings) {
		if (settings.freeMonths() < 0 || settings.freeMonths() > MAX_FREE_MONTHS) {
			throw ApiException.badRequest("INVALID_FREE_MONTHS", "Free membership can be 0 to " + MAX_FREE_MONTHS + " months");
		}
		Settings before = settings();
		set(ENABLED, String.valueOf(settings.enabled()));
		set(AUTO_APPROVE, String.valueOf(settings.autoApprove()));
		set(FREE_MONTHS, String.valueOf(settings.freeMonths()));
		if (!before.equals(settings)) {
			audit.note(ENTITY, null, "SETTINGS", Map.of("signUpOpen", settings.enabled(), "automaticApproval", settings.autoApprove(),
					"freeMonths", settings.freeMonths()));
		}
		return settings;
	}

	// ---- coupons ----

	@Transactional(readOnly = true)
	public List<Coupon> coupons() {
		return jdbc.query("select code, percent_off, max_uses, used, valid_until, active, note, created_at, created_by from coupon order by created_at desc, code",
				(rs, i) -> new Coupon(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getDate(5) == null ? null : rs.getDate(5).toLocalDate(),
						rs.getBoolean(6), rs.getString(7), instant(rs.getTimestamp(8)), rs.getString(9)));
	}

	public Coupon createCoupon(NewCoupon request) {
		String code = request.code() == null || request.code().isBlank() ? freeCouponCode() : request.code().trim().toUpperCase();
		if (findCoupon(code) != null) {
			throw ApiException.conflict("DUPLICATE", "A coupon with the code " + code + " already exists");
		}
		requireFuture(request.validUntil());
		String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
		jdbc.update("insert into coupon (code, percent_off, max_uses, used, valid_until, active, note, created_at, created_by) values (?, ?, ?, 0, ?, true, ?, ?, ?)",
				code, request.percentOff(), request.maxUses() == null ? 0 : request.maxUses(), request.validUntil() == null ? null : Date.valueOf(request.validUntil()),
				note, Timestamp.from(Instant.now()), CurrentUser.email());
		audit.note(COUPON, null, "CREATE", Map.of("code", code, "percentOff", request.percentOff()));
		return findCoupon(code);
	}

	/**
	 * Changes a coupon: its discount, how many businesses can use it, its end date, its note or the
	 * code itself. Sign-ups that already used it keep the price they got, and follow a renamed code.
	 */
	public Coupon updateCoupon(String code, NewCoupon request) {
		Coupon coupon = findCoupon(code == null ? "" : code.trim().toUpperCase());
		if (coupon == null) {
			throw ApiException.notFound(COUPON, code);
		}
		String newCode = request.code() == null || request.code().isBlank() ? coupon.code() : request.code().trim().toUpperCase();
		if (!newCode.equals(coupon.code()) && findCoupon(newCode) != null) {
			throw ApiException.conflict("DUPLICATE", "A coupon with the code " + newCode + " already exists");
		}
		if (request.validUntil() != null && !request.validUntil().equals(coupon.validUntil())) {
			requireFuture(request.validUntil());
		}
		String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
		jdbc.update("update coupon set code = ?, percent_off = ?, max_uses = ?, valid_until = ?, note = ? where code = ?",
				newCode, request.percentOff(), request.maxUses() == null ? 0 : request.maxUses(),
				request.validUntil() == null ? null : Date.valueOf(request.validUntil()), note, coupon.code());
		if (!newCode.equals(coupon.code())) {
			jdbc.update("update signup_request set coupon_code = ? where coupon_code = ?", newCode, coupon.code());
		}
		audit.note(COUPON, null, "UPDATE", Map.of("code", newCode, "was", coupon.code(), "percentOff", request.percentOff(),
				"maxUses", request.maxUses() == null ? 0 : request.maxUses()));
		return findCoupon(newCode);
	}

	/** Switches a coupon off, or back on. A coupon is never deleted, so sign-ups that used it still show it. */
	public Coupon setCouponActive(String code, boolean active) {
		Coupon coupon = findCoupon(code == null ? "" : code.trim().toUpperCase());
		if (coupon == null) {
			throw ApiException.notFound(COUPON, code);
		}
		jdbc.update("update coupon set active = ? where code = ?", active, coupon.code());
		if (coupon.active() != active) {
			audit.note(COUPON, null, active ? "ACTIVATE" : "DEACTIVATE", Map.of("code", coupon.code()));
		}
		return findCoupon(coupon.code());
	}

	/** Checks a coupon for the visitor and says what the plan costs with it. */
	@Transactional(readOnly = true)
	public Quote quote(String code, Plan plan) {
		Coupon coupon = usable(code);
		BigDecimal discount = discount(plan, coupon);
		return new Quote(coupon.code(), coupon.percentOff(), discount, BigDecimal.valueOf(plan.monthlyPrice()).subtract(discount));
	}

	// ---- the visitor ----

	public Receipt signUp(SignupForm form) {
		Settings settings = settings();
		if (!settings.enabled()) {
			throw ApiException.forbidden("SIGNUP_CLOSED", "Sign-up is not open right now. Please contact us and we will set you up.");
		}
		String email = form.ownerEmail().trim().toLowerCase();
		if (emailInUse(email)) {
			throw ApiException.conflict("DUPLICATE_EMAIL", "This email already has an account or a sign-up waiting for approval. Sign in, or use another email.");
		}
		Coupon coupon = form.couponCode() == null || form.couponCode().isBlank() ? null : usable(form.couponCode());
		BigDecimal discount = coupon == null ? BigDecimal.ZERO : discount(form.plan(), coupon);
		// During free membership nothing is charged at sign-up; the coupon stays on the account for the first paid month.
		boolean free = settings.freeMonths() > 0;
		BigDecimal amount = free ? BigDecimal.ZERO : BigDecimal.valueOf(form.plan().monthlyPrice()).subtract(discount);
		String paymentStatus = free ? "FREE_MEMBERSHIP" : amount.signum() == 0 ? "NO_CHARGE" : "DEMO_PAID";
		String reference = (free ? "FREE-" : "DEMO-") + randomCode(10);
		String phone = form.phone() == null || form.phone().isBlank() ? null : form.phone().trim();
		jdbc.update("""
				insert into signup_request (company_name, industry, state_code, owner_name, owner_email, phone, password_hash, plan, amount,
				payment_ref, payment_status, status, created_at, coupon_code, discount, free_months) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)""",
				form.companyName().trim(), form.industry().name(), form.stateCode(), form.ownerName().trim(), email, phone,
				passwordEncoder.encode(form.password()), form.plan().name(), amount, reference, paymentStatus, Timestamp.from(Instant.now()),
				coupon == null ? null : coupon.code(), discount, settings.freeMonths());
		if (coupon != null) {
			jdbc.update("update coupon set used = used + 1 where code = ?", coupon.code());
		}
		Long id = jdbc.queryForObject("select id from signup_request where payment_ref = ?", Long.class, reference);
		if (settings.autoApprove()) {
			approve(id, "automatic approval");
		}
		return new Receipt(settings.autoApprove() ? APPROVED : PENDING, reference, email, form.plan().name(), amount,
				coupon == null ? null : coupon.code(), discount, settings.freeMonths());
	}

	// ---- the product admin ----

	@Transactional(readOnly = true)
	public List<Request> requests() {
		return jdbc.query("""
				select id, company_name, industry, state_code, owner_name, owner_email, phone, plan, amount, payment_ref, payment_status,
				status, note, tenant_slug, created_at, decided_at, decided_by, coupon_code, discount, free_months from signup_request order by id desc""",
				(rs, i) -> new Request(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
						rs.getString(8), rs.getBigDecimal(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14),
						instant(rs.getTimestamp(15)), instant(rs.getTimestamp(16)), rs.getString(17), rs.getString(18), rs.getBigDecimal(19), rs.getInt(20)));
	}

	public Request approve(Long id) {
		approve(id, CurrentUser.email());
		return require(id);
	}

	public Request reject(Long id, String note) {
		Request request = require(id);
		requirePending(request);
		String reason = note == null || note.isBlank() ? null : note.trim();
		if (reason != null && reason.length() > 400) {
			throw ApiException.badRequest("INVALID_NOTE", "The note must be at most 400 characters");
		}
		jdbc.update("update signup_request set status = ?, note = ?, password_hash = null, decided_at = ?, decided_by = ? where id = ?",
				REJECTED, reason, Timestamp.from(Instant.now()), CurrentUser.email(), id);
		if (request.couponCode() != null) {
			// The coupon was never really used, so it goes back to whoever holds the code.
			jdbc.update("update coupon set used = used - 1 where code = ? and used > 0", request.couponCode());
		}
		audit.note(ENTITY, id, "REJECT", Map.of("company", request.companyName(), "owner", request.ownerEmail()));
		return require(id);
	}

	// ---- internals ----

	/** Turns a request into a working client: its workspace, its Owner with the password they chose, and its plan. */
	private void approve(Long id, String decidedBy) {
		Request request = require(id);
		requirePending(request);
		Plan plan = Plan.valueOf(request.plan());
		Industry industry = Industry.valueOf(request.industry());
		String passwordHash = jdbc.queryForObject("select password_hash from signup_request where id = ?", String.class, id);
		String slug = freeSlug(request.companyName());
		// The plan's modules, less any the industry does not use (a consultancy has no stock).
		Set<String> modules = new LinkedHashSet<>(plan.modules());
		modules.retainAll(industry.modules());
		String notes = "Self sign-up, " + plan.label() + " plan, " + (request.freeMonths() > 0 ? request.freeMonths() + " months free, " : "")
				+ (request.couponCode() == null ? "" : "coupon " + request.couponCode() + ", ") + "reference " + request.paymentRef();
		CreateClient client = new CreateClient(request.companyName(), slug, industry, request.stateCode(), null, request.ownerName(), request.ownerEmail(),
				null, false, Tenant.Hosting.SHARED, plan.users(), modules, notes);
		// Free membership runs for the months offered when they signed up; otherwise the first paid month.
		LocalDate paidUntil = LocalDate.now().plusMonths(Math.max(request.freeMonths(), 1));
		// A visitor belongs to no client, so the workspace is created on behalf of the product itself.
		registry.runAs(TenantContext.PLATFORM, () -> {
			platform.create(client);
			em.createNativeQuery("update app_user set password_hash = ?1 where lower(email) = ?2 and tenant_id = ?3")
					.setParameter(1, passwordHash).setParameter(2, request.ownerEmail()).setParameter(3, slug).executeUpdate();
			em.createNativeQuery("update tenant set plan = ?1, paid_until = ?2, created_by = ?3 where slug = ?4")
					.setParameter(1, plan.name()).setParameter(2, paidUntil).setParameter(3, "self sign-up").setParameter(4, slug).executeUpdate();
			audit.note(ENTITY, id, "APPROVE", Map.of("company", request.companyName(), "client", slug, "plan", plan.name(), "by", decidedBy));
			return null;
		});
		registry.forget(slug);
		jdbc.update("update signup_request set status = ?, tenant_slug = ?, password_hash = null, decided_at = ?, decided_by = ? where id = ?",
				APPROVED, slug, Timestamp.from(Instant.now()), decidedBy, id);
	}

	private Request require(Long id) {
		return requests().stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow(() -> ApiException.notFound("Sign-up", id));
	}

	private static void requirePending(Request request) {
		if (!PENDING.equals(request.status())) {
			throw ApiException.conflict("ALREADY_DECIDED", "This sign-up has already been " + request.status().toLowerCase());
		}
	}

	private Coupon findCoupon(String code) {
		return coupons().stream().filter(c -> c.code().equals(code)).findFirst().orElse(null);
	}

	/** The coupon behind a code a visitor typed, if it can still be used. */
	private Coupon usable(String typed) {
		Coupon coupon = findCoupon(typed == null ? "" : typed.trim().toUpperCase());
		if (coupon == null || !coupon.active()) {
			throw ApiException.rule("COUPON_INVALID", "This coupon code is not valid");
		}
		if (coupon.validUntil() != null && coupon.validUntil().isBefore(LocalDate.now())) {
			throw ApiException.rule("COUPON_EXPIRED", "This coupon code has expired");
		}
		if (coupon.maxUses() > 0 && coupon.used() >= coupon.maxUses()) {
			throw ApiException.rule("COUPON_USED_UP", "This coupon code has already been used the number of times allowed");
		}
		return coupon;
	}

	private static void requireFuture(LocalDate validUntil) {
		if (validUntil != null && !validUntil.isAfter(LocalDate.now())) {
			throw ApiException.badRequest("INVALID_DATE", "Valid until must be a future date");
		}
	}

	private static BigDecimal discount(Plan plan, Coupon coupon) {
		return BigDecimal.valueOf(plan.monthlyPrice()).multiply(BigDecimal.valueOf(coupon.percentOff())).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
	}

	private String freeCouponCode() {
		String code = randomCode(8);
		while (findCoupon(code) != null) {
			code = randomCode(8);
		}
		return code;
	}

	private boolean emailInUse(String email) {
		long users = jdbc.queryForObject("select count(*) from app_user where lower(email) = ?", Long.class, email);
		long waiting = jdbc.queryForObject("select count(*) from signup_request where owner_email = ? and status = 'PENDING'", Long.class, email);
		return users + waiting > 0;
	}

	/** A short name from the company name that no client uses yet: "Acme Agro Pvt Ltd" gives acme-agro, then acme-agro-2. */
	private String freeSlug(String companyName) {
		String base = companyName.toLowerCase().replaceAll("\\b(pvt|private|ltd|limited|llp|and|the)\\b", "").replaceAll("[^a-z0-9]+", "-")
				.replaceAll("^[-0-9]+|-+$", "");
		if (base.length() < 3) {
			base = "client-" + base;
		}
		base = base.substring(0, Math.min(base.length(), 24)).replaceAll("-+$", "");
		String slug = base;
		for (int n = 2; taken(slug); n++) {
			slug = base + "-" + n;
		}
		return slug;
	}

	private boolean taken(String slug) {
		return Arrays.asList(TenantContext.PLATFORM, TenantContext.NONE, "admin", "api", "www", "demo-client").contains(slug)
				|| jdbc.queryForObject("select count(*) from tenant where slug = ?", Long.class, slug) > 0;
	}

	private String randomCode(int length) {
		StringBuilder code = new StringBuilder();
		for (int i = 0; i < length; i++) {
			code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
		}
		return code.toString();
	}

	private String setting(String key) {
		List<String> values = jdbc.queryForList("select setting_value from platform_setting where setting_key = ?", String.class, key);
		return values.isEmpty() ? null : values.get(0);
	}

	private boolean flag(String key) {
		return Boolean.parseBoolean(setting(key));
	}

	private int freeMonths() {
		try {
			return Math.min(Math.max(Integer.parseInt(String.valueOf(setting(FREE_MONTHS))), 0), MAX_FREE_MONTHS);
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	private void set(String key, String value) {
		if (jdbc.update("update platform_setting set setting_value = ? where setting_key = ?", value, key) == 0) {
			jdbc.update("insert into platform_setting (setting_key, setting_value) values (?, ?)", key, value);
		}
	}

	private static Instant instant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}
}
