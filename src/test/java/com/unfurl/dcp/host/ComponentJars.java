package com.unfurl.dcp.host;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Test fixture: compiles small Java sources with the JDK compiler and packs real jars, so the pinned-artifact tests
 * exercise genuine class files, manifests and multi-release layouts rather than mocks.
 */
final class ComponentJars {
    private ComponentJars() { }

    /**
     * Compiles sources against the test classpath.
     *
     * @param workDir a scratch directory for the compiler output
     * @param sources binary class name to source text
     * @return jar entry path (for example {@code example/A.class}) to class bytes, sorted
     */
    static Map<String, byte[]> compile(Path workDir, Map<String, String> sources) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("tests need a JDK, not a JRE");
        Path out = Files.createTempDirectory(workDir, "classes");
        List<JavaFileObject> units = new ArrayList<>();
        sources.forEach((name, text) -> units.add(new SimpleJavaFileObject(
                URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return text;
            }
        }));
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        StringWriter messages = new StringWriter();
        boolean compiled = compiler.getTask(messages, null, null,
                List.of("-d", out.toString(), "-classpath", classpath, "-proc:none", "-nowarn"), null, units).call();
        if (!compiled) throw new IllegalStateException("fixture source did not compile:\n" + messages);
        Map<String, byte[]> classes = new TreeMap<>();
        try (Stream<Path> files = Files.walk(out)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                classes.put(out.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return classes;
    }

    /**
     * Writes a jar.
     *
     * @param file               the jar to create
     * @param entries            entry path to bytes, written in the given map's order
     * @param manifestAttributes extra main manifest attributes, such as {@code Multi-Release}
     */
    static Path jar(Path file, Map<String, byte[]> entries, Map<String, String> manifestAttributes) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifestAttributes.forEach((name, value) -> manifest.getMainAttributes().putValue(name, value));
        try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out, manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return file;
    }

    /** Returns the lower-case hex SHA-256 of a file, as a pin records it. */
    static String sha256(Path file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Returns a pin for a built jar under the given coordinates. */
    static ArtifactPin pin(String coordinates, Path file) {
        return new ArtifactPin(coordinates, sha256(file));
    }
}
