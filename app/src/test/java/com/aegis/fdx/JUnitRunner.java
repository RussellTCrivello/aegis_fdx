package com.aegis.fdx;

import org.junit.platform.engine.discovery.ClassNameFilter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the JUnit suites without Gradle.
 *
 * <p>Why this exists: the offline battery (`run-tests.sh`) could previously run only the
 * suites with a {@code main} method, which meant the JUnit half of the test base — the
 * facade, agent, batch, destination and scenario suites — could be executed only by a
 * Gradle build that needs to resolve dependencies from the network. On a locked-down
 * or air-gapped machine that half silently never ran. The JUnit Platform launcher is
 * already in {@code lib/}, so the same jars that compile the project can also run its
 * tests.
 *
 * <p>With no arguments it discovers everything under {@code com.aegis.fdx} whose class
 * name ends in {@code Test}. Class names may be passed instead to run a subset, which
 * is how the battery keeps the interface suites — which need a JavaFX runtime with
 * native libraries — separate from the headless ones.
 */
public final class JUnitRunner {

    public static void main(String[] args) {
        LauncherDiscoveryRequestBuilder b = LauncherDiscoveryRequestBuilder.request();
        if (args.length == 0) {
            b.selectors(DiscoverySelectors.selectPackage("com.aegis.fdx"))
             .filters(ClassNameFilter.includeClassNamePatterns(".*Test"));
        } else {
            List<org.junit.platform.engine.DiscoverySelector> sel = new ArrayList<>();
            for (String a : args) {
                sel.add(DiscoverySelectors.selectClass(a));
            }
            b.selectors(sel);
        }
        LauncherDiscoveryRequest request = b.build();

        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(request, listener);

        TestExecutionSummary summary = listener.getSummary();
        PrintWriter out = new PrintWriter(System.out, true);

        // A failure caused by the absence of a graphics device is a statement about the
        // machine, not about the product. Those are reported separately and loudly, and
        // they do not fail the run; anything else does.
        long environmental = 0;
        for (TestExecutionSummary.Failure f : summary.getFailures()) {
            if (isEnvironmental(f.getException())) {
                environmental++;
                System.out.println("  ENV    " + f.getTestIdentifier().getDisplayName()
                        + " — needs a JavaFX runtime with a graphics device; not run here");
            }
        }
        long real = summary.getTestsFailedCount() - environmental;

        if (real > 0) {
            summary.printFailuresTo(out, 12);
        }

        System.out.printf("=== %d tests, %d passed, %d failed, %d skipped"
                        + (environmental > 0 ? ", %d not runnable on this machine" : "%.0s")
                        + " (%.1f s) ===%n",
                summary.getTestsFoundCount(),
                summary.getTestsSucceededCount(),
                real,
                summary.getTestsSkippedCount(),
                environmental,
                (summary.getTimeFinished() - summary.getTimeStarted()) / 1000.0);

        if (real > 0) {
            System.exit(1);
        }
    }

    /** True when a failure was caused by there being no display, not by the code. */
    private static boolean isEnvironmental(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            String message = c.getMessage() == null ? "" : c.getMessage();
            if (message.contains("No toolkit found")
                    || message.contains("Error initializing QuantumRenderer")
                    || message.contains("Graphics Device initialization failed")
                    || message.contains("Unable to open DISPLAY")
                    || (c instanceof UnsatisfiedLinkError
                            && (message.contains("glass") || message.contains("prism")
                                    || message.contains("javafx")))) {
                return true;
            }
        }
        return false;
    }

    private JUnitRunner() {
    }
}
