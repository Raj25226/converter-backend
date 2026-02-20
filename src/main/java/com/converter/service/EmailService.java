package com.converter.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:noreply@fileconvert.pro}")
    private String fromEmail;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Async
    public void sendVerificationEmail(String toEmail, String token) {
        String link = frontendUrl + "/verify-email?token=" + token;
        String html = buildEmailHtml(
                "Verify Your Email",
                "Welcome to FileConvert Pro!",
                "Please click the button below to verify your email address. This link expires in 24 hours.",
                link,
                "Verify Email"
        );
        sendHtmlEmail(toEmail, "Verify Your Email - FileConvert Pro", html);
    }

    @Async
    public void sendPasswordResetEmail(String toEmail, String token) {
        String link = frontendUrl + "/reset-password?token=" + token;
        String html = buildEmailHtml(
                "Reset Your Password",
                "Password Reset Request",
                "You requested a password reset. Click below to set a new password. This link expires in 1 hour. If you didn't request this, ignore this email.",
                link,
                "Reset Password"
        );
        sendHtmlEmail(toEmail, "Reset Your Password - FileConvert Pro", html);
    }

    private void sendHtmlEmail(String to, String subject, String htmlContent) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            mailSender.send(message);
            log.info("Email sent to {}", to);
        } catch (Exception e) {
            log.error("Failed to send email to {}: {}", to, e.getMessage());
        }
    }

    private String buildEmailHtml(String title, String heading, String body,
                                  String actionUrl, String actionLabel) {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"></head>
            <body style="font-family: Arial, sans-serif; background:#f4f4f4; padding:20px; margin:0;">
              <div style="max-width:600px; margin:0 auto; background:#ffffff; border-radius:8px;
                          box-shadow:0 2px 10px rgba(0,0,0,0.1); overflow:hidden;">
                <div style="background:#4f46e5; padding:30px; text-align:center;">
                  <h1 style="color:#ffffff; margin:0; font-size:24px;">FileConvert Pro</h1>
                </div>
                <div style="padding:40px;">
                  <h2 style="color:#1f2937; margin-top:0;">%s</h2>
                  <p style="color:#6b7280; font-size:16px; line-height:1.6;">%s</p>
                  <div style="text-align:center; margin:30px 0;">
                    <a href="%s" style="background:#4f46e5; color:#ffffff; text-decoration:none;
                                        padding:14px 32px; border-radius:6px; font-size:16px;
                                        font-weight:bold; display:inline-block;">%s</a>
                  </div>
                  <p style="color:#9ca3af; font-size:14px;">
                    Or copy this link:<br>
                    <a href="%s" style="color:#4f46e5; word-break:break-all;">%s</a>
                  </p>
                </div>
                <div style="background:#f9fafb; padding:20px; text-align:center; border-top:1px solid #e5e7eb;">
                  <p style="color:#9ca3af; font-size:12px; margin:0;">
                    © 2024 FileConvert Pro. All rights reserved.
                  </p>
                </div>
              </div>
            </body>
            </html>
            """.formatted(heading, body, actionUrl, actionLabel, actionUrl, actionUrl);
    }
}