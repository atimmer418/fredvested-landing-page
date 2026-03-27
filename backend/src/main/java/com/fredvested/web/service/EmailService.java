package com.fredvested.web.service;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private final Resend resend;

    public EmailService(@Value("${resend.api-key}") String apiKey) {
        this.resend = new Resend(apiKey);
    }

    public void sendConfirmationEmail(String toEmail) {
        try {
            CreateEmailOptions params = CreateEmailOptions.builder()
                    .from("FRED <fred@fredvested.com>")
                    .to(toEmail)
                    .subject("You're in")
                    .html(buildHtmlEmail())
                    .build();
            resend.emails().send(params);
        } catch (ResendException e) {
            throw new RuntimeException("Failed to send confirmation email to " + toEmail, e);
        }
    }

    String buildHtmlEmail() {
        return """
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>You&#39;re in</title>
  <link href="https://fonts.googleapis.com/css2?family=Montserrat:ital,wght@1,900&display=swap" rel="stylesheet">
</head>
<body style="margin:0;padding:0;background-color:#ffffff;font-family:'Inter','Helvetica Neue',Helvetica,Arial,sans-serif;">
  <table width="100%" cellpadding="0" cellspacing="0" style="background-color:#ffffff;padding:40px 16px;">
    <tr>
      <td align="center">
        <table width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%;background-color:#ffffff;border:1px solid #e2e8f0;border-radius:8px;overflow:hidden;">

          <!-- Header -->
          <tr>
            <td style="background-color:#ffffff;padding:32px 40px;text-align:center;border-bottom:3px solid #135bec;">
              <span style="font-family:'Montserrat','Arial Black',Arial,sans-serif;font-size:42px;font-weight:900;font-style:italic;color:#135bec;letter-spacing:-1px;line-height:1;">FRED</span>
            </td>
          </tr>

          <!-- Body -->
          <tr>
            <td style="padding:40px 40px 32px;color:#0F172A;">
              <p style="margin:0 0 24px;font-size:16px;line-height:1.6;color:#0F172A;">
                You&#39;re on <strong>FRED's private beta waitlist</strong>.
              </p>
              <p style="margin:0 0 24px;font-size:15px;line-height:1.7;color:#334155;">
                A few early signups may not have received a confirmation email, so I wanted to make sure you got one now.
              </p>
              <p style="margin:0 0 24px;font-size:15px;line-height:1.7;color:#334155;">
                FRED is built to help you automatically invest part of every paycheck so you can clock out early &#8212; for good.
              </p>

              <!-- Divider -->
              <hr style="border:none;border-top:1px solid #e2e8f0;margin:28px 0;">

              <!-- What happens next -->
              <p style="margin:0 0 16px;font-size:13px;font-weight:600;letter-spacing:0.08em;text-transform:uppercase;color:#64748B;">
                What happens next
              </p>
              <table cellpadding="0" cellspacing="0" style="width:100%;">
                <tr>
                  <td style="padding:6px 0;vertical-align:top;width:20px;">
                    <span style="color:#135bec;font-weight:700;">&#8212;</span>
                  </td>
                  <td style="padding:6px 0 6px 8px;font-size:15px;line-height:1.6;color:#334155;">
                    Signups are being reviewed in waves
                  </td>
                </tr>
                <tr>
                  <td style="padding:6px 0;vertical-align:top;width:20px;">
                    <span style="color:#135bec;font-weight:700;">&#8212;</span>
                  </td>
                  <td style="padding:6px 0 6px 8px;font-size:15px;line-height:1.6;color:#334155;">
                    If invited, you&#39;ll get an email with next steps to claim access
                  </td>
                </tr>
                <tr>
                  <td style="padding:6px 0;vertical-align:top;width:20px;">
                    <span style="color:#135bec;font-weight:700;">&#8212;</span>
                  </td>
                  <td style="padding:6px 0 6px 8px;font-size:15px;line-height:1.6;color:#334155;">
                    Access is limited &#8212; you&#39;ll have 48 hours to claim your spot when invited
                  </td>
                </tr>
              </table>

              <!-- Divider -->
              <hr style="border:none;border-top:1px solid #e2e8f0;margin:28px 0;">

              <p style="margin:0;font-size:15px;line-height:1.7;color:#334155;">
                You don&#39;t need to do anything else right now &#8212; you&#39;re in line.
              </p>
            </td>
          </tr>

          <!-- Footer -->
          <tr>
            <td style="background-color:#f8fafc;padding:24px 40px;border-top:1px solid #e2e8f0;">
              <p style="margin:0 0 4px;font-size:14px;font-weight:600;color:#0F172A;">The FRED Team</p>
              <p style="margin:0;font-size:13px;color:#94a3b8;">fred@fredvested.com</p>
            </td>
          </tr>

        </table>

        <!-- Below-card note -->
        <p style="margin:20px 0 0;font-size:12px;color:#94a3b8;text-align:center;">
          You&#39;re receiving this because you signed up at fredvested.com
        </p>
      </td>
    </tr>
  </table>
</body>
</html>
""";
    }
}
