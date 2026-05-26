package world.willfrog.agentlangchain.routing;

import org.junit.jupiter.api.Test;
import world.willfrog.agent.platform.entity.AgentRun;

import static org.junit.jupiter.api.Assertions.*;

class LangchainSingleWriterGuardTest {

    private final LangchainSingleWriterGuard guard = new LangchainSingleWriterGuard();

    @Test
    void allowsLegacyExtBecauseLangchainIsDefaultProvider() {
        AgentRun run = run("{\"run_provider\":\"legacy\"}");
        assertSame(run, guard.requireReadable(run));
        assertSame(run, guard.requireWritable(run));
    }

    @Test
    void rejectsMissingRun() {
        assertThrows(IllegalArgumentException.class, () -> guard.requireReadable(null));
        assertThrows(IllegalArgumentException.class, () -> guard.requireWritable(null));
        assertThrows(IllegalArgumentException.class, () -> guard.markLangchainOwner(null));
    }

    @Test
    void markLangchainOwnerNoopsWithoutOverwritingOpenRouterProvider() {
        AgentRun run = run("{\"provider\":\"fireworks,novita\"}");
        AgentRun result = guard.markLangchainOwner(run);
        assertSame(run, result);
        assertEquals("{\"provider\":\"fireworks,novita\"}", result.getExt());
    }

    private AgentRun run(String ext) {
        AgentRun run = new AgentRun();
        run.setId("r1");
        run.setUserId("u1");
        run.setExt(ext);
        return run;
    }
}
