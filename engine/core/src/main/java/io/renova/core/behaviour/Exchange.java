package io.renova.core.behaviour;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One application's answer to a scenario.
 *
 * @param status  HTTP status, or -1 when no response arrived
 * @param headers response headers, names lower-case
 * @param error   why no response arrived; null otherwise
 */
public record Exchange(int status, Map<String, String> headers, @JsonIgnore byte[] body, String error) {

    private static final Pattern CHARSET = Pattern.compile("charset=\"?([\\w.:-]+)", Pattern.CASE_INSENSITIVE);

    public Exchange {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    public static Exchange failed(String error) {
        return new Exchange(-1, Map.of(), new byte[0], error);
    }

    public boolean responded() {
        return status >= 0;
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** The body decoded with the response's charset, or UTF-8. */
    public String text() {
        Charset charset = StandardCharsets.UTF_8;
        String type = header("content-type");
        if (type != null) {
            Matcher m = CHARSET.matcher(type);
            if (m.find() && Charset.isSupported(m.group(1))) {
                charset = Charset.forName(m.group(1));
            }
        }
        return new String(body, charset);
    }
}
