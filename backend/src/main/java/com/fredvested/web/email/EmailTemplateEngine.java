package com.fredvested.web.email;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The small, deliberate renderer for the v2 email templates (email/*.html on the
 * classpath). The templates are ours and fixed, so this is regex and a balanced
 * tag walk over known markup, not an HTML library: no new dependency.
 *
 * A template marks per-recipient values with data-field="name" (inner text, or
 * the href of a link, or the alt of an image) and projection-only parts with
 * data-projection="name". Both attributes and the leading HTML comment are
 * stripped from the sent output.
 */
public final class EmailTemplateEngine {

    private EmailTemplateEngine() {}

    private static final Pattern LEADING_COMMENT = Pattern.compile("\\A\\s*<!--.*?-->\\r?\\n?", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern MARKERS = Pattern.compile("\\s(?:data-field|data-projection)=\"[^\"]*\"");
    private static final Pattern BARE_SPAN = Pattern.compile("<span (?:data-field|data-projection)=\"[^\"]*\">");
    private static final String XHTML_NS = " xmlns=\"http://www.w3.org/1999/xhtml\"";

    /** Reads a classpath resource as UTF-8. */
    public static String load(String classpathPath) {
        try (InputStream in = EmailTemplateEngine.class.getClassLoader().getResourceAsStream(classpathPath)) {
            if (in == null) throw new IllegalStateException("template missing: " + classpathPath);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The template's title element, which is also the subject line. */
    public static String title(String html) {
        Matcher m = TITLE.matcher(html);
        if (!m.find()) throw new IllegalStateException("template has no <title>");
        return unescape(m.group(1).trim());
    }

    public static String stripLeadingComment(String html) {
        return LEADING_COMMENT.matcher(html).replaceFirst("");
    }

    /** Replaces the inner text of every element marked data-field="field" with the escaped text. */
    public static String fillText(String html, String field, String text) {
        String escaped = escape(text);
        StringBuilder out = new StringBuilder(html.length());
        int from = 0;
        while (true) {
            Tag tag = openingTag(html, "data-field", field, from);
            if (tag == null) break;
            int close = html.indexOf("</" + tag.name + ">", tag.end);
            if (close < 0) throw new IllegalStateException("unclosed data-field element: " + field);
            out.append(html, from, tag.end).append(escaped);
            from = close;
        }
        out.append(html, from, html.length());
        return out.toString();
    }

    /** Replaces the href of the element marked data-field="field" with the escaped URL. */
    public static String setHref(String html, String field, String url) {
        return setAttribute(html, field, "href", url);
    }

    /** Replaces the alt of the image marked data-field="field". */
    public static String setAlt(String html, String field, String alt) {
        return setAttribute(html, field, "alt", alt);
    }

    /** The current value of an attribute on the element marked data-field="field", unescaped; null if absent. */
    public static String attribute(String html, String field, String attribute) {
        Tag tag = openingTag(html, "data-field", field, 0);
        if (tag == null) return null;
        Matcher m = Pattern.compile("\\s" + attribute + "=\"([^\"]*)\"").matcher(html.substring(tag.start, tag.end));
        return m.find() ? unescape(m.group(1)) : null;
    }

    private static String setAttribute(String html, String field, String attribute, String value) {
        Tag tag = openingTag(html, "data-field", field, 0);
        if (tag == null) return html;
        String tagText = html.substring(tag.start, tag.end);
        Pattern attr = Pattern.compile("\\s" + attribute + "=\"[^\"]*\"");
        Matcher m = attr.matcher(tagText);
        if (!m.find()) throw new IllegalStateException("data-field element has no " + attribute + ": " + field);
        String replaced = m.replaceFirst(Matcher.quoteReplacement(" " + attribute + "=\"" + escape(value) + "\""));
        return html.substring(0, tag.start) + replaced + html.substring(tag.end);
    }

    /**
     * Removes every element marked data-projection="name" with everything inside it
     * (a balanced walk, since the marked spans contain spans). A void element (img)
     * is removed on its own.
     */
    public static String removeProjection(String html, String name) {
        while (true) {
            Tag tag = openingTag(html, "data-projection", name, 0);
            if (tag == null) return html;
            int end = tag.name.equals("img") ? tag.end : closingEnd(html, tag);
            html = html.substring(0, tag.start) + html.substring(end);
        }
    }

    /**
     * The sent form: a bare marker span (one that carries nothing but its data-field or
     * data-projection attribute) is a placeholder, so it collapses to its content; every
     * other marker attribute is dropped from its tag. The XHTML namespace on the html tag
     * goes too: it is the only "http://" a template carries, and no mail client needs it.
     */
    public static String stripMarkers(String html) {
        while (true) {
            Matcher m = BARE_SPAN.matcher(html);
            if (!m.find()) break;
            Tag open = new Tag("span", m.start(), m.end());
            int closeStart = closingEnd(html, open) - "</span>".length();
            html = html.substring(0, open.start) + html.substring(open.end, closeStart) + html.substring(closeStart + "</span>".length());
        }
        return MARKERS.matcher(html).replaceAll("").replace(XHTML_NS, "");
    }

    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String unescape(String s) {
        return s.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&#39;", "'").replace("&amp;", "&");
    }

    private record Tag(String name, int start, int end) {}

    private static Tag openingTag(String html, String marker, String value, int from) {
        int at = html.indexOf(" " + marker + "=\"" + value + "\"", from);
        if (at < 0) return null;
        int start = html.lastIndexOf('<', at);
        int end = html.indexOf('>', at) + 1;
        if (start < 0 || end <= 0) throw new IllegalStateException("malformed tag around " + marker + "=" + value);
        int nameEnd = start + 1;
        while (nameEnd < html.length() && Character.isLetterOrDigit(html.charAt(nameEnd))) nameEnd++;
        return new Tag(html.substring(start + 1, nameEnd), start, end);
    }

    /** Index just past the closing tag that balances the opening tag. */
    private static int closingEnd(String html, Tag open) {
        String opener = "<" + open.name;
        String closer = "</" + open.name + ">";
        int depth = 1;
        int pos = open.end;
        while (depth > 0) {
            int nextOpen = html.indexOf(opener, pos);
            while (nextOpen >= 0) {
                char c = nextOpen + opener.length() < html.length() ? html.charAt(nextOpen + opener.length()) : ' ';
                if (c == ' ' || c == '>' || c == '\n' || c == '\t') break;
                nextOpen = html.indexOf(opener, nextOpen + 1);
            }
            int nextClose = html.indexOf(closer, pos);
            if (nextClose < 0) throw new IllegalStateException("unclosed element: " + open.name);
            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++;
                pos = nextOpen + opener.length();
            } else {
                depth--;
                pos = nextClose + closer.length();
            }
        }
        return pos;
    }
}
