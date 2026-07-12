package com.privatecloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.privatecloud.entity.AlertHistory;
import com.privatecloud.repository.AlertHistoryRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class AlertAgentService {
    private static final Logger log = LoggerFactory.getLogger(AlertAgentService.class);
    private static final int MAX_PREDICTION_LENGTH = 500;
    private static final int MAX_ANALYSIS_LENGTH = 1200;
    private static final int MAX_RECOMMENDATION_LENGTH = 1200;

    private final AlertHistoryRepository alertHistoryRepository;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    @Value("${alert.agent.enabled:false}")
    private boolean enabled;

    @Value("${alert.agent.base-url:}")
    private String baseUrl;

    @Value("${alert.agent.api-key:}")
    private String apiKey;

    @Value("${alert.agent.model:gpt-4o-mini}")
    private String model;

    @Value("${alert.agent.temperature:0.2}")
    private double temperature;

    @Value("${alert.agent.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${alert.agent.read-timeout-ms:20000}")
    private int readTimeoutMs;

    @Value("${alert.agent.max-context-chars:2000}")
    private int maxContextChars;

    public AlertAgentService(
            AlertHistoryRepository alertHistoryRepository,
            ObjectMapper objectMapper,
            @Qualifier("alertAgentExecutor") Executor executor
    ) {
        this.alertHistoryRepository = alertHistoryRepository;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @PostConstruct
    public void logAgentBootstrapConfig() {
        log.info(
                "alert agent config loaded: enabled={}, endpoint={}, model={}",
                enabled,
                resolveChatCompletionsEndpoint(baseUrl),
                model
        );
    }

    public void enqueue(Long alertHistoryId) {
        if (alertHistoryId == null || !enabled) {
            return;
        }
        Optional<AlertHistory> optional = alertHistoryRepository.findById(alertHistoryId);
        if (optional.isEmpty()) {
            return;
        }
        AlertHistory history = optional.get();
        if (!"firing".equalsIgnoreCase(history.getStatus())) {
            return;
        }
        String status = normalize(history.getAgentStatus());
        if ("running".equals(status) || "pending".equals(status) || "success".equals(status)) {
            return;
        }

        history.setAgentStatus("pending");
        history.setAgentUpdatedAt(Instant.now());
        alertHistoryRepository.save(history);

        try {
            executor.execute(() -> analyze(alertHistoryId));
        } catch (RejectedExecutionException ex) {
            markFailed(alertHistoryId, "Agent \u961f\u5217\u5df2\u6ee1");
        }
    }

    private void analyze(Long alertHistoryId) {
        Optional<AlertHistory> optional = alertHistoryRepository.findById(alertHistoryId);
        if (optional.isEmpty()) {
            return;
        }
        AlertHistory history = optional.get();
        if (!"firing".equalsIgnoreCase(history.getStatus())) {
            return;
        }
        updateAgentStatus(alertHistoryId, "running");

        if (isBlank(baseUrl) || isBlank(apiKey)) {
            updateAgentSkipped(
                    alertHistoryId,
                    "\u5df2\u8df3\u8fc7",
                    "Agent \u914d\u7f6e\u4e0d\u5b8c\u6574\uff1a\u7f3a\u5c11 base-url \u6216 api-key",
                    "\u8bf7\u914d\u7f6e alert.agent.base-url \u548c alert.agent.api-key \u540e\u91cd\u8bd5"
            );
            return;
        }

        try {
            String rawResponse = callModel(buildRequestBody(history));
            AgentResult result = parseResult(rawResponse);
            updateAgentSuccess(alertHistoryId, result, rawResponse);
        } catch (Exception ex) {
            markFailed(alertHistoryId, "Agent \u5206\u6790\u5931\u8d25: " + ex.getMessage());
        }
    }

    private Map<String, Object> buildRequestBody(AlertHistory history) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("temperature", temperature);

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(message(
                "system",
                "\u4f60\u662f\u544a\u8b66\u5206\u6790\u52a9\u624b\u3002\u8bf7\u4ec5\u8f93\u51fa JSON\uff0c\u4e0d\u8981\u8f93\u51fa\u989d\u5916\u6587\u672c\u3002\u5fc5\u987b\u4f7f\u7528\u7b80\u4f53\u4e2d\u6587\u56de\u7b54\u3002"
        ));
        messages.add(message("user", buildPrompt(history)));
        payload.put("messages", messages);
        return payload;
    }

    private String buildPrompt(AlertHistory history) {
        String context = trim(safe(history.getContextJson()), Math.max(200, maxContextChars));
        return "\u8bf7\u5206\u6790\u4e0b\u9762\u8fd9\u6761\u544a\u8b66\uff0c\u5e76\u4ec5\u8fd4\u56de JSON\uff1a"
                + "{\"prediction\":\"...\",\"analysis\":\"...\",\"recommendation\":\"...\",\"riskScore\":0.0}\n\n"
                + "host=" + safe(history.getHost()) + "\n"
                + "ruleName=" + safe(history.getRuleName()) + "\n"
                + "metricName=" + safe(history.getMetricName()) + "\n"
                + "alertState=" + safe(history.getAlertState()) + "\n"
                + "previousState=" + safe(history.getPreviousState()) + "\n"
                + "thresholdType=" + safe(history.getThresholdType()) + "\n"
                + "level=" + safe(history.getLevel()) + "\n"
                + "currentValue=" + safe(history.getValue()) + "\n"
                + "thresholdValue=" + safe(history.getThresholdValue()) + "\n"
                + "meanValue=" + safe(history.getMeanValue()) + "\n"
                + "stdValue=" + safe(history.getStdValue()) + "\n"
                + "trendValue=" + safe(history.getTrendValue()) + "\n"
                + "reason=" + safe(history.getReason()) + "\n"
                + "recommendation=" + safe(history.getRecommendation()) + "\n"
                + "occurredAt=" + history.getOccurredAt() + "\n"
                + "contextJson=" + context + "\n\n"
                + "\u8981\u6c42\uff1aprediction\u3001analysis\u3001recommendation \u5fc5\u987b\u662f\u7b80\u4f53\u4e2d\u6587\uff0criskScore \u8303\u56f4\u5fc5\u987b\u5728 [0,1]\u3002";
    }

    private String callModel(Map<String, Object> payload) throws Exception {
        RestTemplate restTemplate = buildRestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        HttpEntity<String> request = new HttpEntity<>(objectMapper.writeValueAsString(payload), headers);
        String endpoint = resolveChatCompletionsEndpoint(baseUrl);
        try {
            ResponseEntity<String> response = restTemplate.exchange(endpoint, HttpMethod.POST, request, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException(
                        "\u8bf7\u6c42\u5931\u8d25 HTTP " + response.getStatusCode().value() + ", endpoint=" + endpoint
                );
            }
            String body = response.getBody();
            if (isBlank(body)) {
                throw new IllegalStateException("\u54cd\u5e94\u4f53\u4e3a\u7a7a, endpoint=" + endpoint);
            }
            return body;
        } catch (RestClientResponseException ex) {
            String detail = trim(ex.getResponseBodyAsString(), 500);
            throw new IllegalStateException(
                    "\u8bf7\u6c42\u5931\u8d25 HTTP " + ex.getRawStatusCode() + ", endpoint=" + endpoint + ", body=" + safe(detail)
            );
        } catch (ResourceAccessException ex) {
            throw new IllegalStateException(
                    "\u8fde\u63a5\u5931\u8d25, endpoint=" + endpoint + ", \u539f\u56e0=" + trim(ex.getMessage(), 500)
            );
        }
    }

    private AgentResult parseResult(String rawResponse) throws Exception {
        String content = extractAssistantContent(rawResponse);
        String jsonText = extractJson(content);
        if (isBlank(jsonText)) {
            AgentResult fallback = new AgentResult();
            fallback.prediction = "\u6a21\u578b\u8fd4\u56de\u975e\u7ed3\u6784\u5316\u5185\u5bb9";
            fallback.analysis = trim(content, MAX_ANALYSIS_LENGTH);
            fallback.recommendation = "\u5efa\u8bae\u6309\u9ed8\u8ba4\u544a\u8b66\u5904\u7f6e\u6d41\u7a0b\u5904\u7406\uff0c\u5e76\u68c0\u67e5\u6a21\u578b\u8f93\u51fa\u683c\u5f0f";
            fallback.riskScore = null;
            return fallback;
        }
        Map<String, Object> parsed = objectMapper.readValue(jsonText, new TypeReference<>() {});
        AgentResult result = new AgentResult();
        result.prediction = safe(parsed.get("prediction"));
        result.analysis = safe(parsed.get("analysis"));
        result.recommendation = safe(parsed.get("recommendation"));
        result.riskScore = clampRiskScore(parsed.get("riskScore"));
        if (isBlank(result.prediction)) result.prediction = "\u77ed\u671f\u8d8b\u52bf\u8bc1\u636e\u4e0d\u8db3";
        if (isBlank(result.analysis)) result.analysis = "\u6a21\u578b\u672a\u8fd4\u56de\u660e\u786e\u7684\u6839\u56e0\u5206\u6790";
        if (isBlank(result.recommendation)) result.recommendation = "\u5efa\u8bae\u5148\u6267\u884c\u57fa\u7ebf\u5904\u7f6e\u5e76\u6301\u7eed\u89c2\u5bdf";
        return result;
    }

    private String extractAssistantContent(String rawResponse) throws Exception {
        Map<String, Object> root = objectMapper.readValue(rawResponse, new TypeReference<>() {});
        Object errorObj = root.get("error");
        if (errorObj != null) {
            throw new IllegalStateException("\u6a21\u578b\u63a5\u53e3\u8fd4\u56de\u9519\u8bef: " + trim(String.valueOf(errorObj), 500));
        }
        Object choicesObj = root.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            throw new IllegalStateException("\u54cd\u5e94\u7f3a\u5c11 choices, raw=" + trim(rawResponse, 500));
        }
        Object first = choices.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) {
            throw new IllegalStateException("choices[0] \u683c\u5f0f\u9519\u8bef");
        }
        Object messageObj = firstMap.get("message");
        if (!(messageObj instanceof Map<?, ?> messageMap)) {
            throw new IllegalStateException("\u54cd\u5e94\u7f3a\u5c11 message");
        }
        Object contentObj = messageMap.get("content");
        return contentObj == null ? "" : String.valueOf(contentObj);
    }

    private String extractJson(String content) {
        if (isBlank(content)) {
            return null;
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            text = text.replace("```json", "").replace("```", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(connectTimeoutMs, 1000));
        factory.setReadTimeout(Math.max(readTimeoutMs, 1000));
        return new RestTemplate(factory);
    }

    private String resolveChatCompletionsEndpoint(String configuredBaseUrl) {
        String endpoint = safe(configuredBaseUrl).trim();
        if (endpoint.isEmpty()) {
            return endpoint;
        }
        if (endpoint.endsWith("/chat/completions")) {
            return endpoint;
        }
        String normalized = endpoint.replaceAll("/+$", "");
        if (normalized.endsWith("/v1")) {
            return normalized + "/chat/completions";
        }
        return endpoint;
    }

    private void markFailed(Long alertHistoryId, String message) {
        Optional<AlertHistory> optional = alertHistoryRepository.findById(alertHistoryId);
        if (optional.isEmpty()) return;
        AlertHistory history = optional.get();
        history.setAgentStatus("failed");
        history.setAgentAnalysis(trim(message, MAX_ANALYSIS_LENGTH));
        history.setAgentUpdatedAt(Instant.now());
        alertHistoryRepository.save(history);
    }

    private void updateAgentStatus(Long alertHistoryId, String status) {
        Optional<AlertHistory> optional = alertHistoryRepository.findById(alertHistoryId);
        if (optional.isEmpty()) return;
        AlertHistory history = optional.get();
        history.setAgentStatus(status);
        history.setAgentUpdatedAt(Instant.now());
        alertHistoryRepository.save(history);
    }

    private void updateAgentSkipped(Long alertHistoryId, String prediction, String analysis, String recommendation) {
        Optional<AlertHistory> optional = alertHistoryRepository.findById(alertHistoryId);
        if (optional.isEmpty()) return;
        AlertHistory history = optional.get();
        history.setAgentStatus("skipped");
        history.setAgentPrediction(trim(prediction, MAX_PREDICTION_LENGTH));
        history.setAgentAnalysis(trim(analysis, MAX_ANALYSIS_LENGTH));
        history.setAgentRecommendation(trim(recommendation, MAX_RECOMMENDATION_LENGTH));
        history.setAgentUpdatedAt(Instant.now());
        alertHistoryRepository.save(history);
    }

    private void updateAgentSuccess(Long alertHistoryId, AgentResult result, String rawResponse) {
        Optional<AlertHistory> optional = alertHistoryRepository.findById(alertHistoryId);
        if (optional.isEmpty()) return;
        AlertHistory history = optional.get();
        history.setAgentStatus("success");
        history.setAgentModel(model);
        history.setAgentRiskScore(result.riskScore);
        history.setAgentPrediction(trim(result.prediction, MAX_PREDICTION_LENGTH));
        history.setAgentAnalysis(trim(result.analysis, MAX_ANALYSIS_LENGTH));
        history.setAgentRecommendation(trim(result.recommendation, MAX_RECOMMENDATION_LENGTH));
        history.setAgentRawResponse(rawResponse);
        history.setAgentUpdatedAt(Instant.now());
        alertHistoryRepository.save(history);
    }

    private Double clampRiskScore(Object value) {
        if (value == null) return null;
        try {
            double score = Double.parseDouble(String.valueOf(value));
            if (score < 0) return 0.0;
            if (score > 1) return 1.0;
            return score;
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, String> message(String role, String content) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("role", role);
        item.put("content", content);
        return item;
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String trim(String value, int maxLength) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.length() <= maxLength) return normalized;
        return normalized.substring(0, maxLength);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static class AgentResult {
        private String prediction;
        private String analysis;
        private String recommendation;
        private Double riskScore;
    }
}
