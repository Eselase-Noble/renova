package io.renova.java.detect;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RenamedMethodCallersTest {

    private static final Map<String, String> RENAMES = Map.of("setSession", "withSession");

    @Test
    void findsCallsOnClassesThatNowDeclareTheNewName() {
        Map<String, List<String>> sources = new LinkedHashMap<>();
        sources.put("src/main/java/a/TicketAction.java", List.of("public class TicketAction implements SessionAware {",
                "    public void withSession(Map<String, Object> session) {", "    }", "}"));
        sources.put("src/main/java/a/UrgentAction.java", List.of("public class UrgentAction extends TicketAction {", "}"));
        sources.put("src/main/java/a/Untouched.java", List.of("public class Untouched {",
                "    public void setSession(Map<String, Object> session) {", "    }", "}"));
        sources.put("src/test/java/a/TicketActionTest.java", List.of(
                "TicketAction action = new TicketAction();",
                "action.setSession(session);",
                "var urgent = new UrgentAction();",
                "urgent.setSession(session);",
                "MockHttpServletRequest request = new MockHttpServletRequest();",
                "request.setSession(httpSession);",
                "Untouched other = new Untouched();",
                "other.setSession(session);"));
        assertThat(RenamedMethodCallers.calls(sources, RENAMES)).extracting(c -> c.line() + ":" + c.variable())
                .containsExactlyInAnyOrder("2:action", "4:urgent");
    }

    @Test
    void aProjectWhereNothingWasRenamedHasNoFindings() {
        Map<String, List<String>> sources = Map.of("src/main/java/a/A.java", List.of("class A {",
                "    public void setSession(Map<String, Object> s) {", "    }", "}"), "src/test/java/a/T.java",
                List.of("A a = new A();", "a.setSession(m);"));
        assertThat(RenamedMethodCallers.calls(sources, RENAMES)).isEmpty();
    }
}
