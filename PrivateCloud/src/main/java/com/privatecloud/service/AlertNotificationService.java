package com.privatecloud.service;

import com.privatecloud.dto.NotificationSettingsRequest;
import com.privatecloud.dto.NotificationSettingsView;
import com.privatecloud.entity.AlertFeedbackBatchItem;
import com.privatecloud.entity.AlertHistory;
import com.privatecloud.entity.NotificationSettings;
import com.privatecloud.entity.User;
import com.privatecloud.repository.AlertFeedbackBatchItemRepository;
import com.privatecloud.repository.AlertHistoryRepository;
import com.privatecloud.repository.NotificationSettingsRepository;
import com.privatecloud.repository.UserRepository;
import com.privatecloud.security.AuthContext;
import com.privatecloud.security.AuthSession;
import jakarta.mail.BodyPart;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FlagTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AlertNotificationService {

    private static final Logger log = LoggerFactory.getLogger(AlertNotificationService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZONE);
    private static final DateTimeFormatter BATCH_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZONE);

    private static final int DEFAULT_INTERVAL_MINUTES = 10;
    private static final int MIN_INTERVAL_MINUTES = 1;
    private static final int MAX_INTERVAL_MINUTES = 1440;
    private static final int DEFAULT_SMTP_PORT = 587;
    private static final int DEFAULT_IMAP_SSL_PORT = 993;
    private static final int DEFAULT_IMAP_STARTTLS_PORT = 143;
    private static final int MAX_ERROR_LENGTH = 500;

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern MULTI_EMAIL_SPLITTER = Pattern.compile("[,;\\n\\r]+");
    private static final Pattern BATCH_ID_PATTERN = Pattern.compile("(?i)batch[_-]?id\\s*[:=]\\s*([a-zA-Z0-9_-]{8,64})");
    private static final Pattern SHORT_CODE_PATTERN = Pattern.compile("(?i)\\bA\\d{1,4}\\b");
    private static final Pattern FALSE_HINT_PATTERN = Pattern.compile("(?i)(\\u8BEF\\u62A5|\\u6709\\u8BEF|\\u8BEF\\u5224|\\u5047\\u8B66|\\u9519\\u62A5|false\\s*positive|false-positive|\\u6B63\\u5E38\\u6CE2\\u52A8|\\u65E0\\u9700\\u5904\\u7406|ignore)");
    private static final Pattern VALID_HINT_PATTERN = Pattern.compile("(?i)(\\u4E0D\\u662F\\u8BEF\\u62A5|\\u975E\\u8BEF\\u62A5|\\u6709\\u6548\\u544A\\u8B66|\\u771F\\u5B9E\\u544A\\u8B66|keep|valid|true\\s*positive|\\u4E0D\\u8981\\u6807\\u8BB0)");
    private static final Pattern FALSE_CODES_AFTER_HINT_PATTERN = Pattern.compile("(?i)(?:\\u8BEF\\u62A5|\\u6709\\u8BEF|\\u8BEF\\u5224|\\u9519\\u62A5|false\\s*positive|fp)\\s*[:\\uFF1A\\s]*((?:A\\d{1,4})(?:\\s*[\\u3001,\\uFF0C\\s]+\\s*A\\d{1,4}){0,20})");
    private static final Pattern FALSE_CODES_BEFORE_HINT_PATTERN = Pattern.compile("(?i)((?:A\\d{1,4})(?:\\s*[\\u3001,\\uFF0C\\s]+\\s*A\\d{1,4}){0,20})\\s*(?:\\u8BEF\\u62A5|\\u6709\\u8BEF|\\u8BEF\\u5224|\\u9519\\u62A5|false\\s*positive|fp)");
    private static final Pattern VALID_CODES_AFTER_HINT_PATTERN = Pattern.compile("(?i)(?:\\u6709\\u6548\\u544A\\u8B66|\\u4E0D\\u662F\\u8BEF\\u62A5|\\u975E\\u8BEF\\u62A5|valid|true\\s*positive)\\s*[:\\uFF1A\\s]*((?:A\\d{1,4})(?:\\s*[\\u3001,\\uFF0C\\s]+\\s*A\\d{1,4}){0,20})");
    private static final Pattern VALID_CODES_BEFORE_HINT_PATTERN = Pattern.compile("(?i)((?:A\\d{1,4})(?:\\s*[\\u3001,\\uFF0C\\s]+\\s*A\\d{1,4}){0,20})\\s*(?:\\u6709\\u6548\\u544A\\u8B66|\\u4E0D\\u662F\\u8BEF\\u62A5|\\u975E\\u8BEF\\u62A5|valid|true\\s*positive)");
    private static final String PROCESSED_FLAG = "pc-feedback-processed";
    private static final Pattern REPLY_BREAK_PATTERN = Pattern.compile(
            "(?i)^(from:|sent:|subject:|to:|cc:|on .*wrote:|-----original message-----|\\u53D1\\u4EF6\\u4EBA[:\\uFF1A]|\\u53D1\\u9001\\u65F6\\u95F4[:\\uFF1A]|\\u4E3B\\u9898[:\\uFF1A]|\\u5728 .* \\u5199\\u9053[:\\uFF1A])"
    );

    private final NotificationSettingsRepository settingsRepository;
    private final AlertHistoryRepository alertHistoryRepository;
    private final AlertFeedbackBatchItemRepository feedbackBatchItemRepository;
    private final UserRepository userRepository;
    private final AccessControlService accessControlService;
    private final NotificationSecretService notificationSecretService;
    private final AlertFeedbackAgentService feedbackAgentService;

    private final String defaultSmtpHost;
    private final int defaultSmtpPort;
    private final boolean defaultSmtpAuth;
    private final boolean defaultSmtpStarttlsEnable;
    private final boolean defaultSmtpSslEnable;
    private final String defaultSmtpUsername;
    private final String defaultSmtpPassword;
    private final String defaultSenderEmail;
    private final String defaultSenderName;
    private final String defaultImapHost;
    private final int defaultImapPort;
    private final boolean defaultImapStarttlsEnable;
    private final boolean defaultImapSslEnable;

    public AlertNotificationService(
            NotificationSettingsRepository settingsRepository,
            AlertHistoryRepository alertHistoryRepository,
            AlertFeedbackBatchItemRepository feedbackBatchItemRepository,
            UserRepository userRepository,
            AccessControlService accessControlService,
            NotificationSecretService notificationSecretService,
            AlertFeedbackAgentService feedbackAgentService,
            @Value("${spring.mail.host:}") String defaultSmtpHost,
            @Value("${spring.mail.port:587}") Integer defaultSmtpPort,
            @Value("${spring.mail.properties.mail.smtp.auth:true}") boolean defaultSmtpAuth,
            @Value("${spring.mail.properties.mail.smtp.starttls.enable:true}") boolean defaultSmtpStarttlsEnable,
            @Value("${spring.mail.properties.mail.smtp.ssl.enable:false}") boolean defaultSmtpSslEnable,
            @Value("${spring.mail.username:}") String defaultSmtpUsername,
            @Value("${spring.mail.password:}") String defaultSmtpPassword,
            @Value("${alert.notification.mail-from:}") String defaultSenderEmail,
            @Value("${alert.notification.mail-from-name:}") String defaultSenderName,
            @Value("${alert.notification.imap.host:}") String defaultImapHost,
            @Value("${alert.notification.imap.port:0}") Integer defaultImapPort,
            @Value("${alert.notification.imap.starttls.enable:true}") boolean defaultImapStarttlsEnable,
            @Value("${alert.notification.imap.ssl.enable:true}") boolean defaultImapSslEnable
    ) {
        this.settingsRepository = settingsRepository;
        this.alertHistoryRepository = alertHistoryRepository;
        this.feedbackBatchItemRepository = feedbackBatchItemRepository;
        this.userRepository = userRepository;
        this.accessControlService = accessControlService;
        this.notificationSecretService = notificationSecretService;
        this.feedbackAgentService = feedbackAgentService;
        this.defaultSmtpHost = normalize(defaultSmtpHost);
        this.defaultSmtpPort = sanitizeSmtpPort(defaultSmtpPort);
        this.defaultSmtpAuth = defaultSmtpAuth;
        this.defaultSmtpStarttlsEnable = defaultSmtpStarttlsEnable;
        this.defaultSmtpSslEnable = defaultSmtpSslEnable;
        this.defaultSmtpUsername = normalize(defaultSmtpUsername);
        this.defaultSmtpPassword = defaultSmtpPassword == null ? "" : defaultSmtpPassword;
        this.defaultSenderEmail = normalize(defaultSenderEmail);
        this.defaultSenderName = normalize(defaultSenderName);
        this.defaultImapHost = normalize(defaultImapHost);
        this.defaultImapPort = defaultImapPort == null ? 0 : Math.max(defaultImapPort, 0);
        this.defaultImapStarttlsEnable = defaultImapStarttlsEnable;
        this.defaultImapSslEnable = defaultImapSslEnable;
    }

    @Transactional(readOnly = true)
    public NotificationSettingsView getSettingsView() {
        return toView(getOrCreateSettings(requireCurrentUserId()));
    }

    @Transactional
    public NotificationSettingsView updateSettings(NotificationSettingsRequest request) {
        NotificationSettings settings = getOrCreateSettings(requireCurrentUserId());
        if (request.getEmailEnabled() != null) settings.setEmailEnabled(request.getEmailEnabled());
        if (request.getRecipientEmail() != null) settings.setRecipientEmail(normalizeSingleRecipient(request.getRecipientEmail()));
        if (request.getIntervalMinutes() != null) settings.setIntervalMinutes(clampInterval(request.getIntervalMinutes()));
        if (request.getSmtpHost() != null) settings.setSmtpHost(normalize(request.getSmtpHost()));
        if (request.getSmtpPort() != null) settings.setSmtpPort(sanitizeSmtpPort(request.getSmtpPort()));
        if (request.getSmtpAuth() != null) settings.setSmtpAuth(request.getSmtpAuth());
        if (request.getSmtpStarttlsEnable() != null) settings.setSmtpStarttlsEnable(request.getSmtpStarttlsEnable());
        if (request.getSmtpSslEnable() != null) settings.setSmtpSslEnable(request.getSmtpSslEnable());
        if (request.getSmtpUsername() != null) settings.setSmtpUsername(normalize(request.getSmtpUsername()));
        if (request.getSmtpPassword() != null) {
            String raw = request.getSmtpPassword().trim();
            settings.setSmtpPasswordEncrypted(raw.isBlank() ? "" : notificationSecretService.encrypt(raw));
        }
        if (request.getSenderEmail() != null) settings.setSenderEmail(normalizeEmailOrBlank(request.getSenderEmail(), "sender email"));
        if (request.getSenderName() != null) settings.setSenderName(normalize(request.getSenderName()));
        if (settings.getSmtpUsername() == null || settings.getSmtpUsername().isBlank()) {
            settings.setSmtpUsername(normalize(settings.getSenderEmail()));
        }
        settingsRepository.save(settings);
        return toView(settings);
    }

    @Transactional
    public void runScheduledDigest() {
        Instant now = Instant.now();
        for (NotificationSettings settings : settingsRepository.findByEmailEnabledTrue()) {
            int interval = clampInterval(settings.getIntervalMinutes());
            Instant cursor = resolveCursor(settings);
            if (cursor != null && now.isBefore(cursor.plus(Duration.ofMinutes(interval)))) {
                continue;
            }
            dispatchDigest(settings, now, interval, true);
        }
    }

    @Transactional
    public boolean sendDigestNow() {
        NotificationSettings settings = getOrCreateSettings(requireCurrentUserId());
        return dispatchDigest(settings, Instant.now(), clampInterval(settings.getIntervalMinutes()), false);
    }

    public void runScheduledFeedbackIngestion() {
        for (NotificationSettings settings : settingsRepository.findByEmailEnabledTrue()) {
            try {
                int processed = pollInboxForFeedback(settings);
                if (processed > 0) {
                    log.info("feedback ingestion processed {} mail(s), userId={}", processed, settings.getId());
                }
            } catch (Exception ex) {
                log.warn("failed to poll feedback inbox, userId={}, reason={}", settings.getId(), normalizeError(ex));
            }
        }
    }

    @Transactional
    public int ingestFeedbackNow() {
        NotificationSettings settings = getOrCreateSettings(requireCurrentUserId());
        try {
            return pollInboxForFeedback(settings);
        } catch (Exception ex) {
            throw new IllegalArgumentException("feedback poll failed: " + normalizeError(ex));
        }
    }

    private boolean dispatchDigest(NotificationSettings settings, Instant now, int intervalMinutes, boolean advanceCursorWhenEmpty) {
        settings.setLastAttemptAt(now);
        String recipient;
        try {
            recipient = normalizeSingleRecipient(settings.getRecipientEmail());
        } catch (IllegalArgumentException ex) {
            markFailure(settings, ex.getMessage());
            return false;
        }
        if (recipient.isBlank()) {
            markFailure(settings, "recipient email is required");
            return false;
        }

        MailRuntimeConfig config;
        try {
            config = resolveMailConfig(settings, recipient);
        } catch (IllegalArgumentException ex) {
            markFailure(settings, ex.getMessage());
            return false;
        }

        Instant start = settings.getLastSentAt() == null ? now.minus(Duration.ofMinutes(intervalMinutes)) : settings.getLastSentAt();
        Instant end = now;
        List<AlertHistory> records = loadWindowAlerts(settings.getId(), start, end);
        records.sort(Comparator.comparing(AlertHistory::getOccurredAt, Comparator.nullsLast(Comparator.reverseOrder())));

        if (records.isEmpty()) {
            if (advanceCursorWhenEmpty) settings.setLastSentAt(now);
            settings.setLastSendStatus("EMPTY");
            settings.setLastSendError("no alerts in current window");
            settingsRepository.save(settings);
            return false;
        }

        String batchId = buildBatchId(settings.getId(), now);
        List<DigestItem> digestItems = buildDigestItems(records);
        try {
            String sourceMessageId = sendDigestEmail(config, intervalMinutes, start, end, batchId, digestItems);
            persistBatchMappings(settings.getId(), batchId, sourceMessageId, digestItems);
            settings.setLastSentAt(now);
            settings.setLastSendStatus("SUCCESS");
            settings.setLastSendError(null);
            settingsRepository.save(settings);
            return true;
        } catch (Exception ex) {
            markFailure(settings, normalizeError(ex));
            return false;
        }
    }

    private int pollInboxForFeedback(NotificationSettings settings) throws MessagingException {
        InboxRuntimeConfig inboxConfig = resolveInboxConfig(settings);
        Session mailSession = Session.getInstance(buildImapProperties(inboxConfig));
        int processedCount = 0;

        Store store = null;
        Folder inbox = null;
        try {
            store = mailSession.getStore(inboxConfig.protocol());
            store.connect(inboxConfig.host(), inboxConfig.port(), inboxConfig.username(), inboxConfig.password());
            inbox = store.getFolder("INBOX");
            if (inbox == null || !inbox.exists()) throw new IllegalStateException("INBOX folder does not exist");
            inbox.open(Folder.READ_WRITE);

            SearchTerm recent = new ReceivedDateTerm(ComparisonTerm.GE, Date.from(Instant.now().minus(Duration.ofDays(14))));
            Flags processed = new Flags(PROCESSED_FLAG);
            SearchTerm notProcessed = new FlagTerm(processed, false);
            Message[] messages;
            try {
                messages = inbox.search(new AndTerm(notProcessed, recent));
            } catch (Exception ex) {
                log.warn("mailbox does not support user flag '{}', fallback unseen mode", PROCESSED_FLAG);
                SearchTerm unseen = new FlagTerm(new Flags(Flags.Flag.SEEN), false);
                messages = inbox.search(new AndTerm(unseen, recent));
            }
            Arrays.sort(messages, Comparator.comparing(this::messageInstant));

            for (Message message : messages) {
                boolean markSeen = false;
                try {
                    markSeen = processFeedbackMessage(settings, message);
                } catch (Exception ex) {
                    log.warn("failed to process feedback email, userId={}, reason={}", settings.getId(), normalizeError(ex));
                } finally {
                    if (markSeen) {
                        try {
                            message.setFlag(Flags.Flag.SEEN, true);
                            message.setFlags(new Flags(PROCESSED_FLAG), true);
                            processedCount++;
                        } catch (MessagingException ignore) {
                        }
                    }
                }
            }
        } finally {
            closeFolderQuietly(inbox);
            closeStoreQuietly(store);
        }
        return processedCount;
    }

    private boolean processFeedbackMessage(NotificationSettings settings, Message message) throws Exception {
        String subject = normalize(message.getSubject());
        String rawText = extractMessageText(message);
        String replyText = cleanReplyText(rawText);
        Set<String> mentionedCodes = extractShortCodes(replyText.isBlank() ? rawText : replyText);
        String batchId = extractBatchId(subject);
        if (batchId.isBlank()) {
            batchId = extractBatchId(rawText);
        }

        Set<String> replyMessageIds = new LinkedHashSet<>();
        replyMessageIds.addAll(extractMessageIds(message.getHeader("In-Reply-To")));
        replyMessageIds.addAll(extractMessageIds(message.getHeader("References")));

        boolean feedbackCandidate = !batchId.isBlank() || !replyMessageIds.isEmpty() || !mentionedCodes.isEmpty();
        if (!feedbackCandidate) {
            return false;
        }

        List<AlertFeedbackBatchItem> mappings;
        if (!batchId.isBlank()) {
            mappings = feedbackBatchItemRepository.findByBatchId(batchId);
        } else if (!replyMessageIds.isEmpty()) {
            mappings = resolveMappingsByMessageIds(replyMessageIds);
        } else {
            mappings = resolveMappingsByRecentCodes(settings.getId(), mentionedCodes);
        }
        if (mappings.isEmpty()) {
            log.warn(
                    "feedback ignored, mapping not found, userId={}, subject={}, batch_id={}, codes={}",
                    settings.getId(),
                    subject,
                    batchId,
                    mentionedCodes
            );
            return true;
        }
        if (batchId.isBlank()) {
            batchId = normalize(mappings.get(0).getBatchId());
        }

        Map<String, AlertFeedbackBatchItem> mappingByCode = mappings.stream().collect(Collectors.toMap(
                item -> normalizeShortCode(item.getShortCode()), item -> item, (a, b) -> a, LinkedHashMap::new
        ));
        String parseText = replyText.isBlank() ? truncate(normalize(rawText), 2400) : replyText;
        if (parseText.isBlank()) {
            log.warn("feedback ignored, empty text, batch_id={}", batchId);
            return true;
        }

        FeedbackParseResult result = parseFeedbackReply(parseText, mappingByCode.keySet());
        for (String code : result.unknownCodes()) {
            log.warn("feedback short code not found, batch_id={}, short_code={}", batchId, code);
        }
        Set<String> finalFalseCodes = new LinkedHashSet<>();

        for (String code : result.ruleFalseCodes()) {
            applyFalsePositive(batchId, code, "RULE", mappingByCode);
            finalFalseCodes.add(code);
        }
        for (String code : result.agentCandidateCodes()) {
            if (result.ruleFalseCodes().contains(code)) continue;
            AlertFeedbackBatchItem mapping = mappingByCode.get(code);
            if (mapping == null) continue;
            AlertHistory alert = alertHistoryRepository.findById(mapping.getAlertId()).orElse(null);
            if (alert == null) continue;
            String snippet = result.snippetByCode().getOrDefault(code, parseText);
            AlertFeedbackAgentService.Decision decision = feedbackAgentService.classifyFalsePositive(
                    code,
                    buildAlertInfo(alert),
                    snippet
            );
            if (decision == AlertFeedbackAgentService.Decision.FALSE) {
                applyFalsePositive(batchId, code, "AGENT", mappingByCode);
                finalFalseCodes.add(code);
            }
        }

        for (Map.Entry<String, AlertFeedbackBatchItem> entry : mappingByCode.entrySet()) {
            if (finalFalseCodes.contains(entry.getKey())) {
                continue;
            }
            applyValidFeedback(batchId, entry.getKey(), "REPLY", mappingByCode);
        }
        return true;
    }

    private List<AlertFeedbackBatchItem> resolveMappingsByMessageIds(Set<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        for (String messageId : messageIds) {
            List<AlertFeedbackBatchItem> mappings = feedbackBatchItemRepository.findBySourceMessageId(normalize(messageId));
            if (!mappings.isEmpty()) {
                return mappings;
            }
        }
        return List.of();
    }

    private List<AlertFeedbackBatchItem> resolveMappingsByRecentCodes(Long userId, Set<String> mentionedCodes) {
        if (userId == null || mentionedCodes == null || mentionedCodes.isEmpty()) {
            return List.of();
        }
        List<AlertFeedbackBatchItem> recentItems = feedbackBatchItemRepository.findTop300ByUserIdOrderByCreatedAtDesc(userId);
        if (recentItems.isEmpty()) {
            return List.of();
        }

        Map<String, List<AlertFeedbackBatchItem>> batches = new LinkedHashMap<>();
        for (AlertFeedbackBatchItem item : recentItems) {
            String key = normalize(item.getBatchId());
            if (key.isBlank()) {
                continue;
            }
            batches.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }

        List<AlertFeedbackBatchItem> bestPartialMatch = List.of();
        int bestPartialMatchCount = 0;
        for (List<AlertFeedbackBatchItem> batchItems : batches.values()) {
            Set<String> codesInBatch = batchItems.stream()
                    .map(AlertFeedbackBatchItem::getShortCode)
                    .map(this::normalizeShortCode)
                    .collect(Collectors.toSet());
            int matchCount = 0;
            for (String code : mentionedCodes) {
                if (codesInBatch.contains(code)) {
                    matchCount++;
                }
            }
            if (matchCount == mentionedCodes.size()) {
                return batchItems;
            }
            if (matchCount > bestPartialMatchCount) {
                bestPartialMatchCount = matchCount;
                bestPartialMatch = batchItems;
            }
        }
        return bestPartialMatchCount > 0 ? bestPartialMatch : List.of();
    }

    private Set<String> extractMessageIds(String[] headerValues) {
        Set<String> ids = new LinkedHashSet<>();
        if (headerValues == null || headerValues.length == 0) {
            return ids;
        }
        Pattern messageIdPattern = Pattern.compile("<[^>]+>");
        for (String header : headerValues) {
            if (header == null || header.isBlank()) {
                continue;
            }
            Matcher matcher = messageIdPattern.matcher(header);
            while (matcher.find()) {
                ids.add(matcher.group());
            }
        }
        return ids;
    }

    private FeedbackParseResult parseFeedbackReply(String replyText, Set<String> allowedCodes) {
        String primaryReply = extractPrimaryReplySegment(replyText);
        Set<String> primaryMentionedCodes = extractShortCodes(primaryReply);
        Set<String> primaryUnknownCodes = new LinkedHashSet<>(primaryMentionedCodes);
        primaryUnknownCodes.removeAll(allowedCodes);
        Set<String> primaryCandidateCodes = new LinkedHashSet<>(primaryMentionedCodes);
        primaryCandidateCodes.retainAll(allowedCodes);

        Set<String> explicitFalsePrimary = extractFocusedCodes(primaryReply, FALSE_CODES_AFTER_HINT_PATTERN, FALSE_CODES_BEFORE_HINT_PATTERN);
        explicitFalsePrimary.retainAll(allowedCodes);
        Set<String> explicitValidPrimary = extractFocusedCodes(primaryReply, VALID_CODES_AFTER_HINT_PATTERN, VALID_CODES_BEFORE_HINT_PATTERN);
        explicitValidPrimary.retainAll(allowedCodes);
        if (!explicitFalsePrimary.isEmpty() || !explicitValidPrimary.isEmpty()) {
            Set<String> ruleFalseCodes = new LinkedHashSet<>(explicitFalsePrimary);
            ruleFalseCodes.removeAll(explicitValidPrimary);
            Map<String, String> snippets = new LinkedHashMap<>();
            for (String code : primaryCandidateCodes) {
                snippets.put(code, primaryReply);
            }
            return new FeedbackParseResult(ruleFalseCodes, Set.of(), primaryUnknownCodes, snippets);
        }

        Set<String> mentionedCodes = extractShortCodes(replyText);
        Set<String> unknownCodes = new LinkedHashSet<>(mentionedCodes);
        unknownCodes.removeAll(allowedCodes);
        if (!primaryUnknownCodes.isEmpty()) {
            unknownCodes = new LinkedHashSet<>(primaryUnknownCodes);
        }

        Set<String> candidateCodes = new LinkedHashSet<>(mentionedCodes);
        candidateCodes.retainAll(allowedCodes);
        if (!primaryCandidateCodes.isEmpty()) {
            candidateCodes = new LinkedHashSet<>(primaryCandidateCodes);
        }

        Map<String, StringBuilder> linesByCode = new LinkedHashMap<>();
        for (String line : replyText.split("\\R+")) {
            for (String code : extractShortCodes(line)) {
                if (!allowedCodes.contains(code)) continue;
                linesByCode.computeIfAbsent(code, k -> new StringBuilder());
                if (!linesByCode.get(code).isEmpty()) linesByCode.get(code).append(" | ");
                linesByCode.get(code).append(line.trim());
            }
        }

        Map<String, String> snippets = new LinkedHashMap<>();
        for (String code : candidateCodes) {
            snippets.put(code, linesByCode.containsKey(code) ? linesByCode.get(code).toString() : replyText);
        }

        Set<String> explicitFalseCodes = extractFocusedCodes(replyText, FALSE_CODES_AFTER_HINT_PATTERN, FALSE_CODES_BEFORE_HINT_PATTERN);
        explicitFalseCodes.retainAll(candidateCodes);
        Set<String> explicitValidCodes = extractFocusedCodes(replyText, VALID_CODES_AFTER_HINT_PATTERN, VALID_CODES_BEFORE_HINT_PATTERN);
        explicitValidCodes.retainAll(candidateCodes);
        if (!explicitFalseCodes.isEmpty() || !explicitValidCodes.isEmpty()) {
            Set<String> ruleFalseCodes = new LinkedHashSet<>(explicitFalseCodes);
            ruleFalseCodes.removeAll(explicitValidCodes);
            return new FeedbackParseResult(ruleFalseCodes, Set.of(), unknownCodes, snippets);
        }

        Set<String> ruleFalseCodes = new LinkedHashSet<>();
        Set<String> agentCandidateCodes = new LinkedHashSet<>();
        for (String code : candidateCodes) {
            String snippet = snippets.getOrDefault(code, replyText);
            boolean hasFalse = FALSE_HINT_PATTERN.matcher(snippet).find();
            boolean hasValid = VALID_HINT_PATTERN.matcher(snippet).find();
            if (hasFalse && !hasValid) {
                ruleFalseCodes.add(code);
            } else if (hasValid && !hasFalse) {
                // Explicit valid feedback will be applied in the default-to-valid pass.
                continue;
            } else if (isSimpleCodeReply(snippet)) {
                ruleFalseCodes.add(code);
            } else {
                agentCandidateCodes.add(code);
            }
        }

        // Fallback: if short codes are present but rule matching is inconclusive,
        // escalate these codes to agent classification instead of dropping them.
        if (ruleFalseCodes.isEmpty() && agentCandidateCodes.isEmpty() && !candidateCodes.isEmpty()) {
            for (String code : candidateCodes) {
                String snippet = snippets.getOrDefault(code, replyText);
                boolean hasValid = VALID_HINT_PATTERN.matcher(snippet).find();
                if (!hasValid) {
                    agentCandidateCodes.add(code);
                }
            }
        }
        return new FeedbackParseResult(ruleFalseCodes, agentCandidateCodes, unknownCodes, snippets);
    }

    private String extractPrimaryReplySegment(String replyText) {
        if (replyText == null || replyText.isBlank()) {
            return "";
        }
        String normalized = replyText.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder builder = new StringBuilder();
        int nonEmptyLines = 0;
        for (String line : normalized.split("\\n")) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank()) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                continue;
            }
            if (trimmed.startsWith(">")) {
                continue;
            }
            if (REPLY_BREAK_PATTERN.matcher(trimmed).find() && nonEmptyLines >= 1) {
                break;
            }
            builder.append(line).append('\n');
            nonEmptyLines++;
            if (nonEmptyLines >= 6 || builder.length() >= 600) {
                break;
            }
        }
        String segment = builder.toString().trim();
        return segment.isBlank() ? normalized.trim() : segment;
    }

    private Set<String> extractFocusedCodes(String text, Pattern... patterns) {
        Set<String> codes = new LinkedHashSet<>();
        if (text == null || text.isBlank() || patterns == null || patterns.length == 0) {
            return codes;
        }
        for (Pattern pattern : patterns) {
            if (pattern == null) {
                continue;
            }
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String group = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                codes.addAll(extractShortCodes(group));
            }
        }
        return codes;
    }

    private void applyFalsePositive(String batchId, String shortCode, String source, Map<String, AlertFeedbackBatchItem> mappingByCode) {
        AlertFeedbackBatchItem mapping = mappingByCode.get(shortCode);
        if (mapping == null) {
            log.warn("feedback code missing mapping, batch_id={}, short_code={}", batchId, shortCode);
            return;
        }
        AlertHistory alert = alertHistoryRepository.findById(mapping.getAlertId()).orElse(null);
        if (alert == null) {
            log.warn("feedback alert not found, batch_id={}, short_code={}, alert_id={}", batchId, shortCode, mapping.getAlertId());
            return;
        }
        Instant now = Instant.now();
        mapping.setFeedbackStatus("FALSE_POSITIVE");
        mapping.setFeedbackSource(source);
        mapping.setFeedbackAt(now);
        feedbackBatchItemRepository.save(mapping);

        alert.setFeedbackStatus("FALSE_POSITIVE");
        alert.setFeedbackSource(source);
        alert.setFeedbackAt(now);
        if (alert.getFeedbackComment() == null || alert.getFeedbackComment().isBlank()) {
            alert.setFeedbackComment("auto feedback batch_id=" + batchId + ", short_code=" + shortCode + ", source=" + source);
        }
        alertHistoryRepository.save(alert);
    }

    private void applyValidFeedback(String batchId, String shortCode, String source, Map<String, AlertFeedbackBatchItem> mappingByCode) {
        AlertFeedbackBatchItem mapping = mappingByCode.get(shortCode);
        if (mapping == null) {
            return;
        }
        if (!"FALSE_POSITIVE".equalsIgnoreCase(normalize(mapping.getFeedbackStatus()))) {
            mapping.setFeedbackStatus("VALID");
            mapping.setFeedbackSource(source);
            mapping.setFeedbackAt(Instant.now());
            feedbackBatchItemRepository.save(mapping);
        }

        AlertHistory alert = alertHistoryRepository.findById(mapping.getAlertId()).orElse(null);
        if (alert == null) {
            return;
        }
        if ("FALSE_POSITIVE".equalsIgnoreCase(normalize(alert.getFeedbackStatus()))) {
            return;
        }
        alert.setFeedbackStatus("VALID");
        alert.setFeedbackSource(source);
        alert.setFeedbackAt(Instant.now());
        if (alert.getFeedbackComment() == null || alert.getFeedbackComment().isBlank()) {
            alert.setFeedbackComment("auto feedback batch_id=" + batchId + ", short_code=" + shortCode + ", source=" + source);
        }
        alertHistoryRepository.save(alert);
    }

    private void persistBatchMappings(Long userId, String batchId, String sourceMessageId, List<DigestItem> digestItems) {
        Instant now = Instant.now();
        List<AlertFeedbackBatchItem> mappings = new ArrayList<>();
        Map<Long, AlertHistory> updates = new LinkedHashMap<>();
        for (DigestItem item : digestItems) {
            AlertFeedbackBatchItem mapping = new AlertFeedbackBatchItem();
            mapping.setUserId(userId);
            mapping.setBatchId(batchId);
            mapping.setShortCode(item.shortCode());
            mapping.setAlertId(item.alert().getId());
            mapping.setSourceMessageId(normalize(sourceMessageId));
            mapping.setFeedbackStatus("UNLABELED");
            mapping.setCreatedAt(now);
            mappings.add(mapping);

            AlertHistory alert = item.alert();
            if (normalize(alert.getFeedbackStatus()).isBlank()) {
                alert.setFeedbackStatus("UNLABELED");
                if (alert.getFeedbackSource() == null || alert.getFeedbackSource().isBlank()) {
                    alert.setFeedbackSource("DEFAULT");
                }
                updates.put(alert.getId(), alert);
            }
        }
        feedbackBatchItemRepository.saveAll(mappings);
        if (!updates.isEmpty()) alertHistoryRepository.saveAll(updates.values());
    }

    private String sendDigestEmail(
            MailRuntimeConfig config,
            int intervalMinutes,
            Instant start,
            Instant end,
            String batchId,
            List<DigestItem> digestItems
    ) throws Exception {
        JavaMailSenderImpl sender = buildMailSender(config);
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
        if (config.senderName().isBlank()) {
            helper.setFrom(config.senderEmail());
        } else {
            helper.setFrom(new InternetAddress(config.senderEmail(), config.senderName(), StandardCharsets.UTF_8.name()));
        }
        helper.setTo(config.recipientEmail());
        helper.setSubject(String.format(
                Locale.ROOT,
                "\u3010\u79c1\u6709\u4e91\u76d1\u63a7\u3011\u8fd1%d\u5206\u949f\u544a\u8b66\u6c47\u603b\uff08%d\u6761\uff09%s ~ %s",
                intervalMinutes,
                digestItems.size(),
                TIME.format(start),
                TIME.format(end)
        ));
        helper.setText(buildDigestEmailHtml(intervalMinutes, start, end, batchId, digestItems), true);
        message.setHeader("X-Alert-Batch-Id", batchId);
        message.saveChanges();
        String sourceMessageId = normalize(message.getMessageID());
        sender.send(message);
        return sourceMessageId;
    }

    private String buildDigestEmailHtml(
            int intervalMinutes,
            Instant start,
            Instant end,
            String batchId,
            List<DigestItem> digestItems
    ) {
        long criticalCount = countByLevel(digestItems, "critical");
        long alertCount = countByLevel(digestItems, "alert");
        long warningCount = countByLevel(digestItems, "warning");

        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-family:Arial,'Microsoft YaHei',sans-serif;line-height:1.6;\">");
        html.append("<h3>\u79c1\u6709\u4e91\u76d1\u63a7\u544a\u8b66\u6c47\u603b</h3>");
        html.append("<p>\u7edf\u8ba1\u7a97\u53e3\uff1a")
                .append(TIME.format(start))
                .append(" ~ ")
                .append(TIME.format(end))
                .append("\uff08\u6bcf ")
                .append(intervalMinutes)
                .append(" \u5206\u949f\uff09</p>");
        html.append("<p>\u5171").append(digestItems.size()).append("\u6761\uff0c\u4e25\u91cd ").append(criticalCount)
                .append(" \u6761\uff0c\u8b66\u62a5 ").append(alertCount).append(" \u6761\uff0c\u8b66\u544a ").append(warningCount).append(" \u6761\u3002</p>");
        html.append("<p style=\"margin:4px 0 8px;color:#6b7280;font-size:12px;\">batch_id: ")
                .append(escapeHtml(batchId))
                .append("</p>");
        html.append("<p style=\"margin:8px 0 12px;color:#374151;font-size:12px;\">")
                .append("\u53cd\u9988\u8bf4\u660e\uff1a\u82e5\u53d1\u73b0\u8bef\u62a5\uff0c\u8bf7\u76f4\u63a5\u56de\u590d\u7f16\u53f7\uff0c\u4f8b\u5982\"\u8bef\u62a5\uff1aA2 A3\"\u6216\"A2 \u8bef\u62a5\"\u3002")
                .append("</p>");
        html.append("<table style=\"border-collapse:collapse;width:100%;font-size:13px;\"><thead><tr style=\"background:#f3f4f6;\">")
                .append(th("\u5e8f\u53f7"))
                .append(th("\u89e6\u53d1\u65f6\u95f4"))
                .append(th("\u7ea7\u522b"))
                .append(th("\u5bf9\u8c61"))
                .append(th("\u6307\u6807"))
                .append(th("\u9608\u503c\u7c7b\u578b"))
                .append(th("\u5f53\u524d\u503c/\u9608\u503c"))
                .append(th("\u72b6\u6001"))
                .append(th("\u539f\u56e0"))
                .append(th("\u5904\u7406\u5efa\u8bae"))
                .append(th("Agent\u98ce\u9669"))
                .append(th("Agent\u9884\u6d4b"))
                .append(th("Agent\u5206\u6790"))
                .append(th("Agent\u5efa\u8bae"))
                .append("</tr></thead><tbody>");

        for (DigestItem item : digestItems) {
            AlertHistory alert = item.alert();
            html.append("<tr>")
                    .append(td(item.shortCode()))
                    .append(td(alert.getOccurredAt() == null ? "--" : TIME.format(alert.getOccurredAt())))
                    .append(td(levelText(alert.getLevel())))
                    .append(td(n(alert.getHost())))
                    .append(td(n(alert.getMetricName())))
                    .append(td(thresholdTypeText(alert.getThresholdType())))
                    .append(td(valueAndThresholdText(alert)))
                    .append(td(statusText(alert.getStatus())))
                    .append(td(truncate(n(alert.getReason()), 220)))
                    .append(td(truncate(n(alert.getRecommendation()), 220)))
                    .append(td(agentRiskText(alert)))
                    .append(td(truncate(n(alert.getAgentPrediction()), 260)))
                    .append(td(truncate(n(alert.getAgentAnalysis()), 320)))
                    .append(td(truncate(n(alert.getAgentRecommendation()), 320)))
                    .append("</tr>");
        }

        html.append("</tbody></table>");
        html.append("<p style=\"margin-top:12px;color:#6b7280;font-size:12px;\">")
                .append("\u5907\u6ce8\uff1a\u82e5\u786e\u8ba4\u8bef\u62a5\uff0c\u5efa\u8bae\u8865\u5145\"\u539f\u56e0 + \u5df2\u6062\u590d\u52a8\u4f5c/\u5904\u7406\u65b9\u5f0f\"\uff08\u4f8b\u5982\uff1aA1 \u8bef\u62a5\uff0c\u5df2\u6062\u590d\uff0c\u5df2\u8c03\u6574\u9608\u503c\uff09\uff0c")
                .append("\u8fd9\u6837\u7cfb\u7edf\u4f1a\u66f4\u51c6\u786e\u8bc6\u522b\u540e\u7eed\u8bef\u62a5\u3002")
                .append("</p>");
        html.append("</div>");
        return html.toString();
    }

    private long countByLevel(List<DigestItem> digestItems, String level) {
        return digestItems.stream()
                .map(item -> normalize(item.alert().getLevel()))
                .filter(level::equals)
                .count();
    }

    private MailRuntimeConfig resolveMailConfig(NotificationSettings settings, String recipient) {
        String host = firstNonBlank(settings.getSmtpHost(), defaultSmtpHost);
        if (host.isBlank()) throw new IllegalArgumentException("SMTP host is required");

        int port = sanitizeSmtpPort(settings.getSmtpPort() == null ? defaultSmtpPort : settings.getSmtpPort());
        boolean auth = settings.getSmtpAuth() == null ? defaultSmtpAuth : settings.getSmtpAuth();
        boolean starttls = settings.getSmtpStarttlsEnable() == null ? defaultSmtpStarttlsEnable : settings.getSmtpStarttlsEnable();
        boolean ssl = settings.getSmtpSslEnable() == null ? defaultSmtpSslEnable : settings.getSmtpSslEnable();

        String username = firstNonBlank(settings.getSmtpUsername(), settings.getSenderEmail(), defaultSmtpUsername);
        String password = settings.getSmtpPasswordEncrypted() != null && !settings.getSmtpPasswordEncrypted().isBlank()
                ? notificationSecretService.decrypt(settings.getSmtpPasswordEncrypted())
                : defaultSmtpPassword;
        if (auth) {
            if (username.isBlank()) throw new IllegalArgumentException("SMTP username is required");
            if (password == null || password.isBlank()) throw new IllegalArgumentException("SMTP password is required");
        }

        String senderEmail = firstNonBlank(
                normalizeEmailOrBlank(settings.getSenderEmail(), "sender email"),
                normalizeEmailOrBlank(defaultSenderEmail, "default sender email"),
                toValidEmailOrBlank(username)
        );
        if (senderEmail.isBlank()) throw new IllegalArgumentException("sender email is required");
        if (!EMAIL_PATTERN.matcher(recipient).matches()) throw new IllegalArgumentException("recipient email format is invalid");

        return new MailRuntimeConfig(
                host, port, auth, starttls, ssl, username, password, senderEmail,
                firstNonBlank(settings.getSenderName(), defaultSenderName), recipient
        );
    }

    private InboxRuntimeConfig resolveInboxConfig(NotificationSettings settings) {
        String imapHost = firstNonBlank(defaultImapHost, inferImapHost(firstNonBlank(settings.getSmtpHost(), defaultSmtpHost)));
        if (imapHost.isBlank()) throw new IllegalArgumentException("IMAP host is required");

        boolean ssl = defaultImapSslEnable;
        boolean starttls = defaultImapStarttlsEnable;
        int port = defaultImapPort > 0 ? defaultImapPort : (ssl ? DEFAULT_IMAP_SSL_PORT : DEFAULT_IMAP_STARTTLS_PORT);

        String username = firstNonBlank(settings.getSmtpUsername(), settings.getSenderEmail(), defaultSmtpUsername);
        String password = settings.getSmtpPasswordEncrypted() != null && !settings.getSmtpPasswordEncrypted().isBlank()
                ? notificationSecretService.decrypt(settings.getSmtpPasswordEncrypted())
                : defaultSmtpPassword;
        if (username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("mailbox credentials are required for IMAP");
        }
        return new InboxRuntimeConfig(imapHost, port, username, password, ssl ? "imaps" : "imap", starttls, ssl);
    }

    private String extractBatchId(String subject) {
        if (subject == null || subject.isBlank()) return "";
        Matcher matcher = BATCH_ID_PATTERN.matcher(subject);
        return matcher.find() ? normalize(matcher.group(1)) : "";
    }

    private String extractMessageText(Message message) throws Exception {
        return extractTextFromPart(message);
    }

    private String extractTextFromPart(Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            return content == null ? "" : String.valueOf(content);
        }
        if (part.isMimeType("text/html")) {
            Object content = part.getContent();
            return content == null ? "" : stripHtml(String.valueOf(content));
        }
        Object content = part.getContent();
        if (content instanceof Multipart multipart) {
            String htmlFallback = "";
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bodyPart = multipart.getBodyPart(i);
                String child = extractTextFromPart(bodyPart);
                if (child == null || child.isBlank()) continue;
                if (bodyPart.isMimeType("text/plain")) return child;
                if (bodyPart.isMimeType("text/html")) htmlFallback = child;
            }
            return htmlFallback;
        }
        if (content instanceof Message nestedMessage) {
            return extractTextFromPart(nestedMessage);
        }
        return "";
    }

    private String cleanReplyText(String rawText) {
        if (rawText == null || rawText.isBlank()) return "";
        String normalized = rawText.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder cleaned = new StringBuilder();
        for (String line : normalized.split("\\n")) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.startsWith(">")) continue;
            if (REPLY_BREAK_PATTERN.matcher(trimmed).find()) {
                // Quoted thread usually starts here; stop once reply content has started.
                if (!cleaned.toString().isBlank()) {
                    break;
                }
                continue;
            }
            cleaned.append(line).append('\n');
        }
        String result = cleaned.toString().trim();
        return result.length() > 2400 ? result.substring(0, 2400) : result;
    }

    private JavaMailSenderImpl buildMailSender(MailRuntimeConfig config) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.host());
        sender.setPort(config.port());
        sender.setProtocol("smtp");
        if (!config.username().isBlank()) sender.setUsername(config.username());
        if (!config.password().isBlank()) sender.setPassword(config.password());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        Properties props = sender.getJavaMailProperties();
        props.put("mail.smtp.auth", String.valueOf(config.auth()));
        props.put("mail.smtp.starttls.enable", String.valueOf(config.starttlsEnable()));
        props.put("mail.smtp.ssl.enable", String.valueOf(config.sslEnable()));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        return sender;
    }

    private Properties buildImapProperties(InboxRuntimeConfig config) {
        Properties props = new Properties();
        props.put("mail.store.protocol", config.protocol());
        props.put("mail.imap.connectiontimeout", "10000");
        props.put("mail.imap.timeout", "15000");
        props.put("mail.imap.ssl.enable", String.valueOf(config.sslEnable()));
        props.put("mail.imap.starttls.enable", String.valueOf(config.starttlsEnable()));
        props.put("mail.imap.port", String.valueOf(config.port()));
        props.put("mail.imaps.connectiontimeout", "10000");
        props.put("mail.imaps.timeout", "15000");
        props.put("mail.imaps.ssl.enable", String.valueOf(config.sslEnable()));
        props.put("mail.imaps.starttls.enable", String.valueOf(config.starttlsEnable()));
        props.put("mail.imaps.port", String.valueOf(config.port()));
        return props;
    }

    private String stripHtml(String html) {
        if (html == null || html.isBlank()) return "";
        return html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
    }

    private Set<String> extractShortCodes(String text) {
        Set<String> codes = new LinkedHashSet<>();
        if (text == null || text.isBlank()) return codes;
        Matcher matcher = SHORT_CODE_PATTERN.matcher(text);
        while (matcher.find()) codes.add(normalizeShortCode(matcher.group()));
        return codes;
    }

    private String normalizeShortCode(String shortCode) {
        return normalize(shortCode).toUpperCase(Locale.ROOT);
    }

    private boolean isSimpleCodeReply(String snippet) {
        String normalized = SHORT_CODE_PATTERN.matcher(snippet.toUpperCase(Locale.ROOT)).replaceAll("")
                .replaceAll("(?i)false\\s*positive", "")
                .replaceAll("(?i)fp", "")
                .replaceAll("[\\p{Punct}\\s]+", "");
        return normalized.length() <= 4;
    }

    private String inferImapHost(String smtpHost) {
        String host = normalize(smtpHost);
        if (host.isBlank()) return "";
        return host.toLowerCase(Locale.ROOT).startsWith("smtp.") ? "imap." + host.substring("smtp.".length()) : host;
    }

    private Instant messageInstant(Message message) {
        try {
            Date receivedDate = message.getReceivedDate();
            if (receivedDate != null) return receivedDate.toInstant();
            Date sentDate = message.getSentDate();
            if (sentDate != null) return sentDate.toInstant();
        } catch (MessagingException ignored) {
        }
        return Instant.EPOCH;
    }

    private void closeFolderQuietly(Folder folder) {
        if (folder == null) return;
        try {
            if (folder.isOpen()) folder.close(false);
        } catch (Exception ignore) {
        }
    }

    private void closeStoreQuietly(Store store) {
        if (store == null) return;
        try {
            store.close();
        } catch (Exception ignore) {
        }
    }

    private void markFailure(NotificationSettings settings, String reason) {
        settings.setLastSendStatus("FAILED");
        settings.setLastSendError(truncate(reason, MAX_ERROR_LENGTH));
        settingsRepository.save(settings);
    }

    private String buildBatchId(Long userId, Instant now) {
        return "B" + BATCH_TIME.format(now) + "-U" + userId + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private List<DigestItem> buildDigestItems(List<AlertHistory> records) {
        List<DigestItem> items = new ArrayList<>();
        int idx = 1;
        for (AlertHistory record : records) items.add(new DigestItem("A" + idx++, record));
        return items;
    }

    private String buildAlertInfo(AlertHistory alert) {
        if (alert == null) return "";
        return "host=" + n(alert.getHost()) + ", rule=" + n(alert.getRuleName()) + ", metric=" + n(alert.getMetricName())
                + ", level=" + n(alert.getLevel()) + ", status=" + n(alert.getStatus()) + ", reason=" + n(alert.getReason());
    }

    private String th(String value) {
        return "<th style=\"border:1px solid #ddd;padding:6px;text-align:left;\">" + escapeHtml(value) + "</th>";
    }

    private String td(String value) {
        return "<td style=\"border:1px solid #ddd;padding:6px;vertical-align:top;\">" + escapeHtml(value) + "</td>";
    }

    private String n(String value) {
        return value == null || value.isBlank() ? "--" : value.trim();
    }

    private String escapeHtml(String value) {
        String v = n(value);
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String levelText(String level) {
        String normalized = normalize(level);
        return switch (normalized) {
            case "critical" -> "\u4e25\u91cd";
            case "alert" -> "\u8b66\u62a5";
            case "warning" -> "\u8b66\u544a";
            default -> n(level);
        };
    }

    private String statusText(String status) {
        return "resolved".equalsIgnoreCase(status) ? "\u5df2\u6062\u590d" : "\u672a\u6062\u590d";
    }

    private String thresholdTypeText(String thresholdType) {
        String normalized = normalize(thresholdType);
        return switch (normalized) {
            case "static" -> "\u9759\u6001\u9608\u503c";
            case "dynamic" -> "\u52a8\u6001\u9608\u503c";
            case "hybrid" -> "\u9759\u6001/\u52a8\u6001";
            default -> n(thresholdType);
        };
    }

    private String valueAndThresholdText(AlertHistory alert) {
        Double threshold = alert.getThresholdValue();
        if (threshold == null) {
            String thresholdType = normalize(alert.getThresholdType());
            if ("static".equals(thresholdType)) {
                threshold = alert.getStaticThreshold();
            } else if ("dynamic".equals(thresholdType)) {
                threshold = alert.getDynamicThreshold();
            } else {
                threshold = alert.getStaticThreshold() != null ? alert.getStaticThreshold() : alert.getDynamicThreshold();
            }
        }
        return formatDouble(alert.getValue()) + " / " + formatDouble(threshold);
    }

    private String formatDouble(Double value) {
        if (value == null) return "--";
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private String agentRiskText(AlertHistory alert) {
        if (alert.getAgentRiskScore() == null) return "--";
        return String.format(Locale.ROOT, "%.2f", alert.getAgentRiskScore());
    }

    private NotificationSettings getOrCreateSettings(Long userId) {
        return settingsRepository.findById(userId).orElseGet(() -> {
            NotificationSettings settings = new NotificationSettings();
            settings.setId(userId);
            settings.setEmailEnabled(false);
            settings.setRecipientEmail("");
            settings.setSmtpHost("");
            settings.setSmtpPort(DEFAULT_SMTP_PORT);
            settings.setSmtpAuth(true);
            settings.setSmtpStarttlsEnable(true);
            settings.setSmtpSslEnable(false);
            settings.setSmtpUsername("");
            settings.setSmtpPasswordEncrypted("");
            settings.setSenderEmail("");
            settings.setSenderName("");
            settings.setIntervalMinutes(DEFAULT_INTERVAL_MINUTES);
            settings.setLastSendStatus("INIT");
            settings.setLastSendError(null);
            return settingsRepository.save(settings);
        });
    }

    private List<AlertHistory> loadWindowAlerts(Long userId, Instant start, Instant end) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return List.of();
        if (isAdminUser(user)) {
            return alertHistoryRepository.findByOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDesc(start, end);
        }
        List<String> assignedServers = user.getServers().stream()
                .map(server -> server.getServerHost())
                .filter(host -> host != null && !host.isBlank())
                .distinct()
                .toList();
        if (assignedServers.isEmpty()) return List.of();
        List<String> hosts = accessControlService.expandEntityHostsForServers(assignedServers);
        if (hosts.isEmpty()) return List.of();
        return alertHistoryRepository.findByOccurredAtGreaterThanEqualAndOccurredAtLessThanAndHostInOrderByOccurredAtDesc(start, end, hosts);
    }

    private boolean isAdminUser(User user) {
        return user.getRoles().stream().anyMatch(role -> "ADMIN".equalsIgnoreCase(role.getName()));
    }

    private Long requireCurrentUserId() {
        AuthSession session = AuthContext.get();
        if (session == null || session.getUserId() == null) throw new IllegalArgumentException("unauthorized");
        return session.getUserId();
    }

    private NotificationSettingsView toView(NotificationSettings settings) {
        int interval = clampInterval(settings.getIntervalMinutes());
        Instant next = settings.getLastSentAt() == null ? null : settings.getLastSentAt().plus(Duration.ofMinutes(interval));
        return new NotificationSettingsView(
                settings.isEmailEnabled(),
                normalize(settings.getRecipientEmail()),
                normalize(settings.getSmtpHost()),
                sanitizeSmtpPort(settings.getSmtpPort()),
                settings.getSmtpAuth() == null || settings.getSmtpAuth(),
                settings.getSmtpStarttlsEnable() == null || settings.getSmtpStarttlsEnable(),
                settings.getSmtpSslEnable() != null && settings.getSmtpSslEnable(),
                normalize(settings.getSmtpUsername()),
                settings.getSmtpPasswordEncrypted() != null && !settings.getSmtpPasswordEncrypted().isBlank(),
                normalize(settings.getSenderEmail()),
                normalize(settings.getSenderName()),
                interval,
                settings.getLastSentAt(),
                settings.getLastAttemptAt(),
                next,
                normalize(settings.getLastSendStatus()),
                normalize(settings.getLastSendError())
        );
    }

    private int clampInterval(Integer interval) {
        int value = interval == null ? DEFAULT_INTERVAL_MINUTES : interval;
        if (value < MIN_INTERVAL_MINUTES) return MIN_INTERVAL_MINUTES;
        return Math.min(value, MAX_INTERVAL_MINUTES);
    }

    private int sanitizeSmtpPort(Integer port) {
        int value = port == null ? DEFAULT_SMTP_PORT : port;
        if (value <= 0) return DEFAULT_SMTP_PORT;
        return Math.min(value, 65535);
    }

    private String normalizeSingleRecipient(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String[] parts = MULTI_EMAIL_SPLITTER.split(raw.trim());
        String first = "";
        int count = 0;
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isBlank()) continue;
            count++;
            if (count == 1) first = value;
        }
        if (count > 1) throw new IllegalArgumentException("only one recipient email is supported for each user");
        if (first.isBlank()) return "";
        if (!EMAIL_PATTERN.matcher(first).matches()) throw new IllegalArgumentException("recipient email format is invalid");
        return first;
    }

    private String normalizeEmailOrBlank(String raw, String fieldName) {
        String v = normalize(raw);
        if (v.isBlank()) return "";
        if (!EMAIL_PATTERN.matcher(v).matches()) throw new IllegalArgumentException(fieldName + " format is invalid");
        return v;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private String normalizeError(Exception ex) {
        String message = ex == null ? "" : ex.getMessage();
        if (message == null || message.isBlank()) return ex == null ? "operation failed" : ex.getClass().getSimpleName();
        return message;
    }

    private String toValidEmailOrBlank(String raw) {
        String v = normalize(raw);
        return v.isBlank() ? "" : (EMAIL_PATTERN.matcher(v).matches() ? v : "");
    }

    private String truncate(String text, int maxLength) {
        String v = text == null ? "" : text.trim();
        return v.length() <= maxLength ? v : v.substring(0, maxLength) + "...";
    }

    private Instant resolveCursor(NotificationSettings settings) {
        Instant lastSent = settings.getLastSentAt();
        Instant lastAttempt = settings.getLastAttemptAt();
        if (lastSent == null) return lastAttempt;
        if (lastAttempt == null) return lastSent;
        return lastSent.isAfter(lastAttempt) ? lastSent : lastAttempt;
    }

    private record MailRuntimeConfig(
            String host,
            int port,
            boolean auth,
            boolean starttlsEnable,
            boolean sslEnable,
            String username,
            String password,
            String senderEmail,
            String senderName,
            String recipientEmail
    ) {
    }

    private record InboxRuntimeConfig(
            String host,
            int port,
            String username,
            String password,
            String protocol,
            boolean starttlsEnable,
            boolean sslEnable
    ) {
    }

    private record DigestItem(String shortCode, AlertHistory alert) {
    }

    private record FeedbackParseResult(
            Set<String> ruleFalseCodes,
            Set<String> agentCandidateCodes,
            Set<String> unknownCodes,
            Map<String, String> snippetByCode
    ) {
    }
}
