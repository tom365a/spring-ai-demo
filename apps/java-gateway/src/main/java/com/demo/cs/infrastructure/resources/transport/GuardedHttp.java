package com.demo.cs.infrastructure.resources.transport;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GuardedHttp {
    private GuardedHttp() {}
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "resource-http-deadlines"); t.setDaemon(true); return t;
    });
    public record Response(int status, Map<String,String> headers, byte[] body) {
        public String header(String name) { return headers.getOrDefault(name.toLowerCase(Locale.ROOT), ""); }
    }
    @FunctionalInterface public interface Reader<T> { T read(int status, Map<String,String> headers, InputStream body) throws IOException; }

    public static Response request(JsonNode config, String method, String url, Map<String,String> headers, byte[] body, Duration timeout, int maxBytes) {
        return exchange(config, method, url, headers, body, timeout, (status, responseHeaders, input) ->
                new Response(status, responseHeaders, readBounded(input, maxBytes)));
    }

    public static <T> T exchange(JsonNode config, String method, String url, Map<String,String> headers, byte[] body, Duration timeout, Reader<T> reader) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw new TransportException("TIMEOUT", "任务预算已耗尽");
        long deadline = System.nanoTime() + timeout.toNanos();
        var target = TargetPolicy.resolve(url, config.path("target"), timeout);
        long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));
        DnsResolver pinned = new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                if (!TargetPolicy.normalizeHost(host).equals(target.host())) throw new UnknownHostException("unapproved host");
                return target.addresses().clone();
            }
            public String resolveCanonicalHostname(String host) { return target.host(); }
        };
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(pinned)
                .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(millis))
                        .setSocketTimeout(Timeout.ofMilliseconds(millis)).build()).build();
        var request = new HttpUriRequestBase(method, target.uri());
        request.setConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofMilliseconds(millis))
                .setResponseTimeout(Timeout.ofMilliseconds(millis)).build());
        headers.forEach((name,value) -> { validHeader(name,value); request.setHeader(name,value); });
        if (body != null) request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        var timedOut = new AtomicBoolean();
        ScheduledFuture<?> cancellation = DEADLINES.schedule(() -> {
            timedOut.set(true);
            request.cancel();
        }, millis, TimeUnit.MILLISECONDS);
        try (var client = HttpClients.custom().setConnectionManager(manager).disableRedirectHandling()
                .disableAutomaticRetries().disableCookieManagement().disableContentCompression().build()) {
            try (var response = client.executeOpen(null, request, null)) {
                int status = response.getCode();
                if (status < 200 || status >= 300) {
                    request.cancel();
                    throw new TransportException(status>=300 && status<400 ? "REDIRECT_DENIED" : "HTTP_ERROR",
                            "外部接口返回 HTTP " + status + (status>=300 && status<400 ? "，不自动跟随重定向" : "，请核对服务状态与权限"));
                }
                Map<String,String> resultHeaders = new LinkedHashMap<>();
                for (var h : response.getHeaders()) resultHeaders.put(h.getName().toLowerCase(Locale.ROOT), h.getValue());
                try (InputStream input = response.getEntity()==null ? InputStream.nullInputStream() : response.getEntity().getContent()) {
                    try { return reader.read(status, resultHeaders, input); }
                    finally { request.cancel(); }
                }
            }
        } catch (IOException e) {
            if (timedOut.get() || System.nanoTime()>=deadline || e instanceof java.net.SocketTimeoutException) {
                throw new TransportException("TIMEOUT", "外部调用超时；已发送的写操作结果需向业务系统核实，不会自动重试");
            }
            throw new TransportException("NETWORK_ERROR", "外部连接失败；已发送的写操作结果需向业务系统核实，不会自动重试");
        } finally { cancellation.cancel(false); manager.close(); }
    }

    public static byte[] readBounded(InputStream input, int maxBytes) throws IOException {
        byte[] value = input.readNBytes(maxBytes+1);
        if (value.length>maxBytes) throw new TransportException("RESPONSE_TOO_LARGE", "外部响应超过大小限制");
        return value;
    }
    public static Map<String,String> authHeaders(JsonNode config, String credential) {
        Map<String,String> headers = new LinkedHashMap<>();
        String type=config.path("auth").path("type").asText("NONE");
        if (type.equals("NONE")) return headers;
        if (credential==null || credential.isBlank()) throw new TransportException("CREDENTIAL_MISSING", "尚未配置可用凭证");
        if (type.equals("BEARER")) headers.put("Authorization", "Bearer "+credential);
        else if (type.equals("API_KEY_HEADER")) {
            String name=config.path("auth").path("headerName").asText();
            if (Set.of("host","cookie","content-length","transfer-encoding","connection","accept","content-type","mcp-session-id","mcp-protocol-version").contains(name.toLowerCase(Locale.ROOT))) throw new TransportException("INVALID_HEADER", "认证 Header 名称不可覆盖传输字段");
            headers.put(name, credential);
        } else throw new TransportException("INVALID_AUTH", "不支持的认证方式");
        headers.forEach(GuardedHttp::validHeader);
        return headers;
    }
    public static void validHeader(String name, String value) {
        if (name==null || !name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value==null || value.chars().anyMatch(c -> c<32 || c==127)) {
            throw new TransportException("INVALID_HEADER", "Header 名称或值不合法");
        }
    }
}
