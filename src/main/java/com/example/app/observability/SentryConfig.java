package com.example.app.observability;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.User;
import io.sentry.spring7.SentryTaskDecorator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

/**
 * Sentry wiring for the app — one place that holds:
 *
 * <ul>
 *   <li>the {@link SentryTaskDecorator} bean that propagates request scope
 *       (user, tags, breadcrumbs) onto {@code @Async} executor threads;</li>
 *   <li>the {@link SentryOptions.BeforeSendCallback} bean that scrubs PII
 *       before any event leaves the JVM;</li>
 *   <li>an errors-only runtime invariant check that fires on
 *       {@link ApplicationReadyEvent} and throws if {@code traces-sample-rate},
 *       {@code profile-session-sample-rate}, or {@code logs.enabled} drifted
 *       from the configured zero/false values.</li>
 * </ul>
 *
 * <p>The companion lessons.md entry ("Never log {@code ProposedEvent} /
 * {@code ExtractedEvent} entities (or their {@code toString()})") locks in the
 * one PII channel rule 9 cannot guarantee on its own — log {@code id} +
 * {@code status} only.
 */
@Configuration
public class SentryConfig {

    private static final Logger log = LoggerFactory.getLogger(SentryConfig.class);

    @Bean
    public SentryTaskDecorator sentryTaskDecorator() {
        return new SentryTaskDecorator();
    }

    @Bean
    public SentryOptions.BeforeSendCallback sentryBeforeSendCallback() {
        return new PiiScrubbingBeforeSendCallback();
    }

    /**
     * Errors-only runtime invariant — defense-in-depth alongside
     * {@code ErrorsOnlyEnforcementTest}. The test gates merge; this check gates
     * boot. Either alone has a hole the other closes (force-merge past CI / SDK
     * refactor that loads options post-context-init).
     *
     * <p>Skipped when {@code sentry.enabled=false} or {@code sentry.dsn} is
     * empty — under the e2e profile, during {@code ./gradlew test} (env
     * overrides), and on a developer's machine without a DSN, transport is off
     * and the invariant has no consumer.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void enforceErrorsOnlyInvariant(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        if (!env.getProperty("sentry.enabled", Boolean.class, Boolean.TRUE)) {
            return;
        }
        String dsn = env.getProperty("sentry.dsn", "");
        if (dsn == null || dsn.isBlank()) {
            return;
        }
        SentryOptions options = Sentry.getCurrentScopes().getOptions();
        Double tracesSampleRate = options.getTracesSampleRate();
        Double profileSessionSampleRate = options.getProfileSessionSampleRate();
        boolean logsEnabled = options.getLogs().isEnabled();
        if (tracesSampleRate == null || tracesSampleRate != 0.0
                || profileSessionSampleRate == null || profileSessionSampleRate != 0.0
                || logsEnabled) {
            throw new IllegalStateException(
                    "Sentry errors-only invariant violated: "
                            + "tracesSampleRate=" + tracesSampleRate
                            + " profileSessionSampleRate=" + profileSessionSampleRate
                            + " logs.enabled=" + logsEnabled
                            + " — expected 0.0 / 0.0 / false. "
                            + "See context/changes/sentry-instrumentation/plan.md.");
        }
        log.info("sentry.errors_only_invariant ok tracesSampleRate=0.0 profileSessionSampleRate=0.0 logs.enabled=false");
    }

    /**
     * The single bean that holds the 10 PII scrubbing rules. Package-visible so
     * {@code SentryConfigTest} can wire it directly without bootstrapping Spring.
     */
    static final class PiiScrubbingBeforeSendCallback implements SentryOptions.BeforeSendCallback {

        // Rule 1: /calendar/<token>.ics URL token.
        private static final Pattern ICAL_TOKEN_URL = Pattern.compile("/calendar/[^./]+\\.ics");
        private static final String ICAL_TOKEN_URL_REDACTED = "/calendar/[REDACTED].ics";

        // Rule 6b: rejected value [...] from Spring's DefaultMessageSourceResolvable.toString().
        private static final Pattern REJECTED_VALUE = Pattern.compile("rejected value \\[[^\\]]*\\]");
        private static final String REJECTED_VALUE_REDACTED = "rejected value [REDACTED]";

        // Rule 7: persistent_logins guard — redact email-shaped substrings on messages mentioning the table.
        // Pattern is intentionally conservative (alphanumerics + dot/dash/plus/underscore/percent) so it does
        // NOT eat surrounding tokens like `persistent_logins.username='…'`; only the email itself is replaced.
        private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
        private static final String EMAIL_REDACTED = "[REDACTED-EMAIL]";

        // Rule 8: Authorization: Bearer <token>.
        private static final Pattern BEARER = Pattern.compile("Authorization: Bearer [^\\s]+");
        private static final String BEARER_REDACTED = "Authorization: Bearer [REDACTED]";

        // Rule 9: known JPA / JDBC exception type suffixes.
        private static final Set<String> SQL_EXCEPTION_TYPE_SUFFIXES = Set.of(
                "PSQLException", "DataIntegrityViolationException", "SQLException");
        private static final String PROPOSED_EVENT_TABLE = "proposed_event";
        private static final String REDACTED_SQL_ROW_DATA = "[REDACTED SQL ROW DATA]";

        @Override
        public SentryEvent execute(SentryEvent event, Hint hint) {
            scrubRequestUrl(event);          // rule 1 (url arm)
            scrubBreadcrumbUrls(event);      // rule 1 (breadcrumb arm)
            scrubUserUsername(event);        // rule 2
            removeViewModelExtras(event);    // rules 3, 4, 5
            scrubBindingExtras(event);       // rule 6a
            scrubProposedEventSqlLeak(event); // rule 9 (runs before message/exception text scrubbers)
            scrubMessages(event);            // rules 6b, 7, 8 (event.message + exception values + breadcrumb messages)
            return event;
        }

        // -------- rule 1 --------

        private static void scrubRequestUrl(SentryEvent event) {
            Request request = event.getRequest();
            if (request == null) {
                return;
            }
            String url = request.getUrl();
            if (url != null && url.contains("/calendar/")) {
                request.setUrl(ICAL_TOKEN_URL.matcher(url).replaceAll(ICAL_TOKEN_URL_REDACTED));
            }
        }

        private static void scrubBreadcrumbUrls(SentryEvent event) {
            List<Breadcrumb> breadcrumbs = event.getBreadcrumbs();
            if (breadcrumbs == null) {
                return;
            }
            for (Breadcrumb breadcrumb : breadcrumbs) {
                Map<String, Object> data = breadcrumb.getData();
                if (data == null) {
                    continue;
                }
                Object urlObj = data.get("url");
                if (urlObj instanceof String url && url.contains("/calendar/")) {
                    breadcrumb.setData("url", ICAL_TOKEN_URL.matcher(url).replaceAll(ICAL_TOKEN_URL_REDACTED));
                }
            }
        }

        // -------- rule 2 --------

        private static void scrubUserUsername(SentryEvent event) {
            User user = event.getUser();
            if (user != null) {
                user.setUsername(null);
                user.setEmail(null);
            }
        }

        // -------- rules 3, 4, 5 --------

        private static void removeViewModelExtras(SentryEvent event) {
            Map<String, Object> extras = event.getExtras();
            if (extras == null) {
                return;
            }
            event.removeExtra("sourceImage");
            event.removeExtra("rawResponse");
            event.removeExtra("data");
        }

        // -------- rule 6a --------

        private static void scrubBindingExtras(SentryEvent event) {
            Map<String, Object> extras = event.getExtras();
            if (extras == null || extras.isEmpty()) {
                return;
            }
            // Iterate a defensive copy of the key set — we mutate extras via setExtra.
            for (String key : new LinkedHashSet<>(extras.keySet())) {
                if (!key.startsWith("bindingResult")) {
                    continue;
                }
                Object value = extras.get(key);
                Object redacted = redactBindingValue(value);
                if (redacted != value) {
                    event.setExtra(key, redacted);
                }
            }
        }

        @SuppressWarnings("unchecked")
        private static Object redactBindingValue(Object value) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new HashMap<>();
                boolean changed = false;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String entryKey = String.valueOf(entry.getKey());
                    Object entryValue = entry.getValue();
                    if (entryValue instanceof Map<?, ?> nested && nested.containsKey("invalidValue")) {
                        Map<String, Object> nestedCopy = new HashMap<>((Map<String, Object>) nested);
                        nestedCopy.put("invalidValue", "[REDACTED]");
                        copy.put(entryKey, nestedCopy);
                        changed = true;
                    } else if ("invalidValue".equals(entryKey)) {
                        copy.put(entryKey, "[REDACTED]");
                        changed = true;
                    } else {
                        copy.put(entryKey, entryValue);
                    }
                }
                return changed ? copy : value;
            }
            return value;
        }

        // -------- rule 9 --------

        private static void scrubProposedEventSqlLeak(SentryEvent event) {
            List<SentryException> exceptions = event.getExceptions();
            if (exceptions == null || exceptions.isEmpty()) {
                return;
            }
            boolean sqlTypeMatches = false;
            for (SentryException ex : exceptions) {
                String type = ex.getType();
                if (type == null) {
                    continue;
                }
                for (String suffix : SQL_EXCEPTION_TYPE_SUFFIXES) {
                    if (type.endsWith(suffix)) {
                        sqlTypeMatches = true;
                        break;
                    }
                }
                if (sqlTypeMatches) {
                    break;
                }
            }
            if (!sqlTypeMatches) {
                return;
            }
            Message message = event.getMessage();
            String messageText = message == null ? null : firstNonNull(message.getFormatted(), message.getMessage());
            boolean messageMentionsTable = messageText != null && messageText.contains(PROPOSED_EVENT_TABLE);
            boolean exceptionMentionsTable = false;
            for (SentryException ex : exceptions) {
                String value = ex.getValue();
                if (value != null && value.contains(PROPOSED_EVENT_TABLE)) {
                    exceptionMentionsTable = true;
                    break;
                }
            }
            if (!messageMentionsTable && !exceptionMentionsTable) {
                return;
            }
            if (message != null) {
                message.setFormatted(REDACTED_SQL_ROW_DATA);
                message.setMessage(REDACTED_SQL_ROW_DATA);
            } else {
                Message replacement = new Message();
                replacement.setFormatted(REDACTED_SQL_ROW_DATA);
                event.setMessage(replacement);
            }
            for (SentryException ex : exceptions) {
                String value = ex.getValue();
                if (value != null && value.contains(PROPOSED_EVENT_TABLE)) {
                    ex.setValue(REDACTED_SQL_ROW_DATA);
                }
            }
        }

        // -------- rules 6b, 7, 8 --------

        private static void scrubMessages(SentryEvent event) {
            Message message = event.getMessage();
            if (message != null) {
                String formatted = message.getFormatted();
                String redactedFormatted = scrubText(formatted);
                if (!java.util.Objects.equals(formatted, redactedFormatted)) {
                    message.setFormatted(redactedFormatted);
                }
                String raw = message.getMessage();
                String redactedRaw = scrubText(raw);
                if (!java.util.Objects.equals(raw, redactedRaw)) {
                    message.setMessage(redactedRaw);
                }
            }
            List<SentryException> exceptions = event.getExceptions();
            if (exceptions != null) {
                for (SentryException ex : exceptions) {
                    String value = ex.getValue();
                    String redacted = scrubText(value);
                    if (!java.util.Objects.equals(value, redacted)) {
                        ex.setValue(redacted);
                    }
                }
            }
            List<Breadcrumb> breadcrumbs = event.getBreadcrumbs();
            if (breadcrumbs != null) {
                for (Breadcrumb breadcrumb : breadcrumbs) {
                    String msg = breadcrumb.getMessage();
                    String redacted = scrubText(msg);
                    if (!java.util.Objects.equals(msg, redacted)) {
                        breadcrumb.setMessage(redacted);
                    }
                }
            }
        }

        private static String scrubText(String text) {
            if (text == null || text.isEmpty()) {
                return text;
            }
            String result = text;
            // Rule 6b — Spring binding error rendered value.
            result = REJECTED_VALUE.matcher(result).replaceAll(REJECTED_VALUE_REDACTED);
            // Rule 7 — persistent_logins guard for email-shaped substrings.
            if (result.contains("persistent_logins")) {
                Matcher m = EMAIL.matcher(result);
                result = m.replaceAll(EMAIL_REDACTED);
            }
            // Rule 8 — Authorization: Bearer body-content scrub.
            result = BEARER.matcher(result).replaceAll(BEARER_REDACTED);
            return result;
        }

        private static String firstNonNull(String a, String b) {
            return a != null ? a : b;
        }
    }
}
