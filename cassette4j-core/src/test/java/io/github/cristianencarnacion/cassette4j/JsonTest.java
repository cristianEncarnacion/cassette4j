package io.github.cristianencarnacion.cassette4j;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {

    @Test
    void roundTripKeepsOrderAndNumbers() {
        String src = "{\"b\":1,\"a\":[true,false,null,-0.50,1e10],\"s\":\"x\\ny \\\"q\\\" \\u00e9\"}";
        Object v = Json.parse(src);
        assertEquals(src.replace("\\u00e9", "é"), Json.write(v));
    }

    @Test
    void canonicalSortsKeysAtEveryLevel() {
        Object a = Json.parse("{\"z\":{\"y\":1,\"x\":2},\"a\":[{\"d\":1,\"c\":2}]}");
        assertEquals("{\"a\":[{\"c\":2,\"d\":1}],\"z\":{\"x\":2,\"y\":1}}", Json.canonical(a));
    }

    @Test
    void prettyIsParseable() {
        Object v = Json.parse("{\"a\":{\"b\":[1,2,{}],\"c\":[]}}");
        assertEquals(Json.write(v), Json.write(Json.parse(Json.pretty(v))));
        assertTrue(Json.pretty(v).contains("\n  \"a\": {"));
    }

    @Test
    void rejectsInvalidJson() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} extra"));
        assertFalse(Json.isJson("data: {}"));
        assertTrue(Json.isJson("  [1]  "));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesNestedStructures() {
        Map<String, Object> m = (Map<String, Object>) Json.parse("{\"list\":[{\"k\":\"v\"}]}");
        List<Object> list = (List<Object>) m.get("list");
        assertEquals("v", ((Map<String, Object>) list.get(0)).get("k"));
    }

    @Test
    void ignoredFieldsAreRemovedAtAnyDepth() {
        Object v = Json.parse("{\"user\":\"a\",\"m\":[{\"user\":\"b\",\"c\":1}]}");
        assertEquals("{\"m\":[{\"c\":1}]}", Json.canonical(Body.withoutFields(v, java.util.Set.of("user"))));
    }
}
