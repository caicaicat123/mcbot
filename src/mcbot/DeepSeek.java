package mcbot;

import java.net.URI;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/** DeepSeek Chat Completions 的最小客户端。所有请求都是异步的。 */
public final class DeepSeek {

    private final HttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSeconds;
    private final int maxAttempts;

    public DeepSeek(String apiKey, String baseUrl, String model, double temperature,
                    int maxTokens, int timeoutSeconds, int maxAttempts) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.deepseek.com" : baseUrl.trim();
        this.model = model == null || model.isBlank() ? "deepseek-chat" : model.trim();
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeoutSeconds = timeoutSeconds;
        this.maxAttempts = Math.max(1, maxAttempts);
        // 显式不走代理：服务器上若有插件设置过 https.proxyHost，会让连接卡死到超时
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .proxy(ProxySelector.of(null))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public boolean configured() {
        return !apiKey.isBlank() && !apiKey.contains("填入");
    }

    public CompletableFuture<String> chat(String systemPrompt, String userPrompt) {
        return attempt(systemPrompt, userPrompt, 1);
    }

    /** 轻量连通性检查：不带密钥地打一次根路径，只看网络能不能通。 */
    public CompletableFuture<Integer> ping() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).thenApply(HttpResponse::statusCode);
    }

    private CompletableFuture<String> attempt(String systemPrompt, String userPrompt, int tryNo) {
        return send(systemPrompt, userPrompt).exceptionallyCompose(error -> {
            if (tryNo < maxAttempts) {
                return CompletableFuture
                        .supplyAsync(() -> null,
                                CompletableFuture.delayedExecutor(700L * tryNo, TimeUnit.MILLISECONDS))
                        .thenCompose(ignored -> attempt(systemPrompt, userPrompt, tryNo + 1));
            }
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            return CompletableFuture.failedFuture(cause);
        });
    }

    private CompletableFuture<String> send(String systemPrompt, String userPrompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("HTTP " + response.statusCode() + " " + shorten(response.body()));
                    }
                    return extractContent(response.body());
                });
    }

    private static String shorten(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "..." : t;
    }

    @SuppressWarnings("unchecked")
    static String extractContent(String responseBody) {
        Map<String, Object> root = Json.parseObject(responseBody);
        Object choices = root.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> choice)) {
            return "";
        }
        Object message = choice.get("message");
        if (!(message instanceof Map<?, ?> msg)) {
            return "";
        }
        Object content = msg.get("content");
        return content == null ? "" : String.valueOf(content).trim();
    }
}
