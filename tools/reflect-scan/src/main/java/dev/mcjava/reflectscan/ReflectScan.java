package dev.mcjava.reflectscan;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Command line entry point.
 *
 * <pre>
 *   java -jar reflect-scan.jar --jars &lt;jar-or-dir&gt; [--jars ...] --out &lt;dir&gt;
 *        [--min-package-hits N] [--quiet]
 * </pre>
 *
 * Outputs (inside --out):
 *   force-link-classes.txt   one RoboVM/MobiVM forceLinkClasses pattern per line
 *   reflection-report.json   machine readable report
 *   reflection-report.md     human readable summary
 */
public final class ReflectScan {

    private ReflectScan() {
    }

    public static void main(String[] args) throws Exception {
        List<Path> inputs = new ArrayList<>();
        Path out = Paths.get("reflect-out");
        int minPackageHits = 3;
        boolean quiet = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--jars":
                    if (i + 1 >= args.length) usage("--jars needs a value");
                    inputs.add(Paths.get(args[++i]));
                    break;
                case "--out":
                    if (i + 1 >= args.length) usage("--out needs a value");
                    out = Paths.get(args[++i]);
                    break;
                case "--min-package-hits":
                    if (i + 1 >= args.length) usage("--min-package-hits needs a value");
                    minPackageHits = Integer.parseInt(args[++i]);
                    break;
                case "--quiet":
                    quiet = true;
                    break;
                case "-h":
                case "--help":
                    usage(null);
                    return;
                default:
                    usage("unknown argument: " + args[i]);
            }
        }

        if (inputs.isEmpty()) usage("at least one --jars is required");

        List<Path> jars = expand(inputs);
        if (jars.isEmpty()) usage("no .jar files found in the given inputs");

        if (!quiet) {
            System.out.println("[reflect-scan] scanning " + jars.size() + " jar(s)");
        }
        long t0 = System.currentTimeMillis();
        ReflectionScan.Result result = new ReflectionScan(jars, minPackageHits).scan();
        Files.createDirectories(out);
        ReportWriter.write(out, result, jars.size());
        long ms = System.currentTimeMillis() - t0;

        if (!quiet) {
            System.out.println("[reflect-scan] classes=" + result.totalClasses
                    + " hits=" + result.hits.size()
                    + " exact=" + result.exactClasses.size()
                    + " patterns=" + result.patterns.size()
                    + " services=" + result.services.size()
                    + " unresolved=" + result.unresolved.size()
                    + " in " + ms + "ms");
            System.out.println("[reflect-scan] wrote " + out.toAbsolutePath());
        }
    }

    private static List<Path> expand(List<Path> inputs) throws IOException {
        List<Path> jars = new ArrayList<>();
        for (Path in : inputs) {
            if (Files.isDirectory(in)) {
                try (Stream<Path> walk = Files.walk(in)) {
                    walk.filter(p -> Files.isRegularFile(p)
                                    && p.getFileName().toString().toLowerCase().endsWith(".jar"))
                            .sorted()
                            .forEach(jars::add);
                }
            } else if (Files.isRegularFile(in)) {
                jars.add(in);
            }
        }
        return jars;
    }

    private static void usage(String error) {
        if (error != null) {
            System.err.println("error: " + error);
        }
        System.err.println("usage: reflect-scan --jars <jar-or-dir> [--jars ...] --out <dir> "
                + "[--min-package-hits N] [--quiet]");
        if (error != null) {
            System.exit(2);
        }
    }
}
