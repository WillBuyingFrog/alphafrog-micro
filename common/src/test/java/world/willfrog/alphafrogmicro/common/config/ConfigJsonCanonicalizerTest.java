package world.willfrog.alphafrogmicro.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigJsonCanonicalizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void md5HexShouldIgnoreObjectFieldOrder() throws Exception {
        String left = "{\"b\":2,\"a\":{\"d\":4,\"c\":3},\"items\":[{\"z\":1,\"y\":2}]}";
        String right = "{\"items\":[{\"y\":2,\"z\":1}],\"a\":{\"c\":3,\"d\":4},\"b\":2}";

        assertEquals(
                ConfigJsonCanonicalizer.md5Hex(objectMapper.readTree(left)),
                ConfigJsonCanonicalizer.md5Hex(objectMapper.readTree(right))
        );
        assertEquals(
                ConfigJsonCanonicalizer.md5Hex(left.getBytes(StandardCharsets.UTF_8)),
                ConfigJsonCanonicalizer.md5Hex(right.getBytes(StandardCharsets.UTF_8))
        );
        assertEquals(
                ConfigJsonCanonicalizer.md5Hex(left),
                ConfigJsonCanonicalizer.md5Hex(right)
        );
    }
}
