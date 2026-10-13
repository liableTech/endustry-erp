package com.endustry.erp.printing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.endustry.erp.platform.common.ApiException;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;

/**
 * Fills an HTML template from print data and turns the result into a PDF.
 *
 * Templates are Mustache: {{company.displayName}}, {{#lines}}...{{/lines}}, {{^x}}shown when x is
 * empty{{/x}}. Values are HTML-escaped. Images must be embedded as data: URIs; the renderer never
 * fetches anything over the network, so an uploaded template cannot make the server call out.
 */
@Component
public class PrintRenderer {

	/** Bundled so the rupee sign and Indian names print the same on every machine. */
	public static final String FONT_FAMILY = "DejaVu Sans";
	private static final String FONT_DIR = "print-templates/fonts/";

	private final Mustache.Compiler mustache = Mustache.compiler().defaultValue("").emptyStringIsFalse(true).escapeHTML(true);
	private final byte[] regular = load(FONT_DIR + "DejaVuSans.ttf");
	private final byte[] bold = load(FONT_DIR + "DejaVuSans-Bold.ttf");

	public String html(String template, Map<String, Object> data) {
		try {
			return mustache.compile(template).execute(data);
		} catch (MustacheException e) {
			throw ApiException.rule("TEMPLATE_INVALID", "The template has a placeholder error: " + e.getMessage());
		}
	}

	public byte[] pdf(String html) {
		org.jsoup.nodes.Document parsed = Jsoup.parse(html);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			PdfRendererBuilder builder = new PdfRendererBuilder();
			builder.useFastMode();
			for (String family : new String[] { FONT_FAMILY, "sans-serif", "serif", "Arial", "Helvetica", "Inter", "Roboto", "Calibri", "Times New Roman" }) {
				builder.useFont(() -> new java.io.ByteArrayInputStream(regular), family, 400, FontStyle.NORMAL, true);
				builder.useFont(() -> new java.io.ByteArrayInputStream(bold), family, 700, FontStyle.NORMAL, true);
			}
			// Only inline data: resources are allowed; anything else resolves to nothing.
			builder.useUriResolver((base, uri) -> uri != null && uri.startsWith("data:") ? uri : null);
			builder.withW3cDocument(new W3CDom().fromJsoup(parsed), "about:blank");
			builder.toStream(out);
			builder.run();
		} catch (Exception e) {
			throw ApiException.rule("TEMPLATE_INVALID", "The template could not be turned into a PDF: " + e.getMessage());
		}
		return out.toByteArray();
	}

	static String resource(String path) {
		return new String(load(path), StandardCharsets.UTF_8);
	}

	private static byte[] load(String path) {
		try (InputStream in = new ClassPathResource(path).getInputStream()) {
			return in.readAllBytes();
		} catch (IOException e) {
			throw new UncheckedIOException("Missing bundled print resource " + path, e);
		}
	}
}
