package com.endustry.erp.platform.signup;

import java.util.Arrays;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.endustry.erp.platform.signup.SignupService.Coupon;
import com.endustry.erp.platform.signup.SignupService.NewCoupon;
import com.endustry.erp.platform.signup.SignupService.Quote;
import com.endustry.erp.platform.signup.SignupService.Receipt;
import com.endustry.erp.platform.signup.SignupService.Request;
import com.endustry.erp.platform.signup.SignupService.Settings;
import com.endustry.erp.platform.signup.SignupService.SignupForm;
import com.endustry.erp.platform.tenancy.Industry;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Self sign-up: the public side for visitors, and the product admin's switches, coupons and approvals. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SignupController {

	private final SignupService signups;

	public record PlanView(String code, String name, int monthlyPrice, int users, List<String> modules) {
	}

	public record IndustryView(String value, String label) {
	}

	/** What the landing and sign-up pages need: whether sign-up is open, the free months, the plans and the industries. */
	public record PublicOptions(boolean enabled, boolean autoApprove, int freeMonths, List<PlanView> plans, List<IndustryView> industries) {
	}

	public record Overview(Settings settings, List<Request> requests, List<Coupon> coupons) {
	}

	public record Decision(String note) {
	}

	public record Active(boolean active) {
	}

	@GetMapping("/public/signup")
	public PublicOptions options() {
		Settings settings = signups.settings();
		return new PublicOptions(settings.enabled(), settings.autoApprove(), settings.freeMonths(),
				Arrays.stream(Plan.values()).map(p -> new PlanView(p.name(), p.label(), p.monthlyPrice(), p.users(), p.modules())).toList(),
				Arrays.stream(Industry.values()).map(i -> new IndustryView(i.name(), i.label())).toList());
	}

	/** Lets the sign-up page show the discount before the visitor pays. */
	@GetMapping("/public/signup/coupon")
	public Quote coupon(@RequestParam String code, @RequestParam Plan plan) {
		return signups.quote(code, plan);
	}

	@PostMapping("/public/signup")
	@ResponseStatus(HttpStatus.CREATED)
	public Receipt signUp(@Valid @RequestBody SignupForm body) {
		return signups.signUp(body);
	}

	@GetMapping("/platform/signups")
	public Overview overview() {
		return new Overview(signups.settings(), signups.requests(), signups.coupons());
	}

	@PutMapping("/platform/signup-settings")
	public Settings saveSettings(@RequestBody Settings body) {
		return signups.saveSettings(body);
	}

	@PostMapping("/platform/signups/{id}/approve")
	public Request approve(@PathVariable Long id) {
		return signups.approve(id);
	}

	@PostMapping("/platform/signups/{id}/reject")
	public Request reject(@PathVariable Long id, @RequestBody(required = false) Decision body) {
		return signups.reject(id, body == null ? null : body.note());
	}

	@PostMapping("/platform/coupons")
	@ResponseStatus(HttpStatus.CREATED)
	public Coupon createCoupon(@Valid @RequestBody NewCoupon body) {
		return signups.createCoupon(body);
	}

	@PutMapping("/platform/coupons/{code}")
	public Coupon updateCoupon(@PathVariable String code, @Valid @RequestBody NewCoupon body) {
		return signups.updateCoupon(code, body);
	}

	@PutMapping("/platform/coupons/{code}/active")
	public Coupon setCouponActive(@PathVariable String code, @RequestBody Active body) {
		return signups.setCouponActive(code, body.active());
	}
}
