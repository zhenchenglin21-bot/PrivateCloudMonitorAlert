package com.privatecloud.security;

public class AuthContext {
    private static final ThreadLocal<AuthSession> SESSION = new ThreadLocal<>();

    public static void set(AuthSession session) {
        SESSION.set(session);
    }

    public static AuthSession get() {
        return SESSION.get();
    }

    public static void clear() {
        SESSION.remove();
    }
}
