package io.github.cristianencarnacion.cassette4j;

import java.util.LinkedHashMap;
import java.util.Map;

/** One recorded request/response pair. */
public record Interaction(String method,
                          String path,
                          Body requestBody,
                          int status,
                          String contentType,
                          Body responseBody) {

    /** Key used to find the recorded answer for an incoming request. */
    String matchKey(MatchRules rules) {
        return method + " " + path + "\n" + requestBody.matchKey(rules);
    }

    Map<String, Object> toMap() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("method", method);
        req.put("path", path);
        requestBody.writeInto(req);

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", new Json.Num(Integer.toString(status)));
        if (contentType != null) {
            res.put("contentType", contentType);
        }
        responseBody.writeInto(res);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("request", req);
        m.put("response", res);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Interaction fromMap(Map<String, Object> m) {
        Map<String, Object> req = (Map<String, Object>) m.get("request");
        Map<String, Object> res = (Map<String, Object>) m.get("response");
        if (req == null || res == null) {
            throw new IllegalArgumentException("Each interaction needs 'request' and 'response'");
        }
        Object status = res.get("status");
        return new Interaction(
                String.valueOf(req.get("method")),
                String.valueOf(req.get("path")),
                Body.readFrom(req),
                status == null ? 200 : Integer.parseInt(status.toString()),
                res.get("contentType") == null ? null : String.valueOf(res.get("contentType")),
                Body.readFrom(res));
    }
}
