package io.renova.core.licence;

import java.time.LocalDate;
import java.util.List;

/**
 * What a customer was sold: who may use Renova, for which ecosystems, and until when. A licence is a file the
 * vendor signs; Renova checks the signature on the customer's machine and never calls home.
 *
 * @param id         the vendor's reference for this licence
 * @param licensee   the person or organisation it was issued to
 * @param edition    a name for what was sold, such as "trial" or "team"; shown, not interpreted
 * @param ecosystems plugin ids the licence covers ("java", "dotnet"); "*" covers every ecosystem
 * @param seats      how many people may use it; stated in the licence and not counted by Renova
 * @param expires    the last day the licence is valid
 */
public record Licence(String id, String licensee, String edition, List<String> ecosystems, int seats, LocalDate issued,
                      LocalDate expires) {

    public Licence {
        ecosystems = ecosystems == null ? List.of() : List.copyOf(ecosystems);
    }

    public boolean covers(String ecosystem) {
        return ecosystems.contains("*") || ecosystems.contains(ecosystem);
    }

    public boolean expired(LocalDate today) {
        return expires != null && today.isAfter(expires);
    }

    /** One line for a status display. */
    public String describe() {
        return licensee + " (" + edition + "): " + (ecosystems.contains("*") ? "every ecosystem" : String.join(", ", ecosystems))
                + ", " + seats + " seat" + (seats == 1 ? "" : "s") + ", valid until " + expires;
    }
}
