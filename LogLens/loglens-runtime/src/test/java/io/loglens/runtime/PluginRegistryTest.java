package io.loglens.runtime;

import io.loglens.api.*;
import io.loglens.config.ConfigLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class PluginRegistryTest {
    @TempDir Path directory;
    public static class DuplicateFactory implements LogProcessorFactory {
        public String providerId() { return "builtin-template"; }
        public LogProcessor create(NodeDefinition n, NodeDependencies d) { return null; }
    }
    public static class CustomFactory implements LogProcessorFactory {
        public String providerId() { return "example"; }
        public LogProcessor create(NodeDefinition definition, NodeDependencies dependencies) {
            return new AbstractLogProcessor(definition, dependencies) {
                public void process(LogContext context, ChainContinuation next) { context.tags().add("custom"); next.proceed(context); }
            };
        }
    }
    public static class ChainA implements LogChainProvider {
        public ChainDefinition create(ChainBuildContext context) { return new ChainDefinition(List.of(new NodeDefinition("custom", "example", Map.of()))); }
    }
    public static class ChainB implements LogChainProvider {
        public ChainDefinition create(ChainBuildContext context) { return new ChainDefinition(context.configuredNodes()); }
    }
    private void jar(String name, Map<String, String> entries) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(directory.resolve(name)))) {
            for (var entry : entries.entrySet()) { jar.putNextEntry(new JarEntry(entry.getKey())); jar.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); jar.closeEntry(); }
        }
    }
    @Test void explicitDefaultFallbackHasAllBuiltinsAndRejectsUnknownProvider() throws Exception {
        try (PluginRegistry registry = new PluginRegistry(directory)) {
            NodeDependencies deps = new NodeDependencies(LogClock.system(), (c, m) -> {});
            assertEquals(5, registry.assemble(new ChainBuildContext("demo", ConfigLoader.defaultPipeline()), deps).size());
            assertThrows(IllegalArgumentException.class, () -> registry.assemble(new ChainBuildContext("demo", List.of(new NodeDefinition("x", "missing", Map.of()))), deps));
        }
    }
    @Test void bundledApiAndDuplicateProviderAreRejected() throws Exception {
        jar("api.jar", Map.of("io/loglens/api/LogProcessor.class", "fake"));
        assertThrows(IllegalArgumentException.class, () -> new PluginRegistry(directory));
        Files.delete(directory.resolve("api.jar"));
        jar("duplicate.jar", Map.of("META-INF/services/io.loglens.api.LogProcessorFactory", DuplicateFactory.class.getName()));
        assertThrows(IllegalArgumentException.class, () -> new PluginRegistry(directory));
    }
    @Test void oneEntireChainOverridesDefaultAndUsesSharedApiTypeIdentity() throws Exception {
        jar("plugin.jar", Map.of("META-INF/services/io.loglens.api.LogProcessorFactory", CustomFactory.class.getName(),
                "META-INF/services/io.loglens.api.LogChainProvider", ChainA.class.getName()));
        try (PluginRegistry registry = new PluginRegistry(directory)) {
            List<LogProcessor> nodes = registry.assemble(new ChainBuildContext("demo", ConfigLoader.defaultPipeline()), new NodeDependencies(LogClock.system(), (c, m) -> {}));
            assertEquals(1, nodes.size()); assertEquals("custom", nodes.get(0).nodeId());
        }
    }
    @Test void multipleEntireChainsReportBothConflictingClasses() throws Exception {
        jar("a.jar", Map.of("META-INF/services/io.loglens.api.LogChainProvider", ChainA.class.getName()));
        jar("b.jar", Map.of("META-INF/services/io.loglens.api.LogChainProvider", ChainB.class.getName()));
        Exception failure = assertThrows(IllegalArgumentException.class, () -> new PluginRegistry(directory));
        assertTrue(failure.getMessage().contains(ChainA.class.getName())); assertTrue(failure.getMessage().contains(ChainB.class.getName()));
    }
}
