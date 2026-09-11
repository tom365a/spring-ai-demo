package com.demo.cs.infrastructure.tools;

/**
 * 工具执行期的会话上下文。
 * ToolExecutionGateway 把内置工具放在虚拟线程里跑，调用方线程的 ThreadLocal 传不进去，
 * 所以统一在这里落一次，需要 sessionId 的工具（如转人工）从这里取。
 */
public final class ToolSessionContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private ToolSessionContext() {
    }

    public static void set(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(sessionId);
        }
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
