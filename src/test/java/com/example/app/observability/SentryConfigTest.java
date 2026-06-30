package com.example.app.observability;

import io.sentry.Breadcrumb;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.User;
import jakarta.validation.constraints.Pattern;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.DataBinder;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One JUnit test per PII scrubber rule in {@link SentryConfig.PiiScrubbingBeforeSendCallback}.
 * Rule 1 has two arms (URL + breadcrumb), rule 6 has two arms (extras + message), and rule 9
 * additionally carries a negative test to prevent false positives on unrelated SQL exceptions.
 *
 * <p>Every test instantiates the callback directly — no Spring context — so a regression
 * fails fast at unit-test speed.
 */
class SentryConfigTest {

    private final SentryOptions.BeforeSendCallback callback = new SentryConfig.PiiScrubbingBeforeSendCallback();

    @Test
    void scrubsIcalTokenFromRequestUrl() {
        SentryEvent event = new SentryEvent();
        Request request = new Request();
        request.setUrl("https://ogarniacz.fly.dev/calendar/abc123def.ics");
        event.setRequest(request);

        SentryEvent scrubbed = callback.execute(event, new Hint());

        assertThat(scrubbed).isNotNull();
        assertThat(scrubbed.getRequest().getUrl())
                .isEqualTo("https://ogarniacz.fly.dev/calendar/[REDACTED].ics");
    }

    @Test
    void scrubsIcalTokenFromBreadcrumbs() {
        SentryEvent event = new SentryEvent();
        Breadcrumb breadcrumb = new Breadcrumb();
        breadcrumb.setData("url", "https://ogarniacz.fly.dev/calendar/secret-token.ics");
        breadcrumb.setData("method", "GET");
        List<Breadcrumb> breadcrumbs = new ArrayList<>();
        breadcrumbs.add(breadcrumb);
        event.setBreadcrumbs(breadcrumbs);

        SentryEvent scrubbed = callback.execute(event, new Hint());

        assertThat(scrubbed.getBreadcrumbs()).hasSize(1);
        assertThat(scrubbed.getBreadcrumbs().get(0).getData("url"))
                .isEqualTo("https://ogarniacz.fly.dev/calendar/[REDACTED].ics");
        // Non-PII data preserved.
        assertThat(scrubbed.getBreadcrumbs().get(0).getData("method")).isEqualTo("GET");
    }

    @Test
    void scrubsUserEmailFromUserUsername() {
        SentryEvent event = new SentryEvent();
        User user = new User();
        user.setId("42");
        user.setUsername("parent@example.com");
        user.setEmail("parent@example.com");
        event.setUser(user);

        SentryEvent scrubbed = callback.execute(event, new Hint());

        assertThat(scrubbed.getUser().getUsername()).isNull();
        assertThat(scrubbed.getUser().getEmail()).isNull();
        assertThat(scrubbed.getUser().getId()).isEqualTo("42");
    }

    @Test
    void scrubsPhotoBytesFromViewModelExtra() {
        SentryEvent event = new SentryEvent();
        Map<String, Object> sourceImageView = new HashMap<>();
        sourceImageView.put("data", new byte[]{1, 2, 3});
        sourceImageView.put("mimeType", "image/jpeg");
        event.setExtra("sourceImage", sourceImageView);
        event.setExtra("keep", "preserve");

        SentryEvent scrubbed = callback.execute(event, new Hint());

        assertThat(scrubbed.getExtras()).doesNotContainKey("sourceImage");
        assertThat(scrubbed.getExtras()).containsEntry("keep", "preserve");
    }

    @Test
    void scrubsRawLlmResponseFromExtraTag() {
        SentryEvent event = new SentryEvent();
        event.setExtra("rawResponse", "[{\"date\":\"2026-07-01\",\"title\":\"Adam urodziny\"}]");
        event.setExtra("imageHash", "abc123");

        SentryEvent scrubbed = callback.execute(event, new Hint());

        assertThat(scrubbed.getExtras()).doesNotContainKey("rawResponse");
        assertThat(scrubbed.getExtras()).containsEntry("imageHash", "abc123");
    }

    @Test
    void scrubsRawBytesFromDataExtra() {
        SentryEvent event = new SentryEvent();
        event.setExtra("data", new byte[]{4, 5, 6, 7});

        SentryEvent scrubbed = callback.execute(event, new Hint());

        assertThat(scrubbed.getExtras()).doesNotContainKey("data");
    }

    @Test
    void scrubsInvalidValueFromFieldErrorExtra() {
        SentryEvent event = new SentryEvent();
        Map<String, Object> titleFieldError = new HashMap<>();
        titleFieldError.put("field", "title");
        titleFieldError.put("invalidValue", "Adelka K.");
        titleFieldError.put("code", "NotBlank");
        Map<String, Object> binding = new HashMap<>();
        binding.put("title", titleFieldError);
        event.setExtra("bindingResult.eventForm", binding);

        SentryEvent scrubbed = callback.execute(event, new Hint());

        @SuppressWarnings("unchecked")
        Map<String, Object> scrubbedBinding = (Map<String, Object>) scrubbed.getExtras().get("bindingResult.eventForm");
        @SuppressWarnings("unchecked")
        Map<String, Object> scrubbedTitle = (Map<String, Object>) scrubbedBinding.get("title");
        assertThat(scrubbedTitle.get("field")).isEqualTo("title");
        assertThat(scrubbedTitle.get("code")).isEqualTo("NotBlank");
        assertThat(scrubbedTitle.get("invalidValue")).isEqualTo("[REDACTED]");
    }

    @Test
    void scrubsRejectedValueFromExceptionMessage() {
        TitleForm form = new TitleForm();
        form.title = "Adelka K.";
        DataBinder binder = new DataBinder(form, "titleForm");
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        binder.setValidator(validator);
        binder.validate();
        BeanPropertyBindingResult bindingResult = (BeanPropertyBindingResult) binder.getBindingResult();
        // BindException renders the same `rejected value [...]` shape via DefaultMessageSourceResolvable —
        // exercises the actual production wire shape without needing a MethodParameter for MethodArgumentNotValidException.
        BindException springException = new BindException(bindingResult);

        SentryEvent event = new SentryEvent();
        SentryException sentryException = new SentryException();
        sentryException.setType("MethodArgumentNotValidException");
        sentryException.setValue(springException.getMessage());
        event.setExceptions(List.of(sentryException));

        // Sanity — Spring rendered the value into the message before we ran.
        assertThat(springException.getMessage()).contains("rejected value [Adelka K.]");

        SentryEvent scrubbed = callback.execute(event, new Hint());

        String value = scrubbed.getExceptions().get(0).getValue();
        assertThat(value).contains("rejected value [REDACTED]");
        assertThat(value).doesNotContain("Adelka");
        assertThat(value).doesNotContain("Adelka K.");
    }

    @Test
    void scrubsPersistentLoginsUsernameFromDbErrorContext() {
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        message.setFormatted("DataAccessException: persistent_logins.username='parent@example.com' duplicate key");
        event.setMessage(message);

        SentryEvent scrubbed = callback.execute(event, new Hint());

        String formatted = scrubbed.getMessage().getFormatted();
        assertThat(formatted).contains("persistent_logins");
        assertThat(formatted).contains("[REDACTED-EMAIL]");
        assertThat(formatted).doesNotContain("parent@example.com");
    }

    @Test
    void scrubsAuthorizationBearerFromMessage() {
        // (a) event.message
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        message.setFormatted("OpenRouter call failed: Authorization: Bearer sk-or-v1-abcdef1234567890");
        event.setMessage(message);

        // (b) exception value
        SentryException ex = new SentryException();
        ex.setType("RuntimeException");
        ex.setValue("Upstream rejected request — Authorization: Bearer sk-or-v1-xyz0987654321 invalid");
        event.setExceptions(List.of(ex));

        // (c) breadcrumb message
        Breadcrumb breadcrumb = new Breadcrumb();
        breadcrumb.setMessage("outbound HTTP 401 with Authorization: Bearer sk-or-v1-leak");
        List<Breadcrumb> breadcrumbs = new ArrayList<>();
        breadcrumbs.add(breadcrumb);
        event.setBreadcrumbs(breadcrumbs);

        SentryEvent scrubbed = callback.execute(event, new Hint());

        assertThat(scrubbed.getMessage().getFormatted())
                .isEqualTo("OpenRouter call failed: Authorization: Bearer [REDACTED]");
        assertThat(scrubbed.getExceptions().get(0).getValue())
                .contains("Authorization: Bearer [REDACTED]")
                .doesNotContain("sk-or-v1-xyz");
        assertThat(scrubbed.getBreadcrumbs().get(0).getMessage())
                .isEqualTo("outbound HTTP 401 with Authorization: Bearer [REDACTED]");
    }

    @Test
    void scrubsProposedEventRowDataFromJpaException() {
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        String leaky = "ERROR: duplicate key value violates unique constraint \"uniq_proposed_event_title\"\n"
                + "  Detail: Key (title)=(Adam urodziny) already exists in proposed_event.";
        message.setFormatted(leaky);
        event.setMessage(message);
        SentryException ex = new SentryException();
        ex.setType("org.postgresql.util.PSQLException");
        ex.setValue(leaky);
        event.setExceptions(List.of(ex));

        SentryEvent scrubbed = callback.execute(event, new Hint());

        assertThat(scrubbed.getMessage().getFormatted()).isEqualTo("[REDACTED SQL ROW DATA]");
        assertThat(scrubbed.getExceptions().get(0).getValue()).isEqualTo("[REDACTED SQL ROW DATA]");
        // Confirm the leak is absent from anywhere the scrubber touches.
        assertThat(scrubbed.getMessage().getFormatted()).doesNotContain("Adam urodziny");
        assertThat(scrubbed.getExceptions().get(0).getValue()).doesNotContain("Adam urodziny");
    }

    @Test
    void doesNotScrubUnrelatedSqlException() {
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        String unrelated = "ERROR: relation \"app_user\" does not exist";
        message.setFormatted(unrelated);
        event.setMessage(message);
        SentryException ex = new SentryException();
        ex.setType("org.postgresql.util.PSQLException");
        ex.setValue(unrelated);
        event.setExceptions(List.of(ex));

        SentryEvent scrubbed = callback.execute(event, new Hint());

        assertThat(scrubbed.getMessage().getFormatted()).isEqualTo(unrelated);
        assertThat(scrubbed.getExceptions().get(0).getValue()).isEqualTo(unrelated);
    }

    /** Throwaway DTO that drives a real Spring binding error in {@link #scrubsRejectedValueFromExceptionMessage()}. */
    static class TitleForm {
        // Pattern guarantees "Adelka K." fails validation and Spring renders the rejected value into the message.
        @Pattern(regexp = "^[0-9]+$")
        String title;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }
}
