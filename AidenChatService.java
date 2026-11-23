package za.co.nmas.nmas.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import za.co.nmas.nmas.entity.Consultation;
import za.co.nmas.nmas.entity.Doctor;
import za.co.nmas.nmas.entity.Facility;
import za.co.nmas.nmas.entity.Patient;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AidenChatService - the AIDEN triage assistant that runs natively inside NMAS.
 *
 * What it does on EVERY patient message:
 *   1. Analyses the message with the AI model (new facts, denials such as "no fever", questions asked,
 *      danger signs, language used) and merges the facts into the conversation's clinical state.
 *   2. Decides an urgency level: EMERGENCY (ambulance now), URGENT (hospital emergency unit today),
 *      SOON (a doctor consultation should be arranged) or ROUTINE (self-care, no doctor needed).
 *   3. Writes a reply that first answers what the patient actually said or asked, then asks the single
 *      most useful missing question - in the patient's own South African language.
 *   4. Rebuilds a live "risk report" from that state (urgency, what was captured, how complete the
 *      information is, ranked possibilities once there is enough to rank, warning signs, self-care).
 *
 * Safety nets that do NOT depend on the AI answering: a negation-aware danger-sign check on every
 * message that jumps straight to the emergency screen, and a limited offline mode if the AI is down.
 * A doctor is suggested only when level == SOON (a consultation has to be arranged); emergencies get an
 * ambulance, urgent cases get the nearest hospital, routine cases get self-care advice.
 */
@Service
public class AidenChatService {

    // ------------------------------------------------------------------
    //  Configuration and collaborators
    // ------------------------------------------------------------------
    private static RestTemplate buildRestTemplate() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8000);
        factory.setReadTimeout(40000);
        return new RestTemplate(factory);
    }

    private final RestTemplate restTemplate = buildRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DoctorService doctorService;
    private final PatientService patientService;
    private final ConsultationService consultationService;
    private final FacilityGeoService facilityGeoService;
    private final DoctorAvailabilityService availabilityService;

    /** Set aiden.ai.api-key in application.properties, or the AIDEN_AI_API_KEY environment variable. No key lives in the source code. */
    @Value("${aiden.ai.api-key:}")
    private String apiKey;

    @Value("${aiden.ai.endpoint:https://api.groq.com/openai/v1/chat/completions}")
    private String endpoint;

    @Value("${aiden.ai.model:openai/gpt-oss-120b}")
    private String model;

    /** Comma-separated models tried, in order, if the main model is unavailable. */
    @Value("${aiden.ai.fallback-models:openai/gpt-oss-20b,llama-3.1-8b-instant}")
    private String fallbackModels;

    /** gpt-oss models only: low | medium | high. Medium is used automatically once the assessment is near. */
    @Value("${aiden.ai.reasoning-effort:low}")
    private String reasoningEffort;

    private static final DateTimeFormatter HISTORY_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");

    /** Server-side conversation state, keyed by the browser-generated sessionId. */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public AidenChatService(DoctorService doctorService, PatientService patientService,
                            ConsultationService consultationService, FacilityGeoService facilityGeoService,
                            DoctorAvailabilityService availabilityService) {
        this.doctorService = doctorService;
        this.patientService = patientService;
        this.consultationService = consultationService;
        this.facilityGeoService = facilityGeoService;
        this.availabilityService = availabilityService;
    }

    // ------------------------------------------------------------------
    //  South African languages only
    // ------------------------------------------------------------------
    public static final String AUTO = "Auto";
    public static final List<String> LANGUAGES = List.of("English", "Afrikaans", "isiZulu", "isiXhosa", "Sepedi",
            "Setswana", "Sesotho", "Xitsonga", "siSwati", "Tshivenda", "isiNdebele");

    private static final Map<String, String> LANGUAGE_ALIASES = new LinkedHashMap<>();
    static {
        for (String l : LANGUAGES) LANGUAGE_ALIASES.put(l.toLowerCase(Locale.ROOT), l);
        String[][] more = {
            {"en", "English"}, {"af", "Afrikaans"},
            {"zulu", "isiZulu"}, {"zu", "isiZulu"},
            {"xhosa", "isiXhosa"}, {"xh", "isiXhosa"},
            {"northern sotho", "Sepedi"}, {"sesotho sa leboa", "Sepedi"}, {"pedi", "Sepedi"}, {"nso", "Sepedi"},
            {"tswana", "Setswana"}, {"tn", "Setswana"},
            {"southern sotho", "Sesotho"}, {"sotho", "Sesotho"}, {"st", "Sesotho"},
            {"tsonga", "Xitsonga"}, {"ts", "Xitsonga"},
            {"swati", "siSwati"}, {"swazi", "siSwati"}, {"ss", "siSwati"},
            {"venda", "Tshivenda"}, {"ve", "Tshivenda"},
            {"ndebele", "isiNdebele"}, {"south ndebele", "isiNdebele"}, {"southern ndebele", "isiNdebele"}, {"nr", "isiNdebele"}
        };
        for (String[] m : more) LANGUAGE_ALIASES.put(m[0], m[1]);
    }

    /** Returns the canonical name of one of the 11 supported languages, or null if the text is not one of them. */
    static String normaliseLanguage(String raw) {
        if (raw == null) return null;
        String k = raw.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) return null;
        return LANGUAGE_ALIASES.get(k);
    }

    // ------------------------------------------------------------------
    //  Urgency levels
    // ------------------------------------------------------------------
    private static final List<String> LEVELS = List.of("ROUTINE", "SOON", "URGENT", "EMERGENCY");

    private static int levelRank(String level) { return Math.max(0, LEVELS.indexOf(level)); }

    private static String levelLabel(String level) {
        switch (level) {
            case "EMERGENCY": return "Call an ambulance now";
            case "URGENT": return "Go to a hospital emergency unit today";
            case "SOON": return "See a doctor soon";
            default: return "Can be managed at home for now";
        }
    }

    private static int clampScore(String level, int score) {
        int lo, hi;
        switch (level) {
            case "EMERGENCY": lo = 85; hi = 100; break;
            case "URGENT": lo = 60; hi = 84; break;
            case "SOON": lo = 30; hi = 59; break;
            default: lo = 0; hi = 29; break;
        }
        if (score <= 0) return (lo + hi) / 2;
        return Math.max(lo, Math.min(hi, score));
    }

    // ------------------------------------------------------------------
    //  The instructions given to the AI model
    // ------------------------------------------------------------------
    private static final String BASE_SYSTEM_PROMPT = """
        You are AIDEN, the AI health triage assistant inside NMAS, the National Medical Access System used by hospitals in South Africa. You are an AI, not a doctor. You never diagnose. You work out how urgent the patient's situation is, explain it clearly, and tell them exactly what to do next.

        OUTPUT: reply with exactly ONE JSON object and nothing else (no markdown, no code fences). The schema is at the end of these instructions.

        HOW EVERY TURN WORKS
        1. ANALYSE the patient's latest message before writing anything. Work out: what new facts they gave, what they corrected, what they DENIED ("no fever" means no fever), whether they asked you a question, and whether anything in the whole conversation points to danger. Negation matters: "I do not have chest pain" is NOT chest pain.
        2. DECIDE the urgency level (below) from everything known so far, not only the last message. Re-decide every turn; it can go up or down as you learn more.
        3. WRITE the reply:
           - First respond to what the patient just said. If they asked a question, answer it fully and correctly before anything else. If you are not sure of a medical fact, say so instead of guessing.
           - Then, only if you still need information, ask ONE question: the single most useful one for deciding urgency. Never ask anything already in KNOWN FACTS. Never ask two questions in one message.
           - If an answer is vague or partial, use what is usable and move on. Ask a follow-up only when it matters for safety.
           - If the message is small talk or unrelated, answer briefly and kindly, then steer back to how they feel.
           - Show that you understood their specific words. Never reply with a stock "thank you" followed by the next scripted question. Never repeat an earlier reply.
        4. After the assessment is given the conversation continues. The patient may ask follow-up questions or tell you something new; answer fully, and update the urgency level if what they say changes it.

        URGENCY LEVELS
        EMERGENCY = call an ambulance now (10177, or 112 from a cell phone). Use it when ANY of these is present now or just happened: chest pain, pressure or tightness (especially with sweating, nausea, or spreading to the arm or jaw); severe trouble breathing, cannot speak full sentences, blue or grey lips; stroke signs (face drooping, arm weakness, slurred speech, sudden confusion or sudden loss of vision); a seizure that is happening, lasts more than 5 minutes, or is a first-ever seizure; unconscious, unresponsive, or collapsed and not waking normally; severe bleeding that will not stop, vomiting or coughing blood; severe allergic reaction (swollen face, tongue or throat, or trouble breathing after a sting, food or medicine); poisoning, overdose or swallowing something harmful; snake bite; severe burns or electric shock; serious head injury, or injury from a gun, knife or road accident; suicidal thoughts with a plan or the means; a sudden "worst headache of my life"; pregnancy with heavy bleeding, severe abdominal pain, fits, or a baby that has stopped moving together with other danger signs; a baby under 3 months with fever, or a child who is floppy, will not wake, or is struggling to breathe; very high or very low blood sugar with confusion. If you would call an ambulance for your own family member, choose EMERGENCY.
        URGENT = needs a hospital emergency unit (casualty) within the next few hours; an ambulance is not essential but waiting at home is not safe. Examples: fever of 39 degrees or more with a stiff neck, rash or confusion, or fever in an older person or child; vomiting or diarrhoea that will not stop with signs of dehydration; severe pain (7 or more out of 10) without the emergency signs above; a suspected broken bone, deep cut or animal bite; breathing difficulty that is mild to moderate and not settling; asthma not helped by the usual reliever; pregnancy-related bleeding, reduced baby movement, severe headache or swollen face; blood in urine or stool; fever in a baby of 3 to 12 months; people with HIV, diabetes, heart disease, asthma or other chronic illness who are getting worse fast.
        SOON = should see a doctor within a few days. A consultation should be arranged, but there is no emergency now.
        ROUTINE = mild and short-lived; safe to manage at home with self-care and warning signs. No doctor is needed now.
        needs_consultation is true ONLY when the level is SOON. EMERGENCY and URGENT mean "go for emergency care", never "book a doctor". ROUTINE means no doctor.
        Do not under-triage. When the information is thin and the symptom could be serious, choose the higher level and say what would make it lower.

        WHEN TO FINISH (assessment_ready = true)
        - Immediately when the level is EMERGENCY or URGENT. Do not ask further questions first.
        - Otherwise once you know enough to place the patient in a level: the main symptom, how long, how bad, who it is (age, and pregnancy if relevant), the danger signs relevant to that symptom, and any chronic illness, medication or allergy that matters. This is usually 3 to 6 questions, fewer when the picture is clear.
        - If PATIENT TURNS SO FAR is 7 or more, finish now with what you have.
        In the finishing reply: say in plain words what this could be (possibilities, never a diagnosis), how urgent it is, exactly what to do now, simple safe self-care, and the warning signs that should send them to hospital or call an ambulance. Keep it spoken-style and under about 120 words.

        SAFETY AND LOCAL RULES
        - Never give a drug dose, never prescribe, never tell anyone to stop their medication. General self-care (rest, fluids, cooling, positioning) is fine.
        - Suicidal thoughts or self-harm: respond with warmth, give SADAG 0800 567 567 (24 hours) and 112, and set the level to EMERGENCY.
        - Only South African numbers: ambulance 10177, any cell phone 112, police 10111, Poison Information 0861 555 777, SADAG 0800 567 567. Never mention 911, 999 or any other country's number.
        - Say "hospital" (or "hospital emergency unit"), not "clinic".
        - Children, pregnant women, older adults and people with chronic illness: be more cautious.
        - EMERGENCY reply: one to three short, calm sentences telling them to call 10177 or 112 now and to share their location. No questions, no doctor.

        STYLE
        - The reply is read aloud by text-to-speech: plain sentences only. No markdown, no bullet points, no asterisks, no emojis, no numbered lists.
        - Mid-conversation replies are short (about 25 to 60 words). Warm, calm, jargon-free.
        - Do not repeat the same disclaimer every turn. Say once, in the finishing reply, that this is an AI assessment and not a diagnosis.

        FIELD RULES
        - facts: only what the patient has actually said or what the NMAS record below contains. Never guess. Copy earlier facts forward and update them. Use "none" when the patient says they have none (for conditions, medication, allergies). Use "not applicable" for severity when it truly does not apply.
        - denied_symptoms: symptoms the patient said they do NOT have.
        - conditions (possibilities): at most 4. "percent" is the RELATIVE likelihood among the ones you list and should add up to 100. Only list conditions supported by what the patient said. Set "serious" to true for a dangerous condition that must be ruled out even if it is less likely. If the information is too thin to rank anything honestly, return an empty list.
        - watch_for, self_care, summary: fill them only when assessment_ready is true, otherwise use empty values.
        - specialization: only when needs_consultation is true; it must be one of: @@SPECS@@.
        - emergency_category: only when the level is EMERGENCY; one of CARDIAC, BREATHING, CHOKING, ALLERGY, STROKE, SEIZURE, UNCONSCIOUS, BLEEDING, POISON, SELF_HARM, SNAKEBITE, BURN, PREGNANCY, GENERAL.

        JSON SCHEMA (keys in English exactly as shown):
        {
          "language": "<the language of your reply, one of: @@LANGS@@>",
          "intent": "symptoms | question | answer | greeting | other",
          "facts": {"main_symptom": "", "onset_duration": "", "severity_1_10": "", "location": "", "associated_symptoms": [], "denied_symptoms": [], "age": "", "sex": "", "pregnant": "", "conditions": "", "medication": "", "allergies": ""},
          "red_flags": [],
          "triage": {"level": "EMERGENCY | URGENT | SOON | ROUTINE", "score": 0, "reasons": []},
          "triage_label": "<one short sentence for the patient saying what level means for them>",
          "needs_consultation": false,
          "assessment_ready": false,
          "emergency_category": "",
          "specialization": "",
          "reply": "<what the patient reads or hears>",
          "conditions": [{"name": "", "percent": 0, "reason": "<under 12 words>", "serious": false}],
          "watch_for": [],
          "self_care": [],
          "summary": "<two sentences for the patient>"
        }
        """;

    // ------------------------------------------------------------------
    //  Session model
    // ------------------------------------------------------------------
    private static class Session {
        final List<Map<String, String>> messages = new ArrayList<>();
        final Map<String, Object> facts = new LinkedHashMap<>();
        String patientContext = "";
        String preferredLanguage = AUTO;   // what the patient picked, or Auto
        String language = "English";       // language of the last reply
        boolean historyConsent = false;
        String patientEmail = null;
        Long patientFacilityId = null;
        boolean emergencySeen = false;
        boolean emergencyShown = false;
        boolean historyUsed = false;
        Double lat = null;
        Double lng = null;
        int userTurns = 0;
        String level = "ROUTINE";
        long lastUsed = System.currentTimeMillis();
        int offlineStep = 0;

        void touch() { lastUsed = System.currentTimeMillis(); }
        void addUser(String content) { userTurns++; messages.add(Map.of("role", "user", "content", content)); }
        void addAssistant(String content) { messages.add(Map.of("role", "assistant", "content", content == null ? "" : content)); }
    }

    public static class ConditionMatch {
        public final String name;
        public final int percent;
        public final String reason;
        public final boolean serious;
        ConditionMatch(String name, int percent, String reason, boolean serious) {
            this.name = name; this.percent = percent; this.reason = reason; this.serious = serious;
        }
    }

    /** Everything learned from analysing one patient message. */
    private static class Analysis {
        String language;
        String reply = "";
        String level = "SOON";
        String triageLabel;
        int score = 0;
        boolean needsConsultation = false;
        boolean ready = false;
        boolean degraded = false;
        String specialization;
        String emergencyCategory;
        String summary = "";
        List<String> reasons = new ArrayList<>();
        List<String> redFlags = new ArrayList<>();
        List<String> watchFor = new ArrayList<>();
        List<String> selfCare = new ArrayList<>();
        List<ConditionMatch> conditions = new ArrayList<>();
    }

    // ------------------------------------------------------------------
    //  Public API used by AiProxyController
    // ------------------------------------------------------------------

    /**
     * Begin a new session and return AIDEN's opening greeting. patientEmail (the logged-in patient) is used only
     * to pull their own NMAS history, and only if historyConsent is true. language is "Auto" or one of the 11
     * South African languages.
     */
    public Map<String, Object> start(String sessionId, String patientEmail, String language, boolean historyConsent) {
        purgeOldSessions();
        Session s = new Session();
        applyLanguagePreference(s, language);
        s.historyConsent = historyConsent;
        s.patientEmail = patientEmail;
        if (patientEmail != null) {
            patientService.findByEmail(patientEmail).ifPresent(p -> {
                if (p.getRegisteredFacility() != null) s.patientFacilityId = p.getRegisteredFacility().getId();
            });
        }
        s.patientContext = historyConsent ? buildPatientContext(patientEmail, s) : "";
        sessions.put(sessionId, s);

        synchronized (s) {
            // The opening turn: no patient words yet, so the model is told to greet and ask what is wrong.
            s.messages.add(Map.of("role", "user", "content",
                    "[The patient has just opened the chat and has not said anything yet. Greet them warmly in one or two short sentences, "
                  + "say you are AIDEN, an AI assistant and not a doctor, and ask what is troubling them today. Do not ask anything else.]"));
            Analysis a = analyse(s, true);
            s.messages.clear();
            if (a.language != null) s.language = a.language;
            s.addAssistant(a.reply);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("message", saSafe(a.reply));
            r.put("language", s.language);
            r.put("isDiagnosis", false);
            r.put("isEmergency", false);
            r.put("sessionExpired", false);
            if (a.degraded) r.put("degraded", true);
            return r;
        }
    }

    /** Send a patient message and return AIDEN's reply. lat/lng are optional (patient shared their location). */
    public Map<String, Object> message(String sessionId, String userMessage, String language, Double lat, Double lng) {
        Session found = sessions.get(sessionId);
        if (found == null) {
            // Self-heal instead of forcing a page refresh: continue in a fresh session (history consent stays off).
            found = new Session();
            sessions.put(sessionId, found);
        }
        final Session s = found;
        synchronized (s) {
            s.touch();
            applyLanguagePreference(s, language);
            if (lat != null && lng != null) { s.lat = lat; s.lng = lng; }

            String text = userMessage == null ? "" : userMessage.trim();
            if (text.isEmpty() || "null".equals(text)) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("message", "I did not catch that. Please tell me again how you are feeling.");
                r.put("language", s.language);
                r.put("isDiagnosis", false);
                r.put("isEmergency", s.emergencyShown);
                r.put("sessionExpired", false);
                return r;
            }
            s.addUser(text);

            // 1. Instant, rule-based danger-sign check. Does not wait for (or depend on) the AI.
            if (!s.emergencyShown) {
                String hit = dangerCategory(text);
                if (hit != null) {
                    s.emergencySeen = true;
                    return emergencyReply(s, hit, null, null);
                }
            }

            // 2. Full analysis of this message by the AI model.
            Analysis a = analyse(s, false);
            if (a.language != null) s.language = a.language;

            // A level never drops back out of an emergency that has already been shown in this chat.
            if (s.emergencyShown) a.level = "EMERGENCY";
            s.level = a.level;
            if ("EMERGENCY".equals(a.level) || "URGENT".equals(a.level)) a.ready = true;
            if (!"SOON".equals(a.level)) a.needsConsultation = false;
            a.score = clampScore(a.level, a.score);

            if ("EMERGENCY".equals(a.level) && !s.emergencyShown) {
                s.emergencySeen = true;
                String cat = validCategory(a.emergencyCategory);
                if (cat == null) cat = dangerCategory(allUserText(s));
                if (cat == null) cat = "GENERAL";
                return emergencyReply(s, cat, a.reply, a);
            }

            s.addAssistant(a.reply);
            return buildResponse(s, a);
        }
    }

    /** Called when the browser shares GPS during a chat: remember it and return the nearest hospital. */
    public Map<String, Object> locationShared(String sessionId, double lat, double lng) {
        Session s = sessions.get(sessionId);
        if (s == null) { s = new Session(); sessions.put(sessionId, s); }
        s.lat = lat;
        s.lng = lng;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        Map<String, Object> h = nearestHospital(s);
        if (h != null) out.put("hospital", h);
        return out;
    }

    /** Clear a session. */
    public void reset(String sessionId) {
        sessions.remove(sessionId);
    }

    private void purgeOldSessions() {
        if (sessions.size() < 100) return;
        long cutoff = System.currentTimeMillis() - 6L * 60 * 60 * 1000;
        sessions.entrySet().removeIf(e -> e.getValue().lastUsed < cutoff);
    }

    private void applyLanguagePreference(Session s, String language) {
        if (language == null || language.isBlank()) return;
        if (AUTO.equalsIgnoreCase(language.trim())) { s.preferredLanguage = AUTO; return; }
        String lang = normaliseLanguage(language);
        if (lang != null) { s.preferredLanguage = lang; s.language = lang; }
    }

    // ------------------------------------------------------------------
    //  Danger-sign detection (rule-based safety net)
    // ------------------------------------------------------------------
    private static String saSafe(String text) {
        if (text == null) return "";
        return text.replaceAll("\\b911\\b", "10177").replaceAll("\\b999\\b", "10177");
    }

    private static Pattern ci(String regex) { return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }

    /** In priority order. English and Afrikaans wording; every other language is covered by the AI analysis. */
    private static final Map<String, Pattern> DANGER = new LinkedHashMap<>();
    static {
        DANGER.put("SELF_HARM", ci("suicid|kill myself|end my life|self[- ]?harm|want to die|selfmoord|wil (my )?(self )?doodmaak|wil sterf"));
        DANGER.put("POISON", ci("overdos|(?<!food )poison(ed|ing)?\\b|swallowed (a lot of )?(pills|tablets|bleach|paraffin|poison)|vergiftig"));
        DANGER.put("CHOKING", ci("\\bchoking\\b|\\bchoked\\b|verstik"));
        DANGER.put("ALLERGY", ci("anaphyla|allergic reaction|swollen (tongue|throat|lips|face)"));
        DANGER.put("SNAKEBITE", ci("snake ?bite|bitten by a snake|slangbyt|deur 'n slang gebyt"));
        DANGER.put("CARDIAC", ci("chest pain|chest tight|pain in (my|the) chest|heart attack|borspyn|pyn in (my|die) bors"));
        DANGER.put("BREATHING", ci("can'?t breathe|cannot breathe|can'?t catch my breath|difficulty breathing|trouble breathing|short(ness)? of breath|struggling to breathe|not breathing|stopped breathing|kan nie (asem|asemhaal)|asemnood"));
        DANGER.put("STROKE", ci("\\bstroke\\b|face droop|slurred|worst headache|arm weakness|beroerte"));
        DANGER.put("SEIZURE", ci("seizure|convuls|\\bfitting\\b|stuipe"));
        DANGER.put("UNCONSCIOUS", ci("unconscious|passed out|unresponsive|not responding|collapsed|bewusteloos"));
        DANGER.put("BLEEDING", ci("severe bleeding|bleeding (heavily|a lot|badly|profusely)|won'?t stop bleeding|vomiting blood|coughing (up )?blood|bloei (baie|erg)|bloed (opgebring|gebraak)"));
    }
    private static final Pattern AFRIKAANS_HINT = ci("borspyn|asem|bewusteloos|beroerte|bloei|stuipe|selfmoord|vergiftig|slang|verstik");
    private static final Pattern NEGATOR = ci("(\\bno\\b|\\bnot\\b|n't\\b|\\bnever\\b|\\bwithout\\b|\\bnothing\\b|\\bgeen\\b|\\bnie\\b|free of)");
    private static final Pattern FIRST_PERSON = ci("\\bi\\b|\\bi'm\\b|\\bim\\b|\\bmy\\b|\\bme\\b|\\bhe\\b|\\bshe\\b|\\bhis\\b|\\bher\\b|\\bbaby\\b|\\bchild\\b|\\bmother\\b|\\bfather\\b|\\bmy\\b|\\bek\\b|\\bhy\\b|\\bsy\\b|\\bons\\b");

    /**
     * Returns the emergency category if the message reports a danger sign that is happening, or null.
     * Ignores negated mentions ("no chest pain") and general questions ("what causes chest pain?").
     */
    private String dangerCategory(String text) {
        if (text == null || text.isBlank()) return null;
        boolean generalQuestion = text.contains("?") && !FIRST_PERSON.matcher(text).find();
        if (generalQuestion) return null;
        for (Map.Entry<String, Pattern> e : DANGER.entrySet()) {
            Matcher m = e.getValue().matcher(text);
            while (m.find()) {
                int from = Math.max(0, m.start() - 28);
                String before = text.substring(from, m.start());
                int to = Math.min(text.length(), m.end() + 12);
                String after = text.substring(m.end(), to);
                boolean negated = NEGATOR.matcher(before).find()
                        || ci("^\\s*(nie|not)\\b").matcher(after).find();
                if (!negated) return e.getKey();
            }
        }
        return null;
    }

    private String allUserText(Session s) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> m : s.messages) if ("user".equals(m.get("role"))) sb.append(' ').append(m.get("content"));
        return sb.toString();
    }

    private static String validCategory(String c) {
        if (c == null) return null;
        String u = c.trim().toUpperCase(Locale.ROOT);
        return List.of("CARDIAC", "BREATHING", "CHOKING", "ALLERGY", "STROKE", "SEIZURE", "UNCONSCIOUS", "BLEEDING",
                "POISON", "SELF_HARM", "SNAKEBITE", "BURN", "PREGNANCY", "GENERAL").contains(u) ? u : null;
    }

    // ------------------------------------------------------------------
    //  Emergency screen
    // ------------------------------------------------------------------
    /** {title, what to do now (lines separated by \n), what a helper should do (lines separated by \n)} in English. */
    private String[] emergencyContent(String cat) {
        switch (cat) {
            case "CARDIAC": return new String[]{"Possible heart emergency",
                "Stop what you are doing and sit down, leaning back slightly.\nLoosen tight clothing and stay as calm and still as you can.\nIf you are not allergic to aspirin, chew one adult aspirin slowly.\nUnlock your door and keep your phone next to you.",
                "Stay with the person and keep them seated and calm.\nIf they stop responding and are not breathing normally, start chest compressions: push hard and fast in the centre of the chest, about 100 to 120 times a minute.\nSend someone to wait outside and guide the ambulance in."};
            case "BREATHING": return new String[]{"Breathing emergency",
                "Sit upright and lean slightly forward. Do not lie flat.\nLoosen clothing and open a window for air.\nUse your reliever inhaler now if you have one.\nTry slow breaths in through the nose and out through pursed lips.",
                "Stay with the person and help them sit upright.\nUse their reliever inhaler if they have one.\nIf they stop breathing, start CPR."};
            case "CHOKING": return new String[]{"Choking",
                "If you can still cough, keep coughing.\nIf you cannot breathe, speak or cough, get someone to help you straight away.",
                "Give up to 5 firm back blows between the shoulder blades.\nThen give up to 5 abdominal thrusts: stand behind them, fist above the belly button, pull sharply inwards and upwards.\nKeep alternating until the object comes out or they collapse; if they collapse, start CPR."};
            case "ALLERGY": return new String[]{"Severe allergic reaction",
                "Use your adrenaline auto-injector now if you have one.\nLie down with your legs raised. If breathing is hard, sit up instead.\nMove away from whatever caused the reaction.",
                "Help them use their adrenaline auto-injector in the outer thigh.\nStay with them and keep them lying down with legs raised, or sitting if breathing is hard.\nIf they stop breathing, start CPR."};
            case "STROKE": return new String[]{"Possible stroke",
                "Note the exact time the symptoms started and tell the paramedics.\nSit or lie down with your head slightly raised.\nDo not eat, drink or take any tablets.",
                "Check the face (drooping), arms (weakness) and speech (slurred).\nKeep them still and calm. Give no food, drink or tablets.\nIf they are unconscious but breathing, roll them onto their side."};
            case "SEIZURE": return new String[]{"Seizure",
                "If you feel one starting, lower yourself to the floor away from furniture and hard objects.\nCall for someone to come to you.",
                "Do not hold them down and do not put anything in their mouth.\nMove hard objects away and cushion their head.\nNote the time. When the shaking stops, roll them onto their side and stay with them."};
            case "UNCONSCIOUS": return new String[]{"Collapse or loss of consciousness",
                "If you feel faint, lie down straight away and raise your legs.\nCall someone to stay with you.",
                "Check if they respond and are breathing.\nIf they are breathing, roll them onto their side. Give no food or drink.\nIf they are not breathing normally, start chest compressions: hard and fast in the centre of the chest."};
            case "BLEEDING": return new String[]{"Serious bleeding",
                "Press firmly on the wound with a clean cloth. Do not lift it to look; add more cloth on top if it soaks through.\nRaise the injured limb if you can, and sit or lie down.",
                "Press firmly on the wound for them with a clean cloth or clothing.\nDo not remove anything stuck in the wound.\nKeep them warm, lying down, and talk to them calmly."};
            case "POISON": return new String[]{"Poisoning or overdose",
                "Do not make yourself vomit.\nKeep the container, packaging or plant so the paramedics can see what it was.\nCall the Poison Information Helpline on 0861 555 777 if you can.",
                "Keep them awake and talking if possible; if they are drowsy, place them on their side.\nTell the paramedics what was taken, how much and when.\nDo not give anything to drink unless a professional tells you to."};
            case "SNAKEBITE": return new String[]{"Snake bite",
                "Keep still and as calm as you can. Moving makes the venom spread faster.\nKeep the bitten arm or leg at or just below the level of your heart.\nTake off rings, watches and tight clothing before the limb swells.\nDo not cut the bite, suck it, apply ice, or tie a tight band.",
                "Keep the person lying down, calm and still.\nDo not try to catch or kill the snake. If it is safe, take a photo of it from a distance.\nNote the time of the bite and tell the paramedics."};
            case "BURN": return new String[]{"Serious burn",
                "Cool the burn under cool running water for 20 minutes.\nTake off rings and tight clothing near the burn, unless they are stuck to the skin.\nCover loosely with a clean, non-fluffy cloth or cling wrap.\nDo not use ice, butter or toothpaste, and do not burst blisters.",
                "Help them cool the burn under running water for 20 minutes.\nCover with a clean cloth or cling wrap and keep them warm.\nIf their clothes are on fire, stop, drop and roll, or smother the flames with a blanket."};
            case "PREGNANCY": return new String[]{"Pregnancy emergency",
                "Lie down on your left side and try to stay calm.\nDo not eat or drink anything.\nUse clean pads for any bleeding and keep them for the paramedics to see.\nHave your antenatal card and ID ready.",
                "Stay with her, keep her lying on her left side and keep her warm.\nNote how much she is bleeding and when the pain started.\nSend someone outside to guide the ambulance in."};
            case "SELF_HARM": return new String[]{"You are not alone",
                "Please call SADAG on 0800 567 567 (24 hours) or 112 right now.\nMove away from anything you could use to hurt yourself.\nCall someone you trust to come and be with you.",
                "Stay with them and do not leave them alone.\nListen calmly without judging.\nSafely remove tablets, sharp objects or other means, and call 112."};
            default: return new String[]{"Emergency",
                "Stay calm, sit or lie down somewhere safe.\nDo not drive yourself.\nUnlock the door and keep your phone with you.",
                "Stay with the person and watch their breathing.\nIf they are not responding and not breathing normally, start chest compressions.\nSend someone to wait outside and guide the ambulance in."};
        }
    }

    private List<Map<String, String>> emergencyNumbers(String cat) {
        List<Map<String, String>> list = new ArrayList<>();
        list.add(Map.of("label", "Ambulance", "number", "10177", "dial", "10177"));
        list.add(Map.of("label", "Emergency from a cell phone", "number", "112", "dial", "112"));
        if ("POISON".equals(cat)) list.add(Map.of("label", "Poison Information", "number", "0861 555 777", "dial", "0861555777"));
        if ("SELF_HARM".equals(cat)) list.add(Map.of("label", "SADAG (24 hours)", "number", "0800 567 567", "dial", "0800567567"));
        if ("GENERAL".equals(cat) || "BLEEDING".equals(cat)) list.add(Map.of("label", "Police", "number", "10111", "dial", "10111"));
        return list;
    }

    private final Map<String, List<String>> translationCache = new ConcurrentHashMap<>();

    /**
     * The emergency screen for the given category. modelIntro is the AI's own reply when the AI raised the emergency
     * (already in the patient's language); otherwise a standard sentence is used.
     */
    private Map<String, Object> emergencyReply(Session s, String cat, String modelIntro, Analysis a) {
        s.emergencyShown = true;
        s.emergencySeen = true;
        s.level = "EMERGENCY";

        // A rule hit on Afrikaans wording tells us the language when the patient left it on Auto.
        if (AUTO.equals(s.preferredLanguage) && modelIntro == null) {
            String lastUser = s.messages.isEmpty() ? "" : s.messages.get(s.messages.size() - 1).get("content");
            if (lastUser != null && AFRIKAANS_HINT.matcher(lastUser).find()) s.language = "Afrikaans";
        }

        String[] content = emergencyContent(cat);
        List<String> steps = new ArrayList<>(List.of(content[1].split("\n")));
        List<String> helper = new ArrayList<>(List.of(content[2].split("\n")));
        String intro = (modelIntro != null && !modelIntro.isBlank()) ? modelIntro
                : "This sounds like an emergency. Call 10177 (ambulance) or 112 from any cell phone right now. "
                + "I am not going to ask you more questions. Please share your location below so help and the nearest hospital can be found.";
        String title = content[0];

        boolean introNeedsTranslation = (modelIntro == null || modelIntro.isBlank());
        if (s.language != null && !"English".equalsIgnoreCase(s.language)) {
            List<String> lines = new ArrayList<>();
            lines.add(title);
            lines.addAll(steps);
            lines.addAll(helper);
            if (introNeedsTranslation) lines.add(intro);
            String key = cat + "|" + s.language + "|" + (introNeedsTranslation ? "i" : "n");
            List<String> tr = translationCache.get(key);
            if (tr == null) {
                tr = translateLines(lines, s.language);
                if (tr != null) translationCache.put(key, tr);
            }
            if (tr != null && tr.size() == lines.size()) {
                title = tr.get(0);
                steps = new ArrayList<>(tr.subList(1, 1 + steps.size()));
                helper = new ArrayList<>(tr.subList(1 + steps.size(), 1 + steps.size() + helper.size()));
                if (introNeedsTranslation) intro = tr.get(tr.size() - 1);
            }
        }
        intro = saSafe(intro);

        Map<String, Object> emergency = new LinkedHashMap<>();
        emergency.put("category", cat);
        emergency.put("title", title);
        emergency.put("numbers", emergencyNumbers(cat));
        emergency.put("steps", steps);
        emergency.put("bystander", helper);
        Map<String, Object> hospital = nearestHospital(s);
        if (hospital != null) emergency.put("hospital", hospital);
        emergency.put("locationShared", s.lat != null && s.lng != null);

        s.addAssistant(intro);

        Analysis report = a != null ? a : new Analysis();
        report.level = "EMERGENCY";
        report.ready = true;
        report.needsConsultation = false;
        report.score = clampScore("EMERGENCY", report.score);
        if (report.reasons.isEmpty()) report.reasons.add(emergencyContent(cat)[0]);
        report.reply = intro;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("message", intro);
        r.put("language", s.language);
        r.put("isDiagnosis", false);
        r.put("isEmergency", true);
        r.put("sessionExpired", false);
        r.put("emergency", emergency);
        r.put("triage", triageMap(s, report));
        r.put("report", reportMap(s, report));
        return r;
    }

    /** Translate lines into a South African language, keeping the line count and phone numbers; null on any problem. */
    private List<String> translateLines(List<String> lines, String language) {
        try {
            List<Map<String, String>> msgs = new ArrayList<>();
            msgs.add(Map.of("role", "system", "content",
                "You translate first-aid and emergency instructions for patients in South Africa. Translate each line into " + language
              + " as a native speaker would say it, using simple everyday words. Output exactly " + lines.size()
              + " lines in the same order, one translated line per input line, no numbering and no extra text. Keep every phone number exactly as written."));
            msgs.add(Map.of("role", "user", "content", String.join("\n", lines)));
            String text = completeText(msgs, 2500);
            if (text == null) return null;
            List<String> out = new ArrayList<>();
            for (String l : text.split("\n")) if (!l.isBlank()) out.add(saSafe(l.trim()));
            return out.size() == lines.size() ? out : null;
        } catch (Exception e) {
            System.err.println("[AIDEN] emergency translation failed: " + e);
            return null;
        }
    }

    // ------------------------------------------------------------------
    //  Analysis of one patient message
    // ------------------------------------------------------------------
    /** Core facts used to measure how complete the information is. Order = order shown in the report. */
    private static final String[][] FACT_LABELS = {
        {"main_symptom", "Main symptom"}, {"onset_duration", "Since when"}, {"severity_1_10", "Severity (1 to 10)"},
        {"location", "Where"}, {"associated_symptoms", "Other symptoms"}, {"denied_symptoms", "Does not have"},
        {"age", "Age"}, {"sex", "Sex"}, {"pregnant", "Pregnant"}, {"conditions", "Known conditions"},
        {"medication", "Current medication"}, {"allergies", "Allergies"}
    };

    private boolean has(Session s, String key) {
        Object v = s.facts.get(key);
        if (v == null) return false;
        if (v instanceof List) return !((List<?>) v).isEmpty();
        return !String.valueOf(v).isBlank();
    }

    /** The seven things needed for a reliable assessment. */
    private List<String> missingCore(Session s) {
        List<String> missing = new ArrayList<>();
        if (!has(s, "main_symptom")) missing.add("main symptom");
        if (!has(s, "onset_duration")) missing.add("how long it has been going on");
        if (!has(s, "severity_1_10")) missing.add("severity from 1 to 10");
        if (!has(s, "age")) missing.add("age");
        if (!has(s, "sex")) missing.add("sex");
        if (!has(s, "associated_symptoms") && !has(s, "denied_symptoms")) missing.add("other symptoms and danger signs");
        if (!has(s, "conditions") && !has(s, "medication") && !has(s, "allergies")) missing.add("chronic conditions, medication or allergies");
        return missing;
    }

    private int completeness(Session s) {
        return (int) Math.round((7 - missingCore(s).size()) * 100.0 / 7.0);
    }

    private Analysis analyse(Session s, boolean opening) {
        // Medium reasoning once the picture is nearly complete or the case may be serious; low otherwise (faster).
        String effort = (s.userTurns >= 4 || levelRank(s.level) >= 2) ? "medium" : reasoningEffort;

        List<String> tryModels = new ArrayList<>();
        tryModels.add(model);
        if (fallbackModels != null) {
            for (String m : fallbackModels.split(",")) {
                String t = m.trim();
                if (!t.isEmpty() && !tryModels.contains(t)) tryModels.add(t);
            }
        }

        String lastError = null;
        if (apiKey == null || apiKey.isBlank()) {
            lastError = "no API key configured (set aiden.ai.api-key or AIDEN_AI_API_KEY)";
        } else {
            for (String m : tryModels) {
                boolean jsonMode = true;
                int maxTokens = 2500;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        String text = callModel(s, m, maxTokens, jsonMode, effort);
                        Analysis a = parseAnalysis(text);
                        if (a == null) { lastError = m + ": unreadable reply"; maxTokens = 4000; continue; }
                        mergeFacts(s, text);
                        return a;
                    } catch (HttpStatusCodeException e) {
                        lastError = m + " HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString();
                        System.err.println("[AIDEN] AI API error " + lastError);
                        int code = e.getStatusCode().value();
                        if (code == 400 && jsonMode) { jsonMode = false; continue; }   // model without JSON mode: ask in plain text
                        if (code == 401 || code == 403 || code == 404 || code == 400) break;  // bad key or model: next model
                        if (code == 429) { try { Thread.sleep(1200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } }
                    } catch (Exception e) {
                        lastError = m + " " + e;
                        System.err.println("[AIDEN] Error calling AI (" + m + "): " + e);
                    }
                }
            }
        }
        System.err.println("[AIDEN] All AI attempts failed (" + lastError + ") - using limited offline mode.");
        return offlineAnalysis(s, opening);
    }

    private String callModel(Session s, String useModel, int maxTokens, boolean jsonMode, String effort) throws Exception {
        List<Map<String, String>> payload = new ArrayList<>();
        payload.add(Map.of("role", "system", "content", buildSystemPrompt(s)));
        int from = Math.max(0, s.messages.size() - 24);
        payload.addAll(s.messages.subList(from, s.messages.size()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", useModel);
        body.put("messages", payload);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.3);
        if (jsonMode) body.put("response_format", Map.of("type", "json_object"));
        if (useModel.contains("gpt-oss")) body.put("reasoning_effort", effort);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        ResponseEntity<String> response = restTemplate.postForEntity(endpoint, new HttpEntity<>(body, headers), String.class);
        JsonNode root = objectMapper.readTree(response.getBody());
        String text = root.path("choices").path(0).path("message").path("content").asText("");
        if (text.isBlank()) throw new IllegalStateException("empty reply from " + useModel);
        return text;
    }

    /** Plain text completion with the model fallbacks (used for translations). */
    private String completeText(List<Map<String, String>> messages, int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) return null;
        List<String> tryModels = new ArrayList<>();
        tryModels.add(model);
        if (fallbackModels != null) for (String m : fallbackModels.split(",")) { String t = m.trim(); if (!t.isEmpty() && !tryModels.contains(t)) tryModels.add(t); }
        for (String m : tryModels) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", m);
                body.put("messages", messages);
                body.put("max_tokens", maxTokens);
                body.put("temperature", 0.1);
                if (m.contains("gpt-oss")) body.put("reasoning_effort", "low");
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(apiKey);
                ResponseEntity<String> response = restTemplate.postForEntity(endpoint, new HttpEntity<>(body, headers), String.class);
                String text = objectMapper.readTree(response.getBody()).path("choices").path(0).path("message").path("content").asText("");
                if (!text.isBlank()) return text;
            } catch (Exception e) {
                System.err.println("[AIDEN] completion failed (" + m + "): " + e);
            }
        }
        return null;
    }

    private static String extractJson(String text) {
        int a = text.indexOf('{');
        int b = text.lastIndexOf('}');
        return (a >= 0 && b > a) ? text.substring(a, b + 1) : text;
    }

    private List<String> stringList(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || n.isMissingNode() || n.isNull()) return out;
        if (n.isArray()) { for (JsonNode x : n) { String t = x.asText("").trim(); if (!t.isEmpty()) out.add(saSafe(t)); } }
        else { String t = n.asText("").trim(); if (!t.isEmpty()) out.add(saSafe(t)); }
        return out;
    }

    /** Turns the model's JSON into an Analysis, or null if it is unusable. */
    private Analysis parseAnalysis(String text) {
        try {
            JsonNode n = objectMapper.readTree(extractJson(text));
            String reply = n.path("reply").asText("").trim();
            if (reply.isEmpty()) return null;
            Analysis a = new Analysis();
            a.reply = saSafe(reply);
            a.language = normaliseLanguage(n.path("language").asText(""));
            JsonNode t = n.path("triage");
            String level = t.path("level").asText("").trim().toUpperCase(Locale.ROOT);
            a.level = LEVELS.contains(level) ? level : "SOON";
            a.score = t.path("score").asInt(0);
            a.reasons = stringList(t.path("reasons"));
            a.triageLabel = n.path("triage_label").asText("").trim();
            a.needsConsultation = n.path("needs_consultation").asBoolean(false);
            a.ready = n.path("assessment_ready").asBoolean(false);
            a.emergencyCategory = n.path("emergency_category").asText("");
            a.specialization = n.path("specialization").asText("").trim();
            a.summary = saSafe(n.path("summary").asText("").trim());
            a.redFlags = stringList(n.path("red_flags"));
            a.watchFor = stringList(n.path("watch_for"));
            a.selfCare = stringList(n.path("self_care"));
            for (JsonNode c : n.path("conditions")) {
                String name = c.path("name").asText("").trim();
                if (name.isEmpty()) continue;
                int pct = Math.max(0, Math.min(100, c.path("percent").asInt(0)));
                a.conditions.add(new ConditionMatch(saSafe(name), pct, saSafe(c.path("reason").asText("").trim()), c.path("serious").asBoolean(false)));
                if (a.conditions.size() >= 4) break;
            }
            return a;
        } catch (Exception e) {
            return null;
        }
    }

    /** Carries the model's updated facts into the session so they are never lost between turns. */
    private void mergeFacts(Session s, String text) {
        try {
            JsonNode facts = objectMapper.readTree(extractJson(text)).path("facts");
            if (!facts.isObject()) return;
            for (String[] fl : FACT_LABELS) {
                JsonNode v = facts.path(fl[0]);
                if (v.isMissingNode() || v.isNull()) continue;
                if (v.isArray()) {
                    List<String> list = stringList(v);
                    if (!list.isEmpty()) s.facts.put(fl[0], list);
                } else {
                    String t = v.asText("").trim();
                    if (!t.isEmpty() && !"null".equalsIgnoreCase(t) && !"unknown".equalsIgnoreCase(t)) s.facts.put(fl[0], t);
                }
            }
        } catch (Exception ignored) { }
    }

    private String buildSystemPrompt(Session s) {
        StringBuilder sb = new StringBuilder(BASE_SYSTEM_PROMPT
                .replace("@@SPECS@@", String.join(", ", availableSpecializations()))
                .replace("@@LANGS@@", String.join(", ", LANGUAGES)));

        sb.append("\nLANGUAGE\n");
        if (AUTO.equals(s.preferredLanguage)) {
            sb.append("- The patient has not chosen a language. Work out which of these languages their latest message is written in: ")
              .append(String.join(", ", LANGUAGES)).append(", and reply in that language. ")
              .append("If the message is too short to tell (for example \"ok\" or \"yes\"), keep using ").append(s.language).append(". ")
              .append("If they mix languages, reply in the language that most of the message is in. If they write in a language that is not in the list, reply in English and tell them which languages you can use.\n");
        } else {
            sb.append("- The patient chose ").append(s.preferredLanguage).append(". Reply in ").append(s.preferredLanguage)
              .append(". Only if their latest message is clearly written in a different language from the list, reply in that language instead.\n");
        }
        sb.append("- Write natural, everyday, correct language the way a native speaker talks at home. Do not translate word for word from English. ")
          .append("Use simple words for someone with little health education. When no everyday word exists for a medical term, describe it in plain words and give the English term in brackets once. ")
          .append("Never switch to English in the middle of a reply. Every text meant for the patient (reply, triage_label, reasons, watch_for, self_care, summary, condition names and reasons) must be in the reply language. ")
          .append("JSON keys, the level values and the emergency_category stay in English.\n");

        if (s.patientContext != null && !s.patientContext.isBlank()) sb.append("\n").append(s.patientContext).append("\n");

        sb.append("\nKNOWN FACTS SO FAR (carry these forward and update them in your facts field): ");
        try { sb.append(objectMapper.writeValueAsString(s.facts)); } catch (Exception e) { sb.append("{}"); }
        List<String> missing = missingCore(s);
        sb.append("\nSTILL UNKNOWN: ").append(missing.isEmpty() ? "nothing essential" : String.join("; ", missing));
        sb.append("\nPATIENT TURNS SO FAR: ").append(s.userTurns);
        sb.append("\nURGENCY LEVEL AFTER THE PREVIOUS TURN: ").append(s.level);

        if (s.emergencyShown) {
            sb.append("\n\nEMERGENCY MODE: the emergency screen with the ambulance numbers, first-aid steps and nearest hospital has already been shown. ")
              .append("Keep the level at EMERGENCY. Reply in at most three short, calm sentences. Answer what they ask about what to do right now. ")
              .append("Do not suggest a doctor or book anything. Remind them to call 10177 or 112 if they have not. Set assessment_ready to true.");
        }
        return sb.toString();
    }

    private String buildPatientContext(String patientEmail, Session s) {
        if (patientEmail == null || patientEmail.isBlank()) return "";
        Patient patient = patientService.findByEmail(patientEmail).orElse(null);
        if (patient == null) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("PATIENT'S NMAS RECORD (their own history, shared by their choice). Use it to avoid asking what is already known and to check whether today's symptoms connect to something documented. ")
          .append("Put what you use into the facts field. Never invent details that are not listed here, and never mention that this section exists; draw on it naturally.\n");
        if (patient.getDateOfBirth() != null) {
            try { sb.append("Age: ").append(Period.between(patient.getDateOfBirth(), LocalDate.now()).getYears()).append("\n"); } catch (Exception ignored) { }
        }
        if (patient.getGender() != null && !patient.getGender().isBlank()) sb.append("Sex: ").append(patient.getGender()).append("\n");
        String chronic = patient.getChronicConditions();
        if (chronic != null && !chronic.isBlank() && !chronic.equalsIgnoreCase("none")) sb.append("Known chronic conditions: ").append(chronic).append("\n");
        String allergies = patient.getAllergies();
        if (allergies != null && !allergies.isBlank() && !allergies.equalsIgnoreCase("none")) sb.append("Allergies: ").append(allergies).append("\n");
        String meds = patient.getCurrentMedication();
        if (meds != null && !meds.isBlank() && !meds.equalsIgnoreCase("none")) sb.append("Current medication: ").append(meds).append("\n");

        List<Consultation> consultations = consultationService.findByPatientId(patient.getId());
        if (consultations != null && !consultations.isEmpty()) {
            sb.append("Past consultations, most recent first:\n");
            consultations.stream()
                    .filter(c -> c.getConsultationDate() != null)
                    .sorted(Comparator.comparing(Consultation::getConsultationDate).reversed())
                    .limit(5)
                    .forEach(c -> {
                        sb.append("  - ").append(HISTORY_DATE.format(c.getConsultationDate()));
                        if (c.getSymptoms() != null && !c.getSymptoms().isBlank()) sb.append(": reported symptoms: ").append(c.getSymptoms());
                        if (c.getDiagnosis() != null && !c.getDiagnosis().isBlank()) sb.append("; diagnosis: ").append(c.getDiagnosis());
                        sb.append("\n");
                    });
        } else {
            sb.append("No past consultations on record; this appears to be their first documented visit.\n");
        }
        s.historyUsed = true;
        return sb.toString();
    }

    // ------------------------------------------------------------------
    //  Building the response and the live risk report
    // ------------------------------------------------------------------
    private Map<String, Object> triageMap(Session s, Analysis a) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("level", a.level);
        t.put("label", a.triageLabel != null && !a.triageLabel.isBlank() ? a.triageLabel : levelLabel(a.level));
        t.put("score", clampScore(a.level, a.score));
        t.put("ambulance", "EMERGENCY".equals(a.level));
        t.put("hospitalNow", "EMERGENCY".equals(a.level) || "URGENT".equals(a.level));
        t.put("consultation", "SOON".equals(a.level) && a.needsConsultation);
        t.put("reasons", a.reasons);
        return t;
    }

    /** Scales the model's likelihoods so they add up to exactly 100, strongest first. */
    private List<Map<String, Object>> normalisedConditions(List<ConditionMatch> in) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (in == null || in.isEmpty()) return out;
        List<ConditionMatch> sorted = new ArrayList<>(in);
        sorted.sort((x, y) -> Integer.compare(y.percent, x.percent));
        int total = 0;
        for (ConditionMatch c : sorted) total += c.percent;
        int[] pct = new int[sorted.size()];
        int sum = 0;
        for (int i = 0; i < sorted.size(); i++) {
            pct[i] = total > 0 ? (int) Math.round(sorted.get(i).percent * 100.0 / total) : (int) Math.round(100.0 / sorted.size());
            sum += pct[i];
        }
        pct[0] += 100 - sum;   // rounding difference goes to the strongest possibility
        for (int i = 0; i < sorted.size(); i++) {
            ConditionMatch c = sorted.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name);
            m.put("percent", Math.max(0, pct[i]));
            m.put("reason", c.reason);
            m.put("serious", c.serious);
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> reportMap(Session s, Analysis a) {
        Map<String, Object> r = new LinkedHashMap<>();
        int completeness = completeness(s);
        r.put("final", a.ready);
        r.put("degraded", a.degraded);
        r.put("triage", triageMap(s, a));
        r.put("completeness", completeness);
        r.put("confidence", a.degraded ? "Limited" : completeness >= 75 ? "Good" : completeness >= 45 ? "Moderate" : "Preliminary");

        List<Map<String, String>> captured = new ArrayList<>();
        for (String[] fl : FACT_LABELS) {
            Object v = s.facts.get(fl[0]);
            if (v == null) continue;
            String val = v instanceof List ? String.join(", ", ((List<?>) v).stream().map(String::valueOf).toList()) : String.valueOf(v);
            if (val.isBlank()) continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("label", fl[1]);
            row.put("value", val);
            captured.add(row);
        }
        r.put("captured", captured);
        r.put("missing", missingCore(s));

        // Possibilities are only ranked once there is enough information to rank them honestly.
        boolean enough = has(s, "main_symptom") && completeness >= 40 && !a.degraded;
        List<Map<String, Object>> conditions = enough ? normalisedConditions(a.conditions) : new ArrayList<>();
        r.put("conditions", conditions);
        r.put("conditionsNote", enough
                ? "Relative likelihood between the possibilities listed, based only on what you have told AIDEN. Not a diagnosis."
                : "Possibilities appear once AIDEN knows enough about your symptoms to rank them honestly.");
        r.put("watchFor", a.watchFor);
        r.put("selfCare", a.selfCare);
        r.put("summary", a.summary);
        r.put("personalised", s.historyUsed);
        r.put("redFlags", a.redFlags);
        return r;
    }

    private Map<String, Object> buildResponse(Session s, Analysis a) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", saSafe(a.reply));
        response.put("language", s.language);
        response.put("isDiagnosis", a.ready);
        response.put("isEmergency", "EMERGENCY".equals(a.level) || s.emergencyShown);
        response.put("sessionExpired", false);
        response.put("triage", triageMap(s, a));
        Map<String, Object> report = reportMap(s, a);
        response.put("report", report);
        response.put("conditions", report.get("conditions"));
        if (a.degraded) response.put("degraded", true);

        if (s.emergencyShown) {
            Map<String, Object> h = nearestHospital(s);
            if (h != null) response.put("hospital", h);
            return response;
        }
        if ("URGENT".equals(a.level) && a.ready) {
            Map<String, Object> h = nearestHospital(s);
            if (h != null) response.put("hospital", h);
        }
        // A doctor is suggested ONLY when a consultation has to be arranged.
        if ("SOON".equals(a.level) && a.ready && a.needsConsultation) {
            String spec = a.specialization != null && !a.specialization.isBlank() ? a.specialization : "General Practitioner";
            Map<String, Object> doctor = pickDoctor(spec, s);
            if (doctor != null) {
                doctor.put("notes", consultationNotes(s, a));
                response.put("doctor", doctor);
            }
        }
        return response;
    }

    /** A short summary attached to a video-consultation request so the doctor sees what AIDEN gathered. */
    private String consultationNotes(Session s, Analysis a) {
        StringBuilder sb = new StringBuilder("AIDEN pre-assessment (AI, not a diagnosis). ");
        for (String[] fl : FACT_LABELS) {
            Object v = s.facts.get(fl[0]);
            if (v == null) continue;
            String val = v instanceof List ? String.join(", ", ((List<?>) v).stream().map(String::valueOf).toList()) : String.valueOf(v);
            if (!val.isBlank() && sb.length() < 380) sb.append(fl[1]).append(": ").append(val).append(". ");
        }
        String out = sb.toString().trim();
        return out.length() > 450 ? out.substring(0, 450) : out;
    }

    // ------------------------------------------------------------------
    //  Hospitals and doctors by location
    // ------------------------------------------------------------------
    private String mapsLink(Facility f) {
        if (f == null) return null;
        try {
            if (f.getLatitude() != null && f.getLongitude() != null)
                return "https://www.google.com/maps/dir/?api=1&destination=" + f.getLatitude() + "," + f.getLongitude();
            String q = f.getFacilityName() + " " + (f.getAddress() != null ? f.getAddress() : "") + " " + f.getCity();
            return "https://www.google.com/maps/search/?api=1&query=" + java.net.URLEncoder.encode(q.trim(), "UTF-8");
        } catch (Exception e) { return null; }
    }

    private Map<String, Object> facilityMap(Facility f, Session s, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", f.getFacilityName());
        m.put("type", f.getFacilityType());
        m.put("address", ((f.getAddress() != null ? f.getAddress() + ", " : "") + f.getCity()));
        m.put("phone", f.getPhoneNumber() != null ? f.getPhoneNumber() : "");
        if (s.lat != null && s.lng != null) {
            Double d = FacilityGeoService.distanceTo(f, s.lat, s.lng);
            if (d != null) m.put("distanceKm", Math.round(d * 10.0) / 10.0);
        }
        if (reason != null) m.put("reason", reason);
        m.put("mapsUrl", mapsLink(f));
        return m;
    }

    /** Nearest hospital to the shared location, else the patient's registered facility, else null. */
    private Map<String, Object> nearestHospital(Session s) {
        try {
            if (s.lat != null && s.lng != null) {
                Facility f = facilityGeoService.nearestEmergencyFacility(s.lat, s.lng);
                if (f != null) return facilityMap(f, s, "Nearest to your location");
            }
            if (s.patientEmail != null) {
                Patient p = patientService.findByEmail(s.patientEmail).orElse(null);
                if (p != null && p.getRegisteredFacility() != null)
                    return facilityMap(p.getRegisteredFacility(), s, "Your registered hospital");
            }
        } catch (Exception ignored) { }
        return null;
    }

    private boolean onVideoShift(Doctor d) {
        try { return availabilityService.isOnShiftNow(d.getId()); } catch (Exception e) { return false; }
    }

    /**
     * Chooses the doctor for a consultation that has to be arranged. With a shared location: the nearest hospital
     * that has this specialization. Without: the hospital the patient is registered at, then anywhere. Also offers
     * a video consultation with a matching doctor who is on a video shift right now, when there is one.
     */
    private Map<String, Object> pickDoctor(String specialization, Session s) {
        List<Doctor> matches = new ArrayList<>();
        try {
            for (Doctor d : doctorService.findAll()) {
                if (d.getSpecialization() != null && d.getSpecialization().equalsIgnoreCase(specialization)) matches.add(d);
            }
        } catch (Exception ignored) { }
        if (matches.isEmpty()) {
            Doctor fb = doctorService.findBySpecialization(specialization).orElse(null);
            if (fb == null) return null;
            matches.add(fb);
        }
        final Long home = s.patientFacilityId;
        final boolean hasLoc = s.lat != null && s.lng != null;
        matches.sort((a, b) -> {
            if (hasLoc) {
                double da = a.getFacility() != null ? java.util.Optional.ofNullable(FacilityGeoService.distanceTo(a.getFacility(), s.lat, s.lng)).orElse(1e9) : 2e9;
                double db = b.getFacility() != null ? java.util.Optional.ofNullable(FacilityGeoService.distanceTo(b.getFacility(), s.lat, s.lng)).orElse(1e9) : 2e9;
                return Double.compare(da, db);
            }
            boolean ha = home != null && a.getFacility() != null && home.equals(a.getFacility().getId());
            boolean hb = home != null && b.getFacility() != null && home.equals(b.getFacility().getId());
            return Boolean.compare(hb, ha);
        });
        Doctor chosen = matches.get(0);
        Facility fac = chosen.getFacility();
        boolean atHome = home != null && fac != null && home.equals(fac.getId());

        Map<String, Object> m = new LinkedHashMap<>();
        String docName = "Dr. " + chosen.getFullName();
        String spec = chosen.getSpecialization() != null ? chosen.getSpecialization() : specialization;
        String facName = fac != null ? fac.getFacilityName() : "NMAS hospital";
        m.put("doctorId", chosen.getId());
        m.put("name", docName);
        m.put("specialization", spec);
        m.put("hospital", facName);
        m.put("location", facName);
        m.put("phone", chosen.getPhoneNumber() != null ? chosen.getPhoneNumber() : (fac != null && fac.getPhoneNumber() != null ? fac.getPhoneNumber() : ""));
        if (fac != null) {
            m.put("address", (fac.getAddress() != null ? fac.getAddress() + ", " : "") + fac.getCity());
            m.put("mapsUrl", mapsLink(fac));
            if (hasLoc) {
                Double d = FacilityGeoService.distanceTo(fac, s.lat, s.lng);
                if (d != null) m.put("distanceKm", Math.round(d * 10.0) / 10.0);
            }
        }
        m.put("reason", atHome ? "This is the hospital where you are registered."
                : hasLoc ? "This is the closest hospital to your location with this specialist."
                : "This is the closest available specialist. Share your location to find the one nearest to you.");
        m.put("visitScript", "Go to " + facName + " and tell reception: \"I am here to see the " + spec + ", " + docName + ".\"");

        // Video consultation with someone who is on a video shift right now.
        for (Doctor d : matches) {
            if (onVideoShift(d)) {
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("doctorId", d.getId());
                v.put("name", "Dr. " + d.getFullName());
                v.put("hospital", d.getFacility() != null ? d.getFacility().getFacilityName() : "NMAS hospital");
                m.put("videoOption", v);
                break;
            }
        }

        List<Map<String, Object>> alts = new ArrayList<>();
        for (Doctor d : matches) {
            if (d == chosen || alts.size() >= 2) continue;
            if (d.getFacility() != null && fac != null && d.getFacility().getId().equals(fac.getId())) continue;
            Map<String, Object> alt = new LinkedHashMap<>();
            alt.put("name", "Dr. " + d.getFullName());
            alt.put("hospital", d.getFacility() != null ? d.getFacility().getFacilityName() : "NMAS hospital");
            if (hasLoc && d.getFacility() != null) {
                Double dist = FacilityGeoService.distanceTo(d.getFacility(), s.lat, s.lng);
                if (dist != null) alt.put("distanceKm", Math.round(dist * 10.0) / 10.0);
            }
            alts.add(alt);
        }
        if (!alts.isEmpty()) m.put("alternatives", alts);
        return m;
    }

    private List<String> availableSpecializations() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        try {
            for (Doctor d : doctorService.findAll()) {
                if (d.getSpecialization() != null && !d.getSpecialization().isBlank()) set.add(d.getSpecialization().trim());
            }
        } catch (Exception ignored) { }
        if (set.isEmpty()) set.addAll(List.of("General Practitioner", "Internal Medicine", "Cardiology", "Pediatrics",
                "Emergency Medicine", "Neurology", "Dermatology"));
        if (!set.contains("General Practitioner")) set.add("General Practitioner");
        return new ArrayList<>(set);
    }

    // ------------------------------------------------------------------
    //  Limited offline mode: used only when the AI service cannot be reached, so a patient is never left with a
    //  dead chat box. It records the patient's own answers (no invented conditions or percentages), still
    //  escalates a high pain score, and otherwise recommends a consultation because nothing could be assessed.
    // ------------------------------------------------------------------
    private static final String[] OFFLINE_QUESTIONS = {
        "On a scale of 1 to 10, how severe is it right now?",
        "Do you have any other symptoms, such as fever, vomiting, a rash, dizziness or trouble breathing?",
        "Do you have any known conditions or allergies, or take any regular medication? Please say none if not."
    };

    private Analysis offlineAnalysis(Session s, boolean opening) {
        Analysis a = new Analysis();
        a.degraded = true;
        a.language = "English";
        if (opening) {
            a.reply = "Hello, I am AIDEN, an AI assistant and not a doctor. My AI service is not reachable at the moment, so I will use a short guided check. "
                    + "What is your main symptom, and how long have you had it?";
            a.level = "ROUTINE";
            return a;
        }
        String last = s.messages.isEmpty() ? "" : s.messages.get(s.messages.size() - 1).get("content");
        switch (s.userTurns) {
            case 1: s.facts.put("main_symptom", last); break;
            case 2: s.facts.put("severity_1_10", last); break;
            case 3: s.facts.put("associated_symptoms", new ArrayList<>(List.of(last))); break;
            default: if (s.userTurns == 4) s.facts.put("conditions", last); break;
        }
        int severity = -1;
        Object sv = s.facts.get("severity_1_10");
        if (sv != null) {
            Matcher m = Pattern.compile("(\\d{1,2})").matcher(String.valueOf(sv));
            if (m.find()) severity = Integer.parseInt(m.group(1));
        }
        if (severity >= 8) {
            a.level = "URGENT";
            a.ready = true;
            a.reply = "That sounds like a lot of pain. Please go to the nearest hospital emergency unit today and do not wait at home. If it gets worse, or you struggle to breathe, have chest pain or heavy bleeding, call 10177 or 112 straight away.";
            a.watchFor.add("Trouble breathing, chest pain, heavy bleeding, confusion or fainting");
            return a;
        }
        if (s.userTurns <= OFFLINE_QUESTIONS.length) {
            a.level = "ROUTINE";
            a.reply = OFFLINE_QUESTIONS[s.userTurns - 1];
            return a;
        }
        a.level = "SOON";
        a.ready = true;
        a.needsConsultation = true;
        a.specialization = "General Practitioner";
        a.reply = "Thank you. I could not reach my AI assessment service, so I cannot give you a reliable assessment right now. "
                + "Because of that I suggest a doctor looks at this soon. If you get worse, struggle to breathe, have chest pain or heavy bleeding, call 10177 or 112 straight away.";
        a.watchFor.add("Trouble breathing, chest pain, heavy bleeding, confusion or fainting");
        a.summary = "AIDEN could not complete an AI assessment. Your answers have been recorded for the doctor.";
        return a;
    }

    // ------------------------------------------------------------------
    //  Diagnostics
    // ------------------------------------------------------------------
    /**
     * Visit /api/ai/diagnose while logged in to see why AIDEN might be falling back to offline mode:
     * missing or rejected API key, retired model, rate limit or blocked network. The key itself is never shown.
     */
    public Map<String, Object> diagnosePing() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyConfigured", apiKey != null && !apiKey.isBlank());
        out.put("endpoint", endpoint);
        out.put("model", model);
        out.put("fallbackModels", fallbackModels);
        if (apiKey == null || apiKey.isBlank()) {
            out.put("ok", false);
            out.put("error", "No API key. Set aiden.ai.api-key in application.properties or the AIDEN_AI_API_KEY environment variable.");
            return out;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", List.of(Map.of("role", "user", "content", "Reply with exactly: OK")));
            body.put("max_tokens", 200);
            if (model.contains("gpt-oss")) body.put("reasoning_effort", "low");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);
            ResponseEntity<String> response = restTemplate.postForEntity(endpoint, new HttpEntity<>(body, headers), String.class);
            out.put("ok", true);
            out.put("httpStatus", response.getStatusCode().value());
            out.put("reply", objectMapper.readTree(response.getBody()).path("choices").path(0).path("message").path("content").asText(""));
        } catch (HttpStatusCodeException e) {
            out.put("ok", false);
            out.put("httpStatus", e.getStatusCode().value());
            out.put("error", e.getResponseBodyAsString());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", String.valueOf(e));
        }
        return out;
    }
}
