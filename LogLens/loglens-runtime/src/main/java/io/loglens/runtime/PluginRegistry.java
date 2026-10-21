package io.loglens.runtime;

import io.loglens.api.*;
import io.loglens.config.ConfigLoader;
import io.loglens.processors.BuiltinFactories;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.jar.JarFile;

/** Startup-only plugin discovery; never scans business dependencies or TCCL. */
public final class PluginRegistry implements AutoCloseable {
    private static final class PluginClassLoader extends URLClassLoader {
        PluginClassLoader(URL[] urls, ClassLoader parent) { super(urls, parent); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("io.loglens.api.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) { try { loaded = findClass(name); } catch (ClassNotFoundException e) { loaded = super.loadClass(name, false); } }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }
    private final PluginClassLoader loader;
    private final Map<String, LogProcessorFactory> factories = new LinkedHashMap<>();
    private final List<LogChainProvider> chains;
    public PluginRegistry(Path directory) throws IOException {
        List<Path> jars;
        if (!Files.exists(directory)) jars = List.of();
        else {
            if (!Files.isDirectory(directory)) throw new IllegalArgumentException("plugins path is not a directory");
            try (var stream = Files.list(directory)) { jars = stream.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList(); }
        }
        if (jars.size() > 128) throw new IllegalArgumentException("maximum 128 plugin JARs");
        List<URL> urls = new ArrayList<>();
        for (Path jar : jars) {
            try (JarFile archive = new JarFile(jar.toFile())) {
                if (archive.stream().anyMatch(entry -> entry.getName().startsWith("io/loglens/api/") && entry.getName().endsWith(".class")))
                    throw new IllegalArgumentException("plugin bundles duplicate loglens-api: " + jar.getFileName());
            }
            urls.add(jar.toUri().toURL());
        }
        loader = new PluginClassLoader(urls.toArray(URL[]::new), LogProcessorFactory.class.getClassLoader());
        try {
            for (LogProcessorFactory factory : BuiltinFactories.all()) register(factory);
            for (LogProcessorFactory factory : ServiceLoader.load(LogProcessorFactory.class, loader)) register(factory);
            chains = ServiceLoader.load(LogChainProvider.class, loader).stream().map(ServiceLoader.Provider::get).toList();
            if (chains.size() > 1) throw new IllegalArgumentException("multiple LogChainProviders: " + chains.stream().map(p -> p.getClass().getName()).toList());
        } catch (Throwable failure) {
            try { loader.close(); } catch (IOException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }
    private void register(LogProcessorFactory factory) {
        String id = factory.providerId();
        if (id == null || id.isBlank()) throw new IllegalArgumentException("plugin providerId required: " + factory.getClass().getName());
        LogProcessorFactory existing = factories.putIfAbsent(id, factory);
        if (existing != null) throw new IllegalArgumentException("duplicate providerId " + id + ": " + existing.getClass().getName() + ", " + factory.getClass().getName());
    }
    public List<LogProcessor> assemble(ChainBuildContext context, NodeDependencies dependencies) {
        ChainDefinition definition = chains.isEmpty() ? new ChainDefinition(context.configuredNodes()) : chains.get(0).create(context);
        if (definition == null) throw new IllegalArgumentException("chain provider returned null");
        ConfigLoader.validatePipeline(definition.nodes());
        List<LogProcessor> nodes = new ArrayList<>();
        try {
            for (NodeDefinition node : definition.nodes()) {
                LogProcessorFactory factory = factories.get(node.provider());
                if (factory == null) throw new IllegalArgumentException("unknown provider " + node.provider() + " for " + node.id());
                LogProcessor processor = factory.create(node, dependencies);
                if (processor == null || !node.id().equals(processor.nodeId())) throw new IllegalArgumentException("factory returned invalid node for " + node.id());
                nodes.add(processor);
            }
            if (chains.isEmpty()) dependencies.diagnostics().report("DEFAULT_CHAIN", "Using configured default chain");
            return List.copyOf(nodes);
        } catch (Throwable failure) {
            for (int i = nodes.size() - 1; i >= 0; i--) {
                try { nodes.get(i).close(); } catch (Throwable close) { failure.addSuppressed(close); }
            }
            throw failure;
        }
    }
    public List<String> providerIds() { return List.copyOf(factories.keySet()); }
    @Override public void close() throws IOException { loader.close(); }
}
