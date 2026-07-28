package org.granitesecurity.profile.dto;

/**
 * The outcome of a delete request, stated explicitly so the UI can explain
 * rather than appear to fail: a user with paid orders is blocked, not deleted
 * (docs/users/blocking-users.md D1, §5.1).
 */
public record DeleteUserResult(String outcome, int paidOrderCount, int deletedOrderCount) {

    public static final String DONE = "DONE";
    public static final String BLOCKED_INSTEAD = "BLOCKED_INSTEAD";

    public static DeleteUserResult done(int deletedOrderCount) {
        return new DeleteUserResult(DONE, 0, deletedOrderCount);
    }

    public static DeleteUserResult blockedInstead(int paidOrderCount) {
        return new DeleteUserResult(BLOCKED_INSTEAD, paidOrderCount, 0);
    }
}
