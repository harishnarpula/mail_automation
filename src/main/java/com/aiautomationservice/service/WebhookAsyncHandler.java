package com.aiautomationservice.service;


import com.aiautomationservice.entity.ConversationMessage;
import com.aiautomationservice.repository.ConversationMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

@Service
public class WebhookAsyncHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookAsyncHandler.class);

    // Markers used in assistant messages to indicate a question was accepted
    // and the next question was sent. Used to track real progression.
    private static final String MARKER_Q2 = "2️⃣";
    private static final String MARKER_Q3 = "3️⃣";
    private static final String MARKER_Q4 = "4️⃣";

    // Marker in the thank-you message — if present the flow already completed
    private static final String MARKER_THANKYOU = "Thank you for sharing your details";

    private final UltraMsgService ultraMsgService;
    private final ConversationMessageRepository conversationRepo;

    public WebhookAsyncHandler(UltraMsgService ultraMsgService,
                               ConversationMessageRepository conversationRepo) {
        this.ultraMsgService  = ultraMsgService;
        this.conversationRepo = conversationRepo;
    }

    @Async
    public void handleLeadReplyAsync(String phone, String leadReply) {
        try {
            List<ConversationMessage> historyBefore = loadHistory(phone);
            boolean isFirstReply = historyBefore.isEmpty();

            // ── Count ACCEPTED questions (assistant msgs containing a question marker)
            // This is robust to re-ask loops: re-ask messages have no marker,
            // so they don't advance the question counter.
            //
            // IMPORTANT: We only count from the START OF THE CURRENT SESSION.
            // The current session starts from the last assistant message that
            // contains "1️⃣" (Q1 welcome). This prevents stale old history from
            // a previous completed flow inflating the counter.
            List<ConversationMessage> sessionHistory = currentSessionHistory(historyBefore);

            long acceptedQCount = sessionHistory.stream()
                    .filter(m -> "assistant".equals(m.getRole()))
                    .filter(m -> m.getContent() != null &&
                            (m.getContent().contains(MARKER_Q2) ||
                                    m.getContent().contains(MARKER_Q3) ||
                                    m.getContent().contains(MARKER_Q4)))
                    .count();

            // Check if flow already completed (thank-you was sent)
            // NOTE: if this is the very first user reply (isFirstReply=true),
            // the history only has the Q1 welcome message — there can be no
            // completed flow yet, so skip this check entirely.
            boolean alreadyCompleted = !isFirstReply && sessionHistory.stream()
                    .filter(m -> "assistant".equals(m.getRole()))
                    .anyMatch(m -> m.getContent() != null &&
                            m.getContent().contains(MARKER_THANKYOU));

            log.info("[WebhookAsyncHandler] phone={} | acceptedQCount={} | isFirstReply={} | alreadyCompleted={}",
                    phone, acceptedQCount, isFirstReply, alreadyCompleted);

            // Save the user's incoming message
            saveMessage(phone, "user", leadReply);

            // Notify team on very first contact
            if (isFirstReply) {
                ultraMsgService.sendMessageToTeam(buildNotification1(phone, leadReply));
                log.info("[WebhookAsyncHandler] 🔔 New Active Lead: {}", phone);
            }

            // ── Post-flow: flow already completed, send smart contextual reply ──
            if (alreadyCompleted) {
                log.info("[WebhookAsyncHandler] Post-flow message from {} — smart reply", phone);
                String postFlowReply = buildPostFlowReply(leadReply);
                saveMessage(phone, "assistant", postFlowReply);
                ultraMsgService.sendMessageToLead(phone, postFlowReply);
                return;
            }

            // ── Onboarding flow ──────────────────────────────────────────────
            //
            // acceptedQCount represents how many questions have been answered:
            //   0 → Q1 (country) sent by LeadService; user is replying — validate and send Q2
            //   1 → Q2 sent (2️⃣); user is replying — validate and send Q3
            //   2 → Q3 sent (3️⃣); user is replying — validate and send Q4
            //   3 → Q4 sent (4️⃣); user is replying — validate intake and complete

            String reply;

            if (acceptedQCount == 0) {
                // Validate country (Q1 answer)
                if (!isValidCountry(leadReply)) {
                    reply = "🌍 *Which country are you considering for your studies?*\n\n"
                            + "Please share your preferred destination so we can guide you better.\n\n"
                            + "_Examples: Canada · UK · Germany · Australia · USA_";
                } else {
                    reply = buildQ2Course();
                }

            } else if (acceptedQCount == 1) {
                // Validate course (Q2 answer)
                if (!isValidText(leadReply, 2)) {
                    reply = "📚 *Which course or programme are you interested in?*\n\n"
                            + "• MBA\n• MS / Masters\n• Nursing\n• Bachelors\n• Other\n\n"
                            + "_Reply with your choice or type your programme name_";
                } else {
                    reply = buildQ3Score();
                }

            } else if (acceptedQCount == 2) {
                // Validate score (Q3 answer)
                if (!isValidScore(leadReply)) {
                    reply = "📝 *What is your English proficiency / entrance exam status?*\n\n"
                            + "• ✅ *Completed* — share your score _(e.g. IELTS 7.0)_\n"
                            + "• 📖 *Currently preparing*\n"
                            + "• ❌ *Not started yet*\n\n"
                            + "_This helps us match you with the right universities_";
                } else {
                    reply = buildQ4Intake();
                }

            } else if (acceptedQCount == 3) {
                // Validate intake (Q4 answer)
                ValidationResult intakeResult = validateIntake(leadReply);
                if (!intakeResult.valid) {
                    reply = intakeResult.errorMessage;
                    saveMessage(phone, "assistant", reply);
                    ultraMsgService.sendMessageToLead(phone, reply);
                    return;
                }

                // ── FLOW COMPLETE ────────────────────────────────────────────
                // Snapshot full history BEFORE saving thank-you messages so
                // extractValidAnswer sees exactly 4 user answers and 3 accepted
                // question messages (Q2, Q3, Q4).
                List<ConversationMessage> fullHistory = loadHistory(phone);
                log.info("[WebhookAsyncHandler] FLOW COMPLETE for {} — history size={} acceptedQCount={}",
                        phone, fullHistory.size(), acceptedQCount);

                // 1. ✅ Notify team FIRST — before anything else can throw
                String teamMsg = buildNotification2(phone, fullHistory, leadReply);
                ultraMsgService.sendMessageToTeam(teamMsg);
                log.info("[WebhookAsyncHandler] 🏆 Qualified lead notified to team: {}", phone);

                // 2. Thank-you to lead
                String thankYou = buildThankYou();
                saveMessage(phone, "assistant", thankYou);
                ultraMsgService.sendMessageToLead(phone, thankYou);

                // 3. AI store invite to lead (with safe sleep)
                try { Thread.sleep(2500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                String aiStore = buildAiStoreInvite();
                saveMessage(phone, "assistant", aiStore);
                ultraMsgService.sendMessageToLead(phone, aiStore);

                log.info("[WebhookAsyncHandler] ✅ Flow complete for: {}", phone);
                return;

            } else {
                // Shouldn't happen — treat as post-flow
                log.warn("[WebhookAsyncHandler] Unexpected acceptedQCount={} for {}", acceptedQCount, phone);
                String postFlowReply = buildPostFlowReply(leadReply);
                saveMessage(phone, "assistant", postFlowReply);
                ultraMsgService.sendMessageToLead(phone, postFlowReply);
                return;
            }

            saveMessage(phone, "assistant", reply);
            ultraMsgService.sendMessageToLead(phone, reply);

        } catch (Exception ex) {
            log.error("[WebhookAsyncHandler] Error for {}: {}", phone, ex.getMessage(), ex);
            try {
                ultraMsgService.sendMessageToLead(phone,
                        "Thank you for reaching out to *AskOxy Study Abroad*. ✅\n\n"
                                + "One of our expert counsellors will be in touch with you shortly. 😊");
            } catch (Exception fe) {
                log.error("[WebhookAsyncHandler] Fallback failed: {}", fe.getMessage());
            }
        }
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    private String buildThankYou() {
        return  "🎉 *Thank you for sharing your details!*\n\n" +
                "Our *AskOxy Study Abroad Counsellor* will personally reach out within *24 hours* with:\n\n" +
                "✅ Personalised university shortlist\n" +
                "✅ Scholarship & funding options\n" +
                "✅ End-to-end application & visa guidance\n\n" +
                "We look forward to making your study abroad dream a reality. 🌍✈️";
    }

    private String buildAiStoreInvite() {
        return  "💡 *While you wait — explore our Study Abroad AI Store!*\n\n" +
                "Get instant expert answers 24/7 — no waiting required.\n\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "🤖 *Our AI Agents:*\n\n" +
                "🌐 *AI Assistant* — your all-in-one study abroad guide\n" +
                "🎓 *Course Advisor* — find the right course & specialisation\n" +
                "🏫 *University Guide* — top universities by country & ranking\n" +
                "🗺️ *Countries Guide* — compare destinations & lifestyle\n" +
                "📋 *Admission Consultant* — application strategy & deadlines\n" +
                "📄 *Offer Letter Guide* — understand & evaluate your offer\n" +
                "✈️ *VisaPath Advisor* — step-by-step visa process\n" +
                "💰 *Application Support* — end-to-end application help\n" +
                "🏠 *Accommodation Guide* — housing & living cost estimates\n" +
                "💼 *Career & Placement* — work permits & PR pathways\n" +
                "🌍 *Global Accreditation* — check university recognition\n" +
                "🎒 *Forex & Pre-Departure* — travel prep & forex tips\n" +
                "🏛️ *CampusScope Advisor* — campus life & student support\n" +
                "📝 *Exam & Interview Coach* — IELTS · GRE · GMAT · interviews\n" +
                "🚀 *Travel & Logistics* — flights, baggage & arrival guide\n\n" +
                "━━━━━━━━━━━━━━━━━━━━\n" +
                "👉 *www.askoxy.ai/ai-store/study-abroad-ai-store*\n\n" +
                "_Select your AI agent and get expert answers in seconds!_ ⚡";
    }

    private String buildPostFlowReply(String leadReply) {
        String lower = leadReply.trim().toLowerCase();
        String topic;
        String agentHint;

        if (lower.contains("fee") || lower.contains("cost") || lower.contains("charge") || lower.contains("price") || lower.contains("money")) {
            topic     = "fees and costs";
            agentHint = "💰 *Application Support* — scholarships & fee waivers\n" +
                    "🏠 *Accommodation Guide* — living cost estimates by country\n" +
                    "🗺️ *Countries Guide* — affordable study destinations";
        } else if (lower.contains("visa")) {
            topic     = "visa requirements";
            agentHint = "✈️ *VisaPath Advisor* — complete step-by-step visa guidance\n" +
                    "🎒 *Forex & Pre-Departure* — pre-departure checklist & documents\n" +
                    "💼 *Career & Placement* — post-study visa & work permit options";
        } else if (lower.contains("ielts") || lower.contains("toefl") || lower.contains("gre") || lower.contains("gmat") || lower.contains("exam") || lower.contains("test") || lower.contains("interview")) {
            topic     = "exam preparation";
            agentHint = "📝 *Exam & Interview Coach* — IELTS · TOEFL · GRE · GMAT\n" +
                    "🎓 *Course Advisor* — score requirements per programme";
        } else if (lower.contains("accommodat") || lower.contains("stay") || lower.contains("hostel") || lower.contains("housing") || lower.contains("room") || lower.contains("rent")) {
            topic     = "accommodation";
            agentHint = "🏠 *Accommodation Guide* — on-campus & off-campus options\n" +
                    "🏛️ *CampusScope Advisor* — campus life & student facilities";
        } else if (lower.contains("scholar")) {
            topic     = "scholarships";
            agentHint = "💰 *Application Support* — scholarships you qualify for\n" +
                    "🎓 *Course Advisor* — courses with merit-based funding";
        } else if (lower.contains("university") || lower.contains("college") || lower.contains("univers") || lower.contains("rank")) {
            topic     = "universities";
            agentHint = "🏫 *University Guide* — ranked universities by course & country\n" +
                    "🌍 *Global Accreditation* — verify university recognition & rankings";
        } else if (lower.contains("course") || lower.contains("program") || lower.contains("degree")) {
            topic     = "courses & programmes";
            agentHint = "🎓 *Course Advisor* — best programmes by country\n" +
                    "💼 *Career & Placement* — career prospects after each course";
        } else if (lower.contains("job") || lower.contains("work") || lower.contains("pr") || lower.contains("immigrat") || lower.contains("career")) {
            topic     = "work & career options";
            agentHint = "💼 *Career & Placement* — work permits, PR & job pathways\n" +
                    "🎓 *Course Advisor* — courses with the highest employability";
        } else if (lower.contains("countr") || lower.contains("destination") || lower.contains("abroad")) {
            topic     = "study destinations";
            agentHint = "🗺️ *Countries Guide* — compare top study destinations\n" +
                    "🏫 *University Guide* — best universities per country";
        } else if (lower.contains("depart") || lower.contains("travel") || lower.contains("flight") || lower.contains("forex") || lower.contains("currency")) {
            topic     = "travel & pre-departure";
            agentHint = "🎒 *Forex & Pre-Departure* — travel prep, forex & checklist\n" +
                    "🚀 *Travel & Logistics* — flights, baggage & arrival guide";
        } else if (lower.contains("offer letter") || lower.contains("admission") || lower.contains("apply") || lower.contains("application")) {
            topic     = "admissions & applications";
            agentHint = "📄 *Offer Letter Guide* — understand & evaluate your offer\n" +
                    "📋 *Admission Consultant* — application strategy & deadlines\n" +
                    "💰 *Application Support* — end-to-end application assistance";
        } else {
            topic     = "your enquiry";
            agentHint = "🌐 *AI Assistant* — your all-in-one study abroad guide\n" +
                    "🎓 *Course Advisor* — find the right course & specialisation\n" +
                    "🏫 *University Guide* — top universities by country & ranking\n\n" +
                    "━━━━━━━━━━━━━━━━━━━━\n" +
                    "👉 *www.askoxy.ai/ai-store/study-abroad-ai-store*\n\n" +
                    "_Select your topic and get expert answers in seconds._ 🤖";
        }

        return String.format(
                "Thank you for your message. 😊\n\n" +
                        "Our counsellor will get back to you shortly regarding *%s*.\n\n" +
                        "━━━━━━━━━━━━━━━━━━━━\n" +
                        "⚡ *Need an instant answer right now?*\n\n" +
                        "Try our AI agents on the *AskOxy Study Abroad AI Store:*\n\n" +
                        "%s\n\n" +
                        "━━━━━━━━━━━━━━━━━━━━\n" +
                        "👉 *www.askoxy.ai/ai-store/study-abroad-ai-store*\n\n" +
                        "_Select your topic and get expert answers in seconds._ 🤖",
                topic, agentHint
        );
    }

    // ── Questions ─────────────────────────────────────────────────────────────

    private String buildQ2Course() {
        return  "Thank you! 👍\n\n" +
                "*2️⃣ Which course or programme are you interested in pursuing?*\n\n" +
                "• MBA\n• MS / Masters\n• Nursing\n• Bachelors\n• Other\n\n" +
                "_Reply with your choice or type your preferred programme name_";
    }

    private String buildQ3Score() {
        return  "Excellent choice! 🎯\n\n" +
                "*3️⃣ What is your English proficiency or entrance exam status?*\n\n" +
                "• ✅ *Completed* — share your score _(e.g. IELTS 7.0)_\n" +
                "• 📖 *Currently preparing*\n" +
                "• ❌ *Not started yet*\n\n" +
                "_Reply with one of the above options_";
    }

    private String buildQ4Intake() {
        int y = LocalDate.now().getYear();
        return  "Got it! 📝\n\n" +
                "*4️⃣ Which intake are you targeting for your admission?*\n\n" +
                String.format("_Example: Sep %d  /  Jan %d_", y, y + 1);
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private boolean isValidCountry(String reply) {
        if (reply == null) return false;
        String lower = reply.trim().toLowerCase();

        // Known country names and common abbreviations (all major study-abroad destinations)
        Set<String> countries = Set.of(
                "canada", "australia", "germany", "france", "italy", "spain",
                "ireland", "netherlands", "sweden", "norway", "denmark", "finland",
                "switzerland", "austria", "belgium", "portugal", "poland",
                "czech republic", "hungary", "romania", "greece",
                "united states", "united kingdom", "united arab emirates",
                "new zealand", "singapore", "japan", "south korea", "china",
                "india", "malaysia", "indonesia", "thailand", "philippines",
                "hong kong", "taiwan", "russia", "turkey",
                "south africa", "kenya", "nigeria", "egypt",
                "brazil", "mexico", "argentina", "colombia", "chile",
                "cyprus", "malta", "estonia", "latvia", "lithuania",
                "croatia", "serbia", "bulgaria", "slovakia", "slovenia",
                "luxembourg", "iceland",
                "uk", "us", "usa", "uae", "nz", "sg", "hk", "ca", "de", "fr", "jp", "kr", "cn"
        );

        // Direct match
        if (countries.contains(lower)) return true;

        // Contains a country name (handles "I want Canada" or "Canada please")
        for (String c : countries) {
            if (lower.contains(c)) return true;
        }

        return false;
    }

    private boolean isValidText(String reply, int minChars) {
        if (reply == null) return false;
        String lower = reply.trim().toLowerCase();

        // Accept exact options shown in Q2, plus common programme names
        Set<String> courseOptions = Set.of(
                // Options shown in the question
                "mba", "ms", "masters", "master", "ms/masters", "ms / masters",
                "nursing", "bachelors", "bachelor", "other",
                // Common typed programme names
                "btech", "b.tech", "bsc", "b.sc", "bca", "bba", "be",
                "msc", "m.sc", "mtech", "m.tech", "mca", "phd",
                "medicine", "mbbs", "engineering", "law", "arts", "science",
                "commerce", "business", "management", "finance", "it",
                "computer science", "data science", "artificial intelligence",
                "machine learning", "biotechnology", "pharmacy", "architecture",
                "design", "fashion", "hospitality", "tourism", "aviation",
                "journalism", "media", "psychology", "economics", "marketing",
                "accounting", "information technology", "software engineering",
                "civil engineering", "mechanical engineering", "electrical engineering",
                "chemical engineering", "biomedical", "environmental", "mathematics",
                "statistics", "public health", "social work", "education",
                "communications", "international relations", "political science"
        );

        // Direct match
        if (courseOptions.contains(lower)) return true;

        // Contains a course keyword
        for (String c : courseOptions) {
            if (lower.contains(c)) return true;
        }

        return false;
    }

    private boolean isValidScore(String reply) {
        if (reply == null) return false;
        String lower = reply.trim().toLowerCase();
        if (lower.isEmpty()) return false;

        // ✅ Option 1: Status keywords — "completed", "preparing", "not started"
        // These are the exact options shown in Q3
        Set<String> statusKeywords = Set.of(
                "completed", "complete", "preparing", "prepare", "preparation",
                "not started", "not yet started", "yet to start",
                "done", "appeared", "given", "cleared",
                "currently preparing", "in progress"
        );
        for (String kw : statusKeywords) if (lower.contains(kw)) return true;

        // ✅ Option 2: Exam name mentioned (with or without score)
        Set<String> examNames = Set.of(
                "ielts", "toefl", "gre", "gmat", "pte", "sat", "act",
                "duolingo", "pearson", "cambridge", "oet", "toeic"
        );
        for (String kw : examNames) if (lower.contains(kw)) return true;

        // ✅ Option 3: A valid score number
        // Score formats: "7.0", "7.5", "70", "70%", "320", "330/340", "6.5 bands"
        // Rule: 1-3 digit number (with optional decimal), optionally followed by % or text
        // Reject 5+ digit numbers like "70000" — those are not scores
        String trimmed = lower.replaceAll("[\s%]", ""); // strip spaces and %
        if (trimmed.matches("[0-9]{1,3}(\\.[0-9]{1,2})?")) return true; // e.g. 70, 7.5, 320
        if (lower.matches(".*[0-9]{1,3}(\\.[0-9]{1,2})?\\s*(%|band|bands|score|marks|points).*")) return true;

        return false;
    }

    private ValidationResult validateIntake(String reply) {
        if (reply == null || reply.trim().length() < 3) {
            return ValidationResult.fail(intakeErrorMsg());
        }
        String lower = reply.trim().toLowerCase();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("20([2-9][0-9])").matcher(lower);

        int curYear  = LocalDate.now().getYear();
        int curMonth = LocalDate.now().getMonthValue();

        if (m.find()) {
            int foundYear = Integer.parseInt("20" + m.group(1));
            if (foundYear < curYear) return ValidationResult.fail(pastErrorMsg(reply, curYear));
            if (foundYear == curYear) {
                int foundMonth = extractMonth(lower);
                if (foundMonth > 0 && foundMonth < curMonth)
                    return ValidationResult.fail(pastErrorMsg(reply, curYear));
            }
            return ValidationResult.ok();
        }
        if (extractMonth(lower) > 0) {
            return ValidationResult.fail(
                    "📅 Please include the *year* along with your target intake:\n\n" +
                            String.format("_Example: Sep *%d*  /  Jan *%d*_", curYear, curYear + 1)
            );
        }
        return ValidationResult.fail(intakeErrorMsg());
    }

    private String pastErrorMsg(String reply, int curYear) {
        return "⚠️ It appears *" + reply.trim() + "* has already passed.\n\n" +
                "📅 Please share a *future intake* you are targeting:\n\n" +
                String.format("_Example: Sep %d  /  Jan %d_", curYear, curYear + 1);
    }

    private String intakeErrorMsg() {
        int y = LocalDate.now().getYear();
        return "📅 Please share a valid *target intake* for your admission:\n\n" +
                String.format("_Example: Sep %d  /  Jan %d_", y, y + 1);
    }

    private int extractMonth(String lower) {
        Map<String, Integer> months = Map.ofEntries(
                Map.entry("jan",1), Map.entry("january",1),
                Map.entry("feb",2), Map.entry("february",2),
                Map.entry("mar",3), Map.entry("march",3),
                Map.entry("apr",4), Map.entry("april",4),
                Map.entry("may",5),
                Map.entry("jun",6), Map.entry("june",6),
                Map.entry("jul",7), Map.entry("july",7),
                Map.entry("aug",8), Map.entry("august",8),
                Map.entry("sep",9), Map.entry("september",9),
                Map.entry("oct",10),Map.entry("october",10),
                Map.entry("nov",11),Map.entry("november",11),
                Map.entry("dec",12),Map.entry("december",12)
        );
        for (Map.Entry<String,Integer> e : months.entrySet())
            if (lower.contains(e.getKey())) return e.getValue();
        return 0;
    }

    private static class ValidationResult {
        final boolean valid;
        final String errorMessage;
        private ValidationResult(boolean v, String m) { valid = v; errorMessage = m; }
        static ValidationResult ok()            { return new ValidationResult(true, null); }
        static ValidationResult fail(String m)  { return new ValidationResult(false, m); }
    }

    // ── Team notifications ────────────────────────────────────────────────────

    private String buildNotification1(String phone, String firstReply) {
        return String.format(
                "*New Active Lead*\n" +
                        "------------------------------\n" +
                        "*Phone:*\t\t+%s\n" +
                        "*First Reply:*\t_%s_\n\n" +
                        "AI onboarding flow has been initiated.", phone, firstReply);
    }

    /**
     * Builds the qualified-lead notification.
     *
     * fullHistory is snapshotted BEFORE the thank-you message is saved, so it contains:
     *   - userMsgs:      [country_answer, course_answer, score_answer, intake_answer]  (4 items)
     *   - assistantMsgs: [Q2(2️⃣), Q3(3️⃣), Q4(4️⃣)]                                 (3 items)
     *
     * We pair each user answer with the next assistant message:
     *   user[0] → assistant[0] has 2️⃣ → country accepted
     *   user[1] → assistant[1] has 3️⃣ → course accepted
     *   user[2] → assistant[2] has 4️⃣ → score accepted
     *   user[3] → no assistant left    → intake (last answer, no pairing needed)
     *
     * currentIntake is passed directly to avoid relying on the DB snapshot for Q4.
     */
    private String buildNotification2(String phone,
                                      List<ConversationMessage> fullHistory,
                                      String currentIntake) {
        List<String> u = fullHistory.stream()
                .filter(m -> "user".equals(m.getRole()))
                .map(ConversationMessage::getContent)
                .toList();
        List<String> a = fullHistory.stream()
                .filter(m -> "assistant".equals(m.getRole()))
                .map(ConversationMessage::getContent)
                .toList();

        log.info("[WebhookAsyncHandler] buildNotification2 phone={} userMsgs={} assistantMsgs={}",
                phone, u.size(), a.size());

        String country = extractValidAnswer(u, a, 0);
        String course  = extractValidAnswer(u, a, 1);
        String score   = extractValidAnswer(u, a, 2);
        // Intake: use currentIntake directly — it's the just-validated Q4 answer
        // and the DB snapshot was taken before saving it
        String intake  = (currentIntake != null && !currentIntake.isBlank())
                ? currentIntake.trim() : extractValidAnswer(u, a, 3);

        log.info("[WebhookAsyncHandler] Extracted answers: country={} course={} score={} intake={}",
                country, course, score, intake);

        return String.format(
                "*Qualified Lead!*\n" +
                        "------------------------------\n" +
                        "*Phone:*\t\t+%s\n" +
                        "*Country:*\t%s\n" +
                        "*Course:*\t\t%s\n" +
                        "*Score:*\t\t%s\n" +
                        "*Intake:*\t\t%s\n" +
                        "------------------------------\n" +
                        "*Status:*\t\tQualified Lead\n\n" +
                        "*Please contact this lead at the earliest opportunity.*",
                phone, country, course, score, intake
        );
    }

    /**
     * Extracts the lead's valid answer at position idx (0=country,1=course,2=score,3=intake).
     *
     * Pairs each user message with the next assistant response:
     * - If the assistant reply contains a next-question marker (2️⃣/3️⃣/4️⃣), the user
     *   message was accepted — add to validAnswers.
     * - If the assistant reply is a re-ask/error (no marker), the user answer was rejected —
     *   skip it.
     * - If no assistant reply exists for a user message, it is the final Q4 answer.
     *
     * This makes extraction robust to any number of re-ask loops on any question.
     */
    private String extractValidAnswer(List<String> userMsgs, List<String> assistantMsgs, int idx) {
        List<String> validAnswers = new ArrayList<>();
        int aIdx = 0;
        for (int uIdx = 0; uIdx < userMsgs.size(); uIdx++) {
            if (aIdx < assistantMsgs.size()) {
                String assistantReply = assistantMsgs.get(aIdx);
                aIdx++;
                // A next-question marker means this user answer was accepted
                if (assistantReply.contains(MARKER_Q2) ||
                        assistantReply.contains(MARKER_Q3) ||
                        assistantReply.contains(MARKER_Q4)) {
                    validAnswers.add(userMsgs.get(uIdx));
                }
                // else: re-ask/error — user answer was rejected, skip
            } else {
                // No assistant reply yet → this is the final Q4 answer
                validAnswers.add(userMsgs.get(uIdx));
            }
        }
        return idx < validAnswers.size() ? validAnswers.get(idx) : "N/A";
    }

    // ── DB helpers ────────────────────────────────────────────────────────────

    /**
     * Returns only the messages belonging to the CURRENT onboarding session.
     *
     * Scans the full history backwards to find the last assistant message
     * containing "1️⃣" (the Q1 welcome sent by LeadService). Everything from
     * that point onward is the current session.
     *
     * This prevents stale messages from a previous completed flow (including
     * old MARKER_THANKYOU entries) from interfering with acceptedQCount or
     * alreadyCompleted checks when a lead is re-registered.
     *
     * If no Q1 marker is found, returns the full history unchanged (safe default).
     */
    private List<ConversationMessage> currentSessionHistory(List<ConversationMessage> fullHistory) {
        int q1Index = -1;
        for (int i = fullHistory.size() - 1; i >= 0; i--) {
            ConversationMessage msg = fullHistory.get(i);
            if ("assistant".equals(msg.getRole()) &&
                    msg.getContent() != null &&
                    msg.getContent().contains("1️⃣")) {
                q1Index = i;
                break;
            }
        }
        if (q1Index == -1) return fullHistory;
        return fullHistory.subList(q1Index, fullHistory.size());
    }

    private void saveMessage(String phone, String role, String content) {
        conversationRepo.save(ConversationMessage.builder()
                .phone(phone).role(role).content(content).build());
    }

    private List<ConversationMessage> loadHistory(String phone) {
        List<ConversationMessage> recent =
                conversationRepo.findTop50ByPhoneOrderByCreatedAtDesc(phone);
        Collections.reverse(recent);
        return recent;
    }
}