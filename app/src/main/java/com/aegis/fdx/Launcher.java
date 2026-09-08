package com.aegis.fdx;

/**
 * Release launcher.
 *
 * <p>When JavaFX is on the <em>classpath</em> rather than the module path, the JVM
 * refuses to start a main class that extends {@code javafx.application.Application}
 * with "JavaFX runtime components are missing". Routing through a plain class that
 * does not extend {@code Application} sidesteps that check, which is the supported
 * way to ship a jpackage image with classpath JavaFX.
 *
 * <p>This is the entry point used by the installers.
 *
 * <p>The application ships two front ends over the same engine:
 * <ul>
 *   <li>{@code com.aegis.fdx.ui.FasApp} — the File Analysis System interface
 *       (default), aligned to the reference interface contract;</li>
 *   <li>{@code com.aegis.fdx.ui.AegisApp} — the original forensic review interface,
 *       unchanged and still fully supported.</li>
 * </ul>
 *
 * <p>Select the forensic interface with {@code --forensic} or
 * {@code -Daegis.ui=forensic}. Neither UI is removed and both operate on the same
 * case data.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        if (wantsForensicUi(args)) {
            com.aegis.fdx.ui.AegisApp.main(stripUiFlags(args));
        } else {
            com.aegis.fdx.ui.FasApp.main(stripUiFlags(args));
        }
    }

    private static boolean wantsForensicUi(String[] args) {
        if ("forensic".equalsIgnoreCase(System.getProperty("aegis.ui", ""))) {
            return true;
        }
        for (String a : args) {
            if ("--forensic".equalsIgnoreCase(a) || "--aegis".equalsIgnoreCase(a)) {
                return true;
            }
        }
        return false;
    }

    /** Keeps JavaFX from seeing our own switches as application parameters. */
    private static String[] stripUiFlags(String[] args) {
        return java.util.Arrays.stream(args)
                .filter(a -> !"--forensic".equalsIgnoreCase(a) && !"--aegis".equalsIgnoreCase(a))
                .toArray(String[]::new);
    }
}
