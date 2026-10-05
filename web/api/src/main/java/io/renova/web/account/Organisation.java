package io.renova.web.account;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** A team that shares projects, migrations and AI keys. */
public record Organisation(String id, String name, String createdAt, List<Member> members) {

    public record Member(String userId, Role role, String joinedAt) {
    }

    public Organisation {
        members = members == null ? List.of() : List.copyOf(members);
    }

    public Optional<Role> roleOf(String userId) {
        return members.stream().filter(m -> m.userId().equals(userId)).map(Member::role).findFirst();
    }

    public long owners() {
        return members.stream().filter(m -> m.role() == Role.OWNER).count();
    }

    public Organisation withMember(String userId, Role role, String now) {
        List<Member> updated = new ArrayList<>(members.stream().filter(m -> !m.userId().equals(userId)).toList());
        String joined = members.stream().filter(m -> m.userId().equals(userId)).map(Member::joinedAt).findFirst().orElse(now);
        updated.add(new Member(userId, role, joined));
        return new Organisation(id, name, createdAt, updated);
    }

    public Organisation without(String userId) {
        return new Organisation(id, name, createdAt, members.stream().filter(m -> !m.userId().equals(userId)).toList());
    }
}
