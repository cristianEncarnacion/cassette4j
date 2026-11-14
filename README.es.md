# cassette4j (español)

**Graba una vez las llamadas a la IA y repítelas en tus tests de Java para siempre: sin internet, sin costo y siempre con la misma respuesta.**

[Read in English](README.md)

## El problema

Probar código que llama a OpenAI, Anthropic, Gemini u otro LLM es incómodo:

- Si llamas al modelo real, los tests son **lentos, cuestan dinero y a veces fallan solos**, y en CI necesitan internet y API key.
- Si haces mocks a mano, **inventas** la respuesta del modelo. Las tool calls y el streaming nunca se parecen a lo real: el test pasa y producción explota.

## La solución

cassette4j es un proxy local pequeño que se pone entre tu código y la API. La primera vez graba la petición y la respuesta reales en un JSON junto a tus tests. Las siguientes veces responde desde ese archivo.

```java
@Test
@LlmCassette
void clasificaUnChoque() {
    assertEquals("AUTO", clasificador.clasificar("Choqué el carro contra una pared"));
}
```

## Instalación

```xml
<dependency>
    <groupId>io.github.cristianencarnacion</groupId>
    <artifactId>cassette4j-junit5</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

En Quarkus usa `cassette4j-quarkus` (ya incluye el módulo de JUnit 5).

## Uso con Quarkus

```java
@QuarkusTest
@QuarkusTestResource(Cassette4jTestResource.class)
class ClasificadorTest {

    @Inject
    Clasificador clasificador;

    @Test
    @LlmCassette
    void clasificaUnChoque() {
        assertEquals("AUTO", clasificador.clasificar("Choqué el carro contra una pared"));
    }
}
```

En `src/test/resources/application.properties`:

```properties
quarkus.langchain4j.openai.api-key=${OPENAI_API_KEY:replay-only}
```

## Modos

| Modo | Qué hace | Cuándo |
|---|---|---|
| `AUTO` | Repite lo grabado y graba lo que falte | En tu máquina (por defecto) |
| `REPLAY` | Solo repite; si falta una llamada, el test falla | En CI (por defecto con `CI=true`) |
| `RECORD` | Llama a la API real y sobrescribe la grabación | Cuando cambias el prompt a propósito |
| `PASSTHROUGH` | Llama a la API real y no graba nada | Para depurar contra el modelo real |

Volver a grabar todo:

```bash
OPENAI_API_KEY=sk-... mvn test -Dcassette4j.mode=RECORD
```

## Seguridad

Los headers (donde va la API key) nunca se guardan, y cualquier texto con forma de llave (`sk-…`, `AIza…`, `?key=…`) se reemplaza por `<redacted>`.

La documentación completa (Spring AI, LangChain4j, proveedores, configuración) está en el [README en inglés](README.md).
