package com.aegis.fdx.engine;

import java.util.ArrayList;
import java.util.List;

/** F-29 per-case settings. Defaults follow the decision table in the brief. */
public final class CaseSettings {

    public enum DedupeScope { OFF, PER_CUSTODIAN, GLOBAL }

    private boolean ocrEnabled = true;              // enabled by default
    private List<String> ocrLanguages = new ArrayList<>(List.of("eng"));
    private DedupeScope dedupeScope = DedupeScope.OFF;   // highlight only
    private int maxArchiveDepth = 20;               // F-03
    private final List<String> passwords = new ArrayList<>();  // F-04
    private int indexMemoryMb = 4096;               // N-04
    private int workers = Math.max(1, Runtime.getRuntime().availableProcessors() - 1); // A-03
    private boolean encryptCaseFolder = true;       // N-06 AES-256
    private long streamThresholdMb = 100;           // N-04

    public boolean ocrEnabled() { return ocrEnabled; }
    public void ocrEnabled(boolean v) { ocrEnabled = v; }
    public List<String> ocrLanguages() { return ocrLanguages; }
    public DedupeScope dedupeScope() { return dedupeScope; }
    public void dedupeScope(DedupeScope v) { dedupeScope = v; }
    public int maxArchiveDepth() { return maxArchiveDepth; }
    public void maxArchiveDepth(int v) { maxArchiveDepth = v; }
    public List<String> passwords() { return passwords; }
    public int indexMemoryMb() { return indexMemoryMb; }
    public void indexMemoryMb(int v) { indexMemoryMb = v; }
    public int workers() { return workers; }
    public void workers(int v) { workers = v; }
    public boolean encryptCaseFolder() { return encryptCaseFolder; }
    public void encryptCaseFolder(boolean v) { encryptCaseFolder = v; }
    public long streamThresholdMb() { return streamThresholdMb; }
}
