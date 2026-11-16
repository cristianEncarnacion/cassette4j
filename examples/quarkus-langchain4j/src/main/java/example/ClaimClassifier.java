package example;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

/** Classifies insurance claims with an LLM. */
@RegisterAiService
public interface ClaimClassifier {

    @SystemMessage("You classify insurance claims. Answer with exactly one word: AUTO, HOME, HEALTH or OTHER.")
    @UserMessage("Classify this claim: {claim}")
    String classify(String claim);
}
