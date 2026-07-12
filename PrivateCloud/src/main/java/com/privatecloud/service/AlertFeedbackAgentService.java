package com.privatecloud.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AlertFeedbackAgentService {
    private static final Logger log = LoggerFactory.getLogger(AlertFeedbackAgentService.class);

    public enum Decision {
        FALSE,
        UNKNOWN
    }

    private final ObjectMapper objectMapper;

    @Value("${alert.feedback-agent.enabled:false}")
    private boolean enabled;

    @Value("${alert.feedback-agent.base-url:}")
    private String baseUrl;

    @Value("${alert.feedback-agent.api-key:}")
    private String apiKey;

    @Value("${alert.feedback-agent.model:gpt-4o-mini}")
    private String model;

    @Value("${alert.feedback-agent.temperature:0.0}")
    private double temperature;

    @Value("${alert.feedback-agent.connect-timeout-ms:5000}")
    private int connectTimeoutMs;

    @Value("${alert.feedback-agent.read-timeout-ms:20000}")
    private int readTimeoutMs;

    public AlertFeedbackAgentService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void logFeedbackAgentBootstrapConfig() {
        log.info(
                "feedback agent config loaded: enabled={}, endpoint={}, model={}",
                enabled,
                resolveChatCompletionsEndpoint(baseUrl),
                model
        );
    }

    public Decision classifyFalsePositive(String shortCode, String alertInfo, String replyText) {
        if (!enabled || isBlank(baseUrl) || isBlank(apiKey)) {
            return Decision.UNKNOWN;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("temperature", temperature);

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(message("system",
                    "You classify alert feedback. Only decide whether it is a false positive. Output must be FALSE or UNKNOWN."));
            messages.add(message(
                    "user",
                    "short_code: " + safe(shortCode) + "\n"
                            + "alert_info: " + safe(trim(alertInfo, 1000)) + "\n"
                            + "reply_text: " + safe(trim(replyText, 1400)) + "\n\n"
                            + "Return exactly one enum token: FALSE or UNKNOWN."
            ));
            payload.put("messages", messages);

            String rawResponse = callModel(payload);
            logTokenUsage(shortCode, rawResponse);
            String content = extractAssistantContent(rawResponse);
            String normalized = isBlank(content) ? "" : content.trim().toUpperCase(Locale.ROOT);
            if (normalized.contains("FALSE")) {
                return Decision.FALSE;
            }
            if (normalized.contains("UNKNOWN")) {
                return Decision.UNKNOWN;
            }

            String jsonText = extractJson(content);
            if (!isBlank(jsonText)) {
                Map<String, Object> parsed = objectMapper.readValue(jsonText, new TypeReference<>() {});
                Object result = parsed.get("result");
                if (result != null && "FALSE".equalsIgnoreCase(String.valueOf(result).trim())) {
                    return Decision.FALSE;
                }
            }
        } catch (Exception ex) {
            log.warn(
                    "feedback agent classify failed, shortCode={}, reason={}",
                    safe(shortCode),
                    trim(ex.getMessage(), 500)
            );
            return Decision.UNKNOWN;
        }
        return Decision.UNKNOWN;
    }

    private void logTokenUsage(String shortCode, String rawResponse) {
        try {
            Map<String, Object> root = objectMapper.readValue(rawResponse, new TypeReference<>() {});
            Object usageObj = root.get("usage");
            if (!(usageObj instanceof Map<?, ?> usageMap)) {
                return;
            }
            String promptTokens = toTokenText(usageMap.get("prompt_tokens"), usageMap.get("input_tokens"));
            String completionTokens = toTokenText(usageMap.get("completion_tokens"), usageMap.get("output_tokens"));
            String totalTokens = toTokenText(usageMap.get("total_tokens"), usageMap.get("total_tokens"));
            if (isBlank(promptTokens) && isBlank(completionTokens) && isBlank(totalTokens)) {
                return;
            }
            log.info(
                    "feedback agent token usage, shortCode={}, prompt={}, completion={}, total={}",
                    safe(shortCode),
                    emptyDash(promptTokens),
                    emptyDash(completionTokens),
                    emptyDash(totalTokens)
            );
        } catch (Exception ignore) {
        }
    }

    private String toTokenText(Object primary, Object fallback) {
        if (primary != null) {
            return String.valueOf(primary).trim();
        }
        if (fallback != null) {
            return String.valueOf(fallback).trim();
        }
        return "";
    }

    private String emptyDash(String value) {
        return isBlank(value) ? "--" : value;
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
                throw new IllegalStateException("HTTP " + response.getStatusCode().value() + ", endpoint=" + endpoint);
            }
            String body = response.getBody();
            if (isBlank(body)) {
                throw new IllegalStateException("empty response body, endpoint=" + endpoint);
            }
            return body;
        } catch (RestClientResponseException ex) {
            String detail = trim(ex.getResponseBodyAsString(), 500);
            throw new IllegalStateException(
                    "HTTP " + ex.getRawStatusCode() + ", endpoint=" + endpoint + ", body=" + safe(detail)
            );
        } catch (ResourceAccessException ex) {
            throw new IllegalStateException("connect failed, endpoint=" + endpoint + ", reason=" + trim(ex.getMessage(), 500));
        }
    }

    private String extractAssistantContent(String rawResponse) throws Exception {
        Map<String, Object> root = objectMapper.readValue(rawResponse, new TypeReference<>() {});
        Object choicesObj = root.get("choices");
        if (!(choicesObj instanceof List<?> choices) || choices.isEmpty()) {
            throw new IllegalStateException("missing choices");
        }
        Object first = choices.get(0);
        if (!(first instanceof Map<?, ?> firstMap)) {
            throw new IllegalStateException("bad choice");
        }
        Object messageObj = firstMap.get("message");
        if (!(messageObj instanceof Map<?, ?> messageMap)) {
            throw new IllegalStateException("missing message");
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

    private Map<String, String> message(String role, String content) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("role", role);
        item.put("content", content);
        return item;
    }

    private String trim(String value, int maxLength) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.length() <= maxLength) return normalized;
        return normalized.substring(0, maxLength);
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
