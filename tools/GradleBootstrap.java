package com.crow.wrapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Lightweight project-local Gradle distribution bootstrapper. */
public final class GradleBootstrap {
    private GradleBootstrap() {}

    public static void main(String[] args) {
        try {
            Path project = projectDirectory();
            Path propertiesFile = project.resolve("gradle/wrapper/gradle-wrapper.properties");
            if (!Files.isRegularFile(propertiesFile)) {
                throw new IOException("Cannot find " + propertiesFile);
            }

            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(propertiesFile)) {
                properties.load(in);
            }
            String distributionUrl = properties.getProperty("distributionUrl");
            if (distributionUrl == null || distributionUrl.trim().isEmpty()) {
                throw new IOException("distributionUrl is missing from gradle-wrapper.properties");
            }

            String fileName = distributionUrl.substring(distributionUrl.lastIndexOf('/') + 1);
            if (!fileName.endsWith(".zip")) {
                throw new IOException("Unsupported Gradle distribution URL: " + distributionUrl);
            }
            String distributionName = fileName.substring(0, fileName.length() - 4);
            String folderName = distributionName.replaceFirst("-(bin|all)$", "");

            String gradleUserHome = System.getenv("GRADLE_USER_HOME");
            Path userHome = gradleUserHome == null || gradleUserHome.trim().isEmpty()
                    ? Paths.get(System.getProperty("user.home"), ".gradle")
                    : Paths.get(gradleUserHome);
            Path cache = userHome.resolve("wrapper/dists").resolve(distributionName).resolve("crow-bootstrap");
            Path install = cache.resolve(folderName);
            Path launcher = install.resolve(isWindows() ? "bin/gradle.bat" : "bin/gradle");

            if (!Files.isRegularFile(launcher)) {
                Files.createDirectories(cache);
                Path archive = cache.resolve(fileName + ".part");
                System.out.println("Downloading Gradle distribution: " + distributionUrl);
                download(distributionUrl, archive);
                unzipSafely(archive, cache);
                Files.deleteIfExists(archive);
            }
            if (!Files.isRegularFile(launcher)) {
                throw new IOException("Gradle launcher was not found after extraction: " + launcher);
            }
            if (!isWindows()) {
                launcher.toFile().setExecutable(true, false);
            }

            List<String> command = new ArrayList<String>();
            if (isWindows()) {
                command.add("cmd.exe");
                command.add("/d");
                command.add("/c");
                StringBuilder cmd = new StringBuilder(quote(launcher.toAbsolutePath().toString()));
                for (String arg : args) cmd.append(' ').append(quote(arg));
                command.add(cmd.toString());
            } else {
                command.add(launcher.toAbsolutePath().toString());
                for (String arg : args) command.add(arg);
            }

            ProcessBuilder process = new ProcessBuilder(command);
            process.directory(project.toFile());
            process.inheritIO();
            int exitCode = process.start().waitFor();
            System.exit(exitCode);
        } catch (Exception error) {
            System.err.println("Gradle bootstrap failed: " + error.getMessage());
            System.err.println("Check your internet connection and JDK installation, then retry.");
            System.exit(1);
        }
    }

    private static Path projectDirectory() {
        String configured = System.getenv("CROW_WRAPPER_PROJECT_DIR");
        Path candidate = configured == null || configured.trim().isEmpty()
                ? Paths.get(System.getProperty("user.dir"))
                : Paths.get(configured);
        candidate = candidate.toAbsolutePath().normalize();
        if (Files.isRegularFile(candidate.resolve("gradle/wrapper/gradle-wrapper.properties"))) {
            return candidate;
        }
        Path parent = candidate.getParent();
        if (parent != null && Files.isRegularFile(parent.resolve("gradle/wrapper/gradle-wrapper.properties"))) {
            return parent;
        }
        return candidate;
    }

    private static void download(String address, Path output) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", "Crow-Gradle-Bootstrap/1.0");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Gradle download returned HTTP " + status);
            }
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(output))) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void unzipSafely(Path archive, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root)) throw new IOException("Unsafe path in Gradle archive");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    try (BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) out.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String quote(String value) {
        if (value.indexOf(' ') < 0 && value.indexOf('\t') < 0 && value.indexOf('"') < 0) return value;
        return "\"" + value.replace("\"", "\\\"") + "\"";
    }
}
