package com.demo.cs.infrastructure.resources.transport;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.*;

/** Validate and pin DNS answers to one request; HTTP never resolves the hostname a second time. */
public final class TargetPolicy {
    private TargetPolicy() {}
    public record PinnedTarget(URI uri, String host, InetAddress[] addresses) {}

    public static PinnedTarget resolve(String url, JsonNode target, Duration timeout) {
        final URI uri;
        try { uri = URI.create(url); } catch (RuntimeException e) { throw error("地址格式不正确"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = normalizeHost(uri.getHost());
        int port = uri.getPort() >= 0 ? uri.getPort() : scheme.equals("https") ? 443 : 80;
        if (!scheme.equals("https") && !scheme.equals("http")) throw error("仅支持 HTTP(S) 地址");
        if (host.isBlank() || uri.getRawUserInfo() != null || uri.getRawFragment() != null || host.contains("%")) throw error("地址不能包含用户信息、片段或地址作用域");
        if (port < 1 || port > 65535 || !scheme.equals(target.path("scheme").asText().toLowerCase(Locale.ROOT))
                || !host.equals(normalizeHost(target.path("host").asText())) || port != target.path("port").asInt(-1)) {
            throw error("连接目标未获精确授权，请核对协议、主机和端口");
        }
        FutureTask<InetAddress[]> dns = new FutureTask<>(() -> InetAddress.getAllByName(host));
        Thread.startVirtualThread(dns);
        try {
            InetAddress[] addresses = dns.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (addresses.length == 0) throw error("目标地址无法解析");
            for (InetAddress address : addresses) checkAddress(address, target.path("allowPrivate").asBoolean(false));
            return new PinnedTarget(uri, host, addresses.clone());
        } catch (TimeoutException e) { dns.cancel(true); throw new TransportException("TIMEOUT", "目标地址解析超时"); }
        catch (InterruptedException e) { dns.cancel(true); Thread.currentThread().interrupt(); throw new TransportException("CANCELLED", "调用已取消"); }
        catch (ExecutionException e) { throw error("目标地址无法解析"); }
    }

    public static String normalizeHost(String host) {
        if (host == null) return "";
        String value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length()-1);
        if (value.endsWith(".")) value = value.substring(0, value.length()-1);
        return value;
    }

    public static void checkAddress(InetAddress address, boolean allowPrivate) {
        byte[] b = address.getAddress();
        if (address.isAnyLocalAddress() || address.isMulticastAddress() || address.isLinkLocalAddress()) throw error("禁止访问保留地址或云元数据端点");
        boolean local = address.isLoopbackAddress() || address.isSiteLocalAddress();
        if (b.length == 4) {
            int a=b[0]&255, c=b[1]&255, d=b[2]&255, e=b[3]&255;
            if (a==0 || a>=224 || (a==100 && c==100 && d==100 && e==200)
                    || (a==192 && c==0 && (d==0 || d==2)) || (a==198 && (c==18 || c==19 || (c==51 && d==100)))
                    || (a==203 && c==0 && d==113)) throw error("禁止访问保留地址或云元数据端点");
            local |= a==100 && c>=64 && c<=127;
        } else {
            int first=b[0]&255;
            local |= (first & 0xfe)==0xfc;
            if (!local && ((first & 0xe0)!=0x20 || (first==0x20 && b[1]==1 && (b[2]&255)==0x0d && (b[3]&255)==0xb8))) {
                throw error("禁止访问保留 IPv6 地址");
            }
        }
        if (local && !allowPrivate) throw error("本地或内网目标需要管理员明确授权");
    }
    private static TransportException error(String message) { return new TransportException("TARGET_DENIED", message); }
}
