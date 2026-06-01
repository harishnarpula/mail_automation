package com.aiautomationservice.service;

import com.aiautomationservice.dto.LeadRequest;
import com.aiautomationservice.entity.ProcessedLead;
import com.aiautomationservice.repository.ProcessedLeadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class LeadService {

    private static final Logger log = LoggerFactory.getLogger(LeadService.class);

    private final UltraMsgService ultraMsgService;
    private final GmailService gmailService;
    private final ProcessedLeadRepository processedLeadRepo;
    private final LeadEmailService leadEmailService;

    public LeadService(UltraMsgService ultraMsgService,
                       GmailService gmailService,LeadEmailService leadEmailService,
                       ProcessedLeadRepository processedLeadRepo) {
        this.ultraMsgService   = ultraMsgService;
        this.gmailService      = gmailService;
        this.processedLeadRepo = processedLeadRepo;
        this.leadEmailService=leadEmailService;
    }

    public void processLead(LeadRequest lead) {
        log.info("[LeadService] Processing lead: name={}, phone={}, row={}",
                lead.getName(), lead.getPhone(), lead.getRowNumber());

        // ── Duplicate check ──────────────────────────────────────────────────
        String cleanPhone = cleanPhone(lead.getPhone());
        if (processedLeadRepo.existsByPhone(cleanPhone)) {
            log.warn("[LeadService] DUPLICATE — phone {} already processed. Skipping.", cleanPhone);
            return;
        }
        try {
            processedLeadRepo.save(ProcessedLead.builder()
                    .phone(cleanPhone)
                    .rowNumber(lead.getRowNumber())
                    .name(lead.getName())
                    .build());
        } catch (DataIntegrityViolationException ex) {
            log.warn("[LeadService] Race-condition duplicate for {} — skipping.", cleanPhone);
            return;
        }

        // Step 1: Notify team on WhatsApp group
        ultraMsgService.sendMessageToTeam(buildTeamNotification(lead));
        log.info("[LeadService] Team notified for: {}", lead.getName());

        // Step 2: Send welcome + Q1 to lead
        ultraMsgService.sendMessageToLead(lead.getPhone(), buildInstantOutreach(lead));
        log.info("[LeadService] Welcome message sent to: {}", lead.getPhone());

        // Step 3: Thank-you email async
        sendThankYouEmail(lead);
    }

    // ── Thank-you email ──────────────────────────────────────────────────────

    @Async
    public void sendThankYouEmail(LeadRequest lead) {
        if (lead.getEmail() == null || lead.getEmail().isBlank()) {
            log.info("[LeadService] No email for {} — skipping", lead.getName());
            return;
        }
        try {
            String subject = "Your Study Abroad Journey Starts Here! 🌍 — AskOxy";
            leadEmailService.sendToLead(lead.getEmail(), subject, buildThankYouEmailContent(lead));
            log.info("[LeadService] Thank-you email sent to: {}", lead.getEmail());
        } catch (Exception ex) {
            log.warn("[LeadService] Email failed for {}: {}", lead.getName(), ex.getMessage());
        }
    }

    // ── Message templates ────────────────────────────────────────────────────

    private String buildInstantOutreach(LeadRequest lead) {
        String name = nvl(lead.getName(), "there");
        return String.format(
                "Hi %s! 👋%n%n" +
                        "Welcome to *AskOxy Study Abroad* 🌍🎓%n" +
                        "We turn your dream of studying overseas into reality — " +
                        "from the right university to your visa, we're with you every step! ✈️%n%n" +
                        "*1️⃣ Which country are you planning to study in?*",
                name
        );
    }

    private String buildTeamNotification(LeadRequest lead) {
        String organic    = "true".equalsIgnoreCase(lead.getIsOrganic()) ? "Organic" : "Paid";
        String campaignId = lead.getRowNumber() != null ? "#" + lead.getRowNumber() : "N/A";
        String date       = formatDate(nvl(lead.getCreatedTime(), ""));

        return String.format(
                "*New Lead!*%n" +
                        "------------------------------%n" +
                        "*Name:*\t\t%s%n" +
                        "*City:*\t\t%s%n" +
                        "*Phone:*\t\t%s%n" +
                        "*Email:*\t\t%s%n" +
                        "*Campaign ID:* %s  |  %s%n%n" +
                        "AI onboarding started!",
                nvl(lead.getName(),    "N/A"),
                nvl(lead.getCity(),    "N/A"),
                nvl(lead.getPhone(),   "N/A"),
                nvl(lead.getEmail(),   "N/A"),
                nvl(lead.getProduct(), "N/A"),
                nvl(lead.getSource(),  "N/A"),
                organic, campaignId, date
        );
    }

    private String buildThankYouEmailContent(LeadRequest lead) {
        String name  = nvl(lead.getName(),  "there");
        String city  = nvl(lead.getCity(),  "N/A");
        String phone = nvl(lead.getPhone(), "N/A");

        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
            </head>
            <body style="margin:0;padding:0;background:#eef2f7;
                         font-family:'Segoe UI',Arial,sans-serif;">

              <table width="100%%" cellpadding="0" cellspacing="0"
                     style="background:#eef2f7;padding:40px 0;">
                <tr><td align="center">
                  <table width="600" cellpadding="0" cellspacing="0"
                         style="background:#ffffff;border-radius:20px;overflow:hidden;
                                box-shadow:0 8px 40px rgba(0,0,0,0.10);max-width:600px;">

                    <!-- ░░ HEADER ░░ -->
                    <tr>
                      <td style="background:linear-gradient(135deg,#1565c0 0%%,#1a73e8 60%%,#42a5f5 100%%);
                                 padding:44px 40px 36px;text-align:center;">
                        <p style="margin:0 0 10px;font-size:36px;line-height:1;">🎓</p>
                        <h1 style="margin:0;color:#ffffff;font-size:26px;font-weight:700;
                                   letter-spacing:-0.3px;line-height:1.3;">
                          AskOxy Study Abroad
                        </h1>
                        <p style="margin:10px 0 0;color:#bbdefb;font-size:14px;
                                  letter-spacing:1.5px;text-transform:uppercase;">
                          Your Global Education Partner
                        </p>
                      </td>
                    </tr>

                    <!-- ░░ GREETING ░░ -->
                    <tr>
                      <td style="padding:40px 44px 0;">
                        <h2 style="margin:0 0 14px;color:#1a1a2e;font-size:22px;font-weight:700;">
                          Hi %s! 👋
                        </h2>
                        <p style="margin:0;color:#4a5568;font-size:15px;line-height:1.9;">
                          We're excited to confirm your enquiry with
                          <strong style="color:#1a73e8;">AskOxy Study Abroad</strong>.
                          Our expert counselors are already reviewing your profile and will get
                          in touch with you within <strong>24 hours</strong> with a personalised
                          plan just for you.
                        </p>
                      </td>
                    </tr>

                    <!-- ░░ WHAT WE'LL DO FOR YOU ░░ -->
                    <tr>
                      <td style="padding:32px 44px 0;">
                        <p style="margin:0 0 18px;color:#1a73e8;font-size:12px;font-weight:700;
                                  text-transform:uppercase;letter-spacing:1.5px;">
                          What We'll Do For You
                        </p>
                        <table width="100%%" cellpadding="0" cellspacing="0">
                          <tr>
                            <td style="padding:0 0 12px;">
                              <table cellpadding="0" cellspacing="0">
                                <tr>
                                  <td style="width:40px;height:40px;background:#e3f2fd;
                                             border-radius:10px;text-align:center;
                                             vertical-align:middle;font-size:18px;">✅</td>
                                  <td style="padding-left:16px;color:#2d3748;font-size:14px;line-height:1.6;">
                                    <strong>Personalised university shortlist</strong>
                                    based on your profile &amp; goals
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:0 0 12px;">
                              <table cellpadding="0" cellspacing="0">
                                <tr>
                                  <td style="width:40px;height:40px;background:#e3f2fd;
                                             border-radius:10px;text-align:center;
                                             vertical-align:middle;font-size:18px;">🏅</td>
                                  <td style="padding-left:16px;color:#2d3748;font-size:14px;line-height:1.6;">
                                    <strong>Scholarship opportunities</strong>
                                    you qualify for — maximise your funding
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:0 0 12px;">
                              <table cellpadding="0" cellspacing="0">
                                <tr>
                                  <td style="width:40px;height:40px;background:#e3f2fd;
                                             border-radius:10px;text-align:center;
                                             vertical-align:middle;font-size:18px;">📝</td>
                                  <td style="padding-left:16px;color:#2d3748;font-size:14px;line-height:1.6;">
                                    <strong>Complete application guidance</strong>
                                    &amp; SOP/LOR support
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:0 0 4px;">
                              <table cellpadding="0" cellspacing="0">
                                <tr>
                                  <td style="width:40px;height:40px;background:#e3f2fd;
                                             border-radius:10px;text-align:center;
                                             vertical-align:middle;font-size:18px;">✈️</td>
                                  <td style="padding-left:16px;color:#2d3748;font-size:14px;line-height:1.6;">
                                    <strong>End-to-end visa assistance</strong>
                                    from application to approval
                                  </td>
                                </tr>
                              </table>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>

                    <!-- ░░ CTA BUTTON ░░ -->
                    <tr>
                      <td style="padding:36px 44px 0;text-align:center;">
                        <a href="https://www.askoxy.ai/studyabroad"
                           style="display:inline-block;
                                  background:linear-gradient(135deg,#1565c0,#1a73e8);
                                  color:#ffffff;text-decoration:none;
                                  padding:15px 40px;border-radius:50px;
                                  font-size:15px;font-weight:700;
                                  letter-spacing:0.4px;
                                  box-shadow:0 4px 14px rgba(26,115,232,0.4);">
                          Explore AskOxy Study Abroad &rarr;
                        </a>
                      </td>
                    </tr>

                    <!-- ░░ CONTACT INFO ░░ -->
                    <tr>
                      <td style="padding:28px 44px 0;">
                        <table width="100%%" cellpadding="0" cellspacing="0"
                               style="background:#f7faff;border:1px solid #dce8fb;
                                      border-radius:12px;padding:22px 24px;">
                          <tr>
                            <td style="color:#4a5568;font-size:14px;line-height:2.2;">
                              🌐 &nbsp;<strong>Website:</strong>&nbsp;
                              <a href="https://www.askoxy.ai/studyabroad"
                                 style="color:#1a73e8;text-decoration:none;font-weight:600;">
                                www.askoxy.ai/studyabroad
                              </a><br/>
                              ⏰ &nbsp;<strong>Response time:</strong>&nbsp; Within 24 hours
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>

                    <!-- ░░ SIGN-OFF ░░ -->
                    <tr>
                      <td style="padding:32px 44px 40px;">
                        <p style="margin:0 0 6px;color:#4a5568;font-size:15px;line-height:1.8;">
                          We look forward to helping you achieve your study abroad dream! 🚀
                        </p>
                        <p style="margin:0;color:#333;font-size:15px;">
                          Warm regards,<br/>
                          <strong style="color:#1a73e8;">The AskOxy Study Abroad Team</strong>
                        </p>
                      </td>
                    </tr>

                    <!-- ░░ FOOTER ░░ -->
                    <tr>
                      <td style="background:#f0f4f8;border-top:1px solid #e2e8f0;
                                 padding:20px 44px;text-align:center;">
                        <p style="margin:0;color:#a0aec0;font-size:12px;line-height:1.8;">
                          &copy; 2026 AskOxy Group. All rights reserved.<br/>
                          <a href="https://www.askoxy.ai/studyabroad"
                             style="color:#1a73e8;text-decoration:none;">
                            www.askoxy.ai/studyabroad
                          </a>
                        </p>
                      </td>
                    </tr>

                  </table>
                </td></tr>
              </table>

            </body>
            </html>
            """.formatted(name);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String cleanPhone(String phone) {
        if (phone == null) return "";
        return phone.replace("@c.us", "").replace("@g.us", "").replaceAll("[^0-9]", "");
    }

    private String formatDate(String raw) {
        try {
            String[] p = raw.split(" ");
            if (p.length >= 5) return p[2] + " " + p[1] + " " + p[3] + ", " + p[4].substring(0, 5);
        } catch (Exception ignored) {}
        return raw;
    }

    private String nvl(String v, String fallback) {
        return (v != null && !v.isBlank()) ? v : fallback;
    }
}