package com.aiautomationservice.service;

import org.springframework.stereotype.Service;

@Service
public class EmailTemplateService {

    public String wrapInTemplate(String plainTextBody, String clientName) {
        String cleanedBody = stripAgentSignOff(plainTextBody);
        String htmlBody = plainTextToHtml(cleanedBody);

        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="UTF-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
          <meta http-equiv="X-UA-Compatible" content="IE=edge"/>
          <title>OXYGLOBAL.TECH</title>
          <!--[if mso]>
          <noscript><xml><o:OfficeDocumentSettings>
            <o:PixelsPerInch>96</o:PixelsPerInch>
          </o:OfficeDocumentSettings></xml></noscript>
          <![endif]-->
        </head>
        <body style="margin:0;padding:0;background-color:#f4f5f7;font-family:'Segoe UI',Tahoma,Geneva,Verdana,sans-serif;">

          <table width="100%" cellpadding="0" cellspacing="0" border="0"
                 style="background-color:#f4f5f7;padding:32px 16px;">
            <tr>
              <td align="center">

                <table width="640" cellpadding="0" cellspacing="0" border="0"
                       style="max-width:640px;width:100%;border-radius:12px;
                              overflow:hidden;box-shadow:0 2px 16px rgba(0,0,0,0.08);">

                  <!-- HEADER -->
                  <tr>
                    <td style="background:#ffffff;padding:28px 48px 0px 48px;text-align:center;
                               border-bottom:3px solid #1a73e8;">
                      <img src="https://www.oxyglobal.tech/assets/oxyglobal-B5ioIK7_.png"
                           alt="OXYGLOBAL.TECH"
                           width="200"
                           style="display:block;margin:0 auto;border:0;max-width:200px;height:auto;" />
                      <div style="height:20px;"></div>
                    </td>
                  </tr>

                  <!-- BODY -->
                  <tr>
                    <td style="background:#ffffff;padding:36px 48px 32px 48px;">
                      <div style="font-size:15px;line-height:1.85;color:#1f1f1f;">
                        """ + htmlBody + """
                      </div>
                    </td>
                  </tr>

                  <!-- DIVIDER -->
                  <tr>
                    <td style="background:#ffffff;padding:0 48px;">
                      <div style="height:1px;background:#e8e8e8;"></div>
                    </td>
                  </tr>

                  <!-- SIGNATURE -->
                  <tr>
                    <td style="background:#ffffff;padding:24px 48px 40px 48px;
                               border-radius:0 0 12px 12px;">
                      <table cellpadding="0" cellspacing="0" border="0">
                        <tr>
                          <td style="padding-right:14px;vertical-align:middle;">
                            <div style="width:44px;height:44px;border-radius:50%;
                                        background:#1a73e8;line-height:44px;
                                        text-align:center;font-size:18px;">✉️</div>
                          </td>
                          <td style="vertical-align:middle;">
                            <div style="font-size:14px;font-weight:700;color:#1f1f1f;">
                              Radhakrishna Thatavarti</div>
                            <div style="font-size:12px;color:#555;margin-top:2px;">
                              Founder &amp; CEO, OXYGLOBAL TECHNOLOGIES</div>
                            <div style="font-size:12px;margin-top:3px;">
                              <a href="https://www.oxyglobal.tech"
                                 style="color:#1a73e8;text-decoration:none;">
                                www.oxyglobal.tech</a>
                            </div>
                            <div style="font-size:12px;margin-top:2px;">
                              <a href="mailto:sales@oxyglobaltech.xyz"
                                 style="color:#1a73e8;text-decoration:none;">
                                sales@oxyglobaltech.xyz</a>
                            </div>
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>

                </table>
              </td>
            </tr>
          </table>

        </body>
        </html>
        """;
    }

    /**
     * Strips the agent-generated sign-off from the bottom of the body.
     * The template's own signature block replaces it.
     */
    private String stripAgentSignOff(String body) {
        if (body == null) return "";

        String[] lines = body.split("\n");
        int cutAt = lines.length;

        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].trim().toLowerCase();
            if (t.isEmpty()) continue;
            if (t.startsWith("warm regards") || t.startsWith("best regards")
                    || t.startsWith("best,") || t.startsWith("regards,")
                    || t.equals("oxyglobal.tech") || t.equals("oxyglobal.tech team")
                    || t.equals("oxyglobal technologies")
                    || t.startsWith("radhakrishna thatavarti")
                    || t.startsWith("founder") || t.startsWith("www.oxyglobal")
                    || t.contains("sales@oxyglobal") || t.contains("@oxyglobal")) {
                cutAt = i;
            } else {
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cutAt; i++) {
            sb.append(lines[i]);
            if (i < cutAt - 1) sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Converts plain text to HTML.
     * - Lines that look like section headings get styled as bold dividers
     * - Double newlines → paragraph breaks
     * - Single newlines within a paragraph → <br/>
     */
    private String plainTextToHtml(String text) {
        if (text == null) return "";

        String escaped = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");

        String[] paragraphs = escaped.split("\\n\\s*\\n");
        StringBuilder html = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;

            // Detect section headings — short, all-caps or title-case lines with no period
            if (isSectionHeading(trimmed)) {
                html.append("<p style=\"margin:24px 0 8px 0;font-size:13px;font-weight:700;"
                                + "color:#1a73e8;letter-spacing:1px;text-transform:uppercase;"
                                + "border-bottom:1px solid #e8e8e8;padding-bottom:6px;\">")
                        .append(trimmed)
                        .append("</p>");
            } else {
                String withBreaks = trimmed.replace("\n", "<br/>");
                html.append("<p style=\"margin:0 0 18px 0;\">")
                        .append(withBreaks)
                        .append("</p>");
            }
        }
        return html.toString();
    }

    /**
     * Returns true if a paragraph looks like a section heading.
     * Criteria: single line (no newline), under 60 chars, no sentence-ending punctuation.
     */
    private boolean isSectionHeading(String text) {
        if (text.contains("\n")) return false;
        if (text.length() > 60) return false;
        if (text.endsWith(".") || text.endsWith(",") || text.endsWith(":")) return false;
        // Must be mostly uppercase or a known heading pattern
        String upper = text.toUpperCase();
        int upperCount = 0;
        int letterCount = 0;
        for (char c : text.toCharArray()) {
            if (Character.isLetter(c)) {
                letterCount++;
                if (Character.isUpperCase(c)) upperCount++;
            }
        }
        return letterCount > 3 && (upperCount * 100 / letterCount) >= 60;
    }
}