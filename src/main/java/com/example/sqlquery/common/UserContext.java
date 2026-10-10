package com.example.sqlquery.common;

public class UserContext {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> ROLE_HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(Long userId) {
        HOLDER.set(userId);
    }

    public static Long get() {
        return HOLDER.get();
    }

    public static void setRole(String role) {
        ROLE_HOLDER.set(role);
    }

    public static String getRole() {
        return ROLE_HOLDER.get();
    }

    /** 当前请求是不是管理员。见 {@link UserRole#isAdmin(String)}。 */
    public static boolean isAdmin() {
        return UserRole.isAdmin(ROLE_HOLDER.get());
    }

    public static void clear() {
        HOLDER.remove();
        ROLE_HOLDER.remove();
    }
}
