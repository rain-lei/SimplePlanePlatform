package io.loglens.launcher;

import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.jar.JarFile;

/** Java 8-compatible premain shell; it disables monitoring on unsupported runtimes. */
public final class LogLensAgent {
    private static URLClassLoader runtimeLoader;
    private static JarFile bridgeArchive;
    private LogLensAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
        premain(arguments, instrumentation, System.getProperty("java.specification.version", "unknown"));
    }

    static void premain(String arguments, Instrumentation instrumentation, String specificationVersion) {
        boolean strict = arguments != null && arguments.startsWith("strict=true,");
        String incompatibility = compatibilityError(specificationVersion);
        if (incompatibility != null) {
            System.err.println("[LogLens] COMPATIBILITY_ERROR Monitoring disabled: " + incompatibility);
            if (strict) throw new IllegalStateException("LogLens strict initialization failed: " + incompatibility);
            return;
        }
        try {
            String config = configPath(arguments);
            Path source = Paths.get(LogLensAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
            Path directory = source.getParent().resolve(".loglens-runtime").resolve(UUID.randomUUID().toString());
            Files.createDirectories(directory);
            Path bridge = extract("loglens-bootstrap.jar", directory);
            Path runtime = extract("loglens-runtime.jar", directory);
            bridgeArchive = new JarFile(bridge.toFile());
            instrumentation.appendToBootstrapClassLoaderSearch(bridgeArchive);
            runtimeLoader = new URLClassLoader(new URL[]{runtime.toUri().toURL()}, ClassLoader.getSystemClassLoader().getParent());
            Class<?> entry = Class.forName("io.loglens.agent.AgentRuntime", true, runtimeLoader);
            entry.getMethod("start", String.class, Instrumentation.class).invoke(null, config, instrumentation);
        } catch (Throwable failure) {
            Throwable detail = failure instanceof java.lang.reflect.InvocationTargetException
                    ? ((java.lang.reflect.InvocationTargetException) failure).getCause() : failure;
            strict |= "io.loglens.agent.AgentRuntime$StrictInitializationFailure".equals(detail.getClass().getName());
            if (runtimeLoader != null) try { runtimeLoader.close(); } catch (Exception ignored) {}
            System.err.println("[LogLens] initialization disabled: " + detail.getClass().getName() + conflictDetail(detail));
            if (strict) throw new IllegalStateException("LogLens strict initialization failed", failure);
        }
    }

    public static String configPath(String arguments) {
        if (arguments == null) throw new IllegalArgumentException("config argument required");
        String remainder = arguments.startsWith("strict=true,") ? arguments.substring(12) : arguments;
        if (!remainder.startsWith("config=") || remainder.length() == 7) throw new IllegalArgumentException("expected config=<path>");
        return Paths.get(remainder.substring(7)).toAbsolutePath().normalize().toString();
    }

    static String compatibilityError(String specificationVersion) {
        int major = 0;
        if (specificationVersion != null) {
            int start = specificationVersion.startsWith("1.") ? 2 : 0;
            int end = start;
            while (end < specificationVersion.length() && Character.isDigit(specificationVersion.charAt(end))) end++;
            if (end > start) try { major = Integer.parseInt(specificationVersion.substring(start, end)); }
            catch (NumberFormatException ignored) { major = 0; }
        }
        if (major >= 17) return null;
        return "Java 17 or newer is required; detected Java " + (specificationVersion == null ? "unknown" : specificationVersion) + ".";
    }

    private static String conflictDetail(Throwable failure) {
        if (!(failure instanceof IllegalArgumentException) || failure.getMessage() == null) return "";
        String message = failure.getMessage();
        if (message.startsWith("multiple LogChainProviders:") || message.startsWith("duplicate providerId ")) return " (" + message + ")";
        return "";
    }

    private static Path extract(String resource, Path directory) throws Exception {
        Path target = directory.resolve(resource);
        try (java.io.InputStream input = LogLensAgent.class.getResourceAsStream("/" + resource)) {
            if (input == null) throw new IllegalStateException("missing nested Agent resource: " + resource);
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
}
