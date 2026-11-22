package com.cactro.booking.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Sends real email. Two transports:
 *  - Brevo HTTPS API (BREVO_API_KEY set)  -> works on Render free tier, which BLOCKS SMTP ports 25/465/587.
 *  - SMTP via JavaMailSender (fallback)   -> works locally / on paid hosts.
 */
@Service
public class EmailService {
    private final JavaMailSender mailSender;
    private final String from, fromName, brevoKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    // Load-test accounts (@loadtest.dev) never get real mail (would spam / burn the email quota).
    // We model the real provider constraint instead: N concurrent connections, each ~latency ms.
    private final Semaphore smtpSlots;
    private final long simLatencyMs;

    public EmailService(JavaMailSender mailSender,
                        @Value("${app.mail-from}") String from,
                        @Value("${app.mail-from-name:Event Booking}") String fromName,
                        @Value("${app.brevo-api-key:}") String brevoKey,
                        @Value("${app.sim-smtp-connections:5}") int simConnections,
                        @Value("${app.sim-smtp-latency-ms:400}") long simLatencyMs) {
        this.mailSender = mailSender;
        this.from = from;
        this.fromName = fromName;
        this.brevoKey = brevoKey;
        this.smtpSlots = new Semaphore(simConnections, true);
        this.simLatencyMs = simLatencyMs;
    }

    public static boolean isLoadTest(String email) { return email.endsWith("@loadtest.dev"); }

    public void send(String to, String subject, String html) {
        if (isLoadTest(to)) { simulate(); return; }
        try {
            if (!brevoKey.isBlank()) sendViaBrevo(to, subject, html);
            else sendViaSmtp(to, subject, html);
        } catch (Exception e) {
            throw new RuntimeException("Email send failed: " + e.getMessage(), e);
        }
    }

    private void sendViaBrevo(String to, String subject, String html) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "sender", Map.of("name", fromName, "email", from),
                "to", List.of(Map.of("email", to)),
                "subject", subject,
                "htmlContent", html));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://api.brevo.com/v3/smtp/email"))
                .timeout(Duration.ofSeconds(15))
                .header("api-key", brevoKey).header("content-type", "application/json").header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new IllegalStateException("Brevo " + r.statusCode() + ": " + r.body());
    }

    private void sendViaSmtp(String to, String subject, String html) throws Exception {
        MimeMessage msg = mailSender.createMimeMessage();
        MimeMessageHelper h = new MimeMessageHelper(msg, "UTF-8");
        h.setFrom(from);
        h.setTo(to);
        h.setSubject(subject);
        h.setText(html, true);
        mailSender.send(msg);
    }

    private void simulate() {
        try {
            smtpSlots.acquire();
            try { Thread.sleep(simLatencyMs); } finally { smtpSlots.release(); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
