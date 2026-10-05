package io.renova.web.account;

/** @param passwordHash BCrypt hash; never sent to clients */
public record User(String id, String email, String name, String passwordHash, String createdAt) {

    /** What clients may see of a user. */
    public record View(String id, String email, String name) {
    }

    public View view() {
        return new View(id, email, name);
    }
}
