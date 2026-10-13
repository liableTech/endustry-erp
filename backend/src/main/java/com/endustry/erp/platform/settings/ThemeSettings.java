package com.endustry.erp.platform.settings;

import com.endustry.erp.platform.common.BaseEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Look and feel of the application, applied by the frontend as CSS variables. One row. */
@Entity
@Table(name = "theme_settings")
@Getter
@Setter
public class ThemeSettings extends BaseEntity {

	private static final String HEX = "#[0-9a-fA-F]{6}";
	private static final String HEX_MESSAGE = "must be a hex colour like #4F46E5";

	@NotBlank
	@Size(max = 40)
	private String appName = "Endustry ERP";

	@NotBlank
	@Pattern(regexp = "light|dark|system", message = "must be light, dark or system")
	private String mode = "light";

	@NotBlank
	@Pattern(regexp = HEX, message = HEX_MESSAGE)
	private String primaryColor = "#6D3FE0";

	@NotBlank
	@Pattern(regexp = HEX, message = HEX_MESSAGE)
	private String accentColor = "#A855F7";

	@NotBlank
	@Pattern(regexp = HEX, message = HEX_MESSAGE)
	private String successColor = "#16A34A";

	@NotBlank
	@Pattern(regexp = HEX, message = HEX_MESSAGE)
	private String warningColor = "#D97706";

	@NotBlank
	@Pattern(regexp = HEX, message = HEX_MESSAGE)
	private String dangerColor = "#DC2626";

	@NotBlank
	@Pattern(regexp = "dark|light|brand", message = "must be dark, light or brand")
	private String sidebarStyle = "light";

	@Min(0)
	@Max(24)
	private int radius = 14;

	@NotBlank
	@Pattern(regexp = "comfortable|compact", message = "must be comfortable or compact")
	private String density = "comfortable";

	@NotBlank
	@Pattern(regexp = "Inter|Poppins|Roboto|System", message = "is not a supported font")
	private String fontFamily = "Inter";
}
