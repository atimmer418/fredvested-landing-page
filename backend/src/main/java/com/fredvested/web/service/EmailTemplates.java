package com.fredvested.web.service;

/**
 * The two waitlist emails. Pure functions of their links and the postal
 * address; no personalisation beyond the To header (the address is not in the
 * body at all). Web-safe and system font stacks only: a hosted font would make
 * every recipient's mail client fetch from a vendor the privacy policy does not
 * name. Every email carries an unsubscribe link and the postal address.
 */
public final class EmailTemplates {

    private EmailTemplates() {}

    public record Rendered(String subject, String html, String text) {}

    /**
     * Consent only (counsel, 2026-09-24): a double opt-in email exists to obtain
     * consent, so it is not transactional and carries nothing promotional. Subject,
     * one sentence on why they are receiving it, the link, the expiry, the
     * unsubscribe link and the postal address. EmailTemplatesTest holds the
     * deny-list of promotional phrases taken from the page copy.
     */
    public static Rendered confirmation(String confirmUrl, String unsubscribeUrl, int ttlDays, String postalAddress) {
        String subject = "Confirm your email for FRED's waitlist";
        String text = """
You're receiving this email because this address was entered on the waitlist form at fredvested.com. To finish signing up, confirm your email:

%s

This link expires in %d days. If you didn't sign up, you can ignore this email and nothing will happen.

The FRED Team
help@fredvested.com

Unsubscribe: %s
FREDvested LLC, %s
""".formatted(confirmUrl, ttlDays, unsubscribeUrl, postalAddress);

        String html = shell("Confirm your email", """
              <p style="margin:0 0 24px;font-size:16px;line-height:1.6;color:#0F172A;">
                You&#39;re receiving this email because this address was entered on the waitlist form at fredvested.com. To finish signing up, confirm your email.
              </p>
              <table cellpadding="0" cellspacing="0" style="margin:0 0 24px;">
                <tr>
                  <td style="background-color:#135bec;border-radius:10px;">
                    <a href="%s" style="display:inline-block;padding:14px 28px;font-size:16px;font-weight:700;color:#ffffff;text-decoration:none;">Confirm my email</a>
                  </td>
                </tr>
              </table>
              <p style="margin:0;font-size:13px;line-height:1.6;color:#64748B;">
                This link expires in %d days. If you didn&#39;t sign up, you can ignore this email and nothing will happen.
              </p>
""".formatted(confirmUrl, ttlDays), unsubscribeUrl, postalAddress);
        return new Rendered(subject, html, text);
    }

    /** The single-opt-in path (double opt-in flag off): the original welcome email. */
    public static Rendered welcome(String unsubscribeUrl, String postalAddress) {
        String subject = "You're in";
        String text = """
You're on FRED's private beta waitlist.

FRED is built to help you automatically invest part of every paycheck so you can clock out early. For good.

WHAT HAPPENS NEXT

- Signups are being reviewed in waves
- If invited, you'll get an email with next steps to claim access
- Access is limited: you'll have 48 hours to claim your spot when invited

You don't need to do anything else right now. You're in line.

The FRED Team
help@fredvested.com

You're receiving this because you signed up at fredvested.com.
Unsubscribe: %s
FREDvested LLC, %s
""".formatted(unsubscribeUrl, postalAddress);

        String html = shell("You&#39;re in", """
              <p style="margin:0 0 24px;font-size:16px;line-height:1.6;color:#0F172A;">
                You&#39;re on <strong>FRED's private beta waitlist</strong>.
              </p>
              <p style="margin:0 0 24px;font-size:15px;line-height:1.7;color:#334155;">
                FRED is built to help you automatically invest part of every paycheck so you can clock out early. For good.
              </p>
""" + NEXT_STEPS + """
              <hr style="border:none;border-top:1px solid #e2e8f0;margin:28px 0;">
              <p style="margin:0;font-size:15px;line-height:1.7;color:#334155;">
                You don&#39;t need to do anything else right now. You&#39;re in line.
              </p>
""", unsubscribeUrl, postalAddress);
        return new Rendered(subject, html, text);
    }

    private static final String NEXT_STEPS = """
              <hr style="border:none;border-top:1px solid #e2e8f0;margin:28px 0;">
              <p style="margin:0 0 16px;font-size:13px;font-weight:600;letter-spacing:0.08em;text-transform:uppercase;color:#64748B;">
                What happens next
              </p>
              <table cellpadding="0" cellspacing="0" style="width:100%;">
                <tr>
                  <td style="padding:6px 0;vertical-align:top;width:20px;"><span style="color:#135bec;font-weight:700;">&#8226;</span></td>
                  <td style="padding:6px 0 6px 8px;font-size:15px;line-height:1.6;color:#334155;">Signups are being reviewed in waves</td>
                </tr>
                <tr>
                  <td style="padding:6px 0;vertical-align:top;width:20px;"><span style="color:#135bec;font-weight:700;">&#8226;</span></td>
                  <td style="padding:6px 0 6px 8px;font-size:15px;line-height:1.6;color:#334155;">If invited, you&#39;ll get an email with next steps to claim access</td>
                </tr>
                <tr>
                  <td style="padding:6px 0;vertical-align:top;width:20px;"><span style="color:#135bec;font-weight:700;">&#8226;</span></td>
                  <td style="padding:6px 0 6px 8px;font-size:15px;line-height:1.6;color:#334155;">Access is limited: you&#39;ll have 48 hours to claim your spot when invited</td>
                </tr>
              </table>
""";

    private static String shell(String title, String body, String unsubscribeUrl, String postalAddress) {
        return """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>%s</title>
</head>
<body style="margin:0;padding:0;background-color:#ffffff;font-family:Helvetica,Arial,sans-serif;">
  <table width="100%%" cellpadding="0" cellspacing="0" style="background-color:#ffffff;padding:20px 16px;">
    <tr>
      <td align="center">
        <table width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;background-color:#ffffff;border:1px solid #e2e8f0;border-radius:8px;overflow:hidden;">
          <tr>
            <td style="background-color:#ffffff;padding:32px 40px;text-align:center;border-bottom:3px solid #135bec;">
              <span style="font-family:'Arial Black','Avenir Next',Arial,Helvetica,sans-serif;font-size:42px;font-weight:900;font-style:italic;color:#135bec;letter-spacing:-1px;line-height:1;">FRED</span>
            </td>
          </tr>
          <tr>
            <td style="padding:40px 40px 32px;color:#0F172A;">
%s
            </td>
          </tr>
          <tr>
            <td style="background-color:#f8fafc;padding:24px 40px;border-top:1px solid #e2e8f0;">
              <p style="margin:0 0 4px;font-size:14px;font-weight:600;color:#0F172A;">The FRED Team</p>
              <p style="margin:0;font-size:13px;color:#94a3b8;">help@fredvested.com</p>
            </td>
          </tr>
        </table>
        <p style="margin:20px 0 0;font-size:12px;color:#94a3b8;text-align:center;">
          You&#39;re receiving this because this address was entered at fredvested.com.
          <a href="%s" style="color:#94a3b8;">Unsubscribe</a>
        </p>
        <p style="margin:8px 0 0;font-size:12px;color:#94a3b8;text-align:center;">
          FREDvested LLC, %s
        </p>
      </td>
    </tr>
  </table>
</body>
</html>
""".formatted(title, body, unsubscribeUrl, escape(postalAddress));
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
