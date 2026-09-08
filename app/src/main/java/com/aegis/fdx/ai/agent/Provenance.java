package com.aegis.fdx.ai.agent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a statement in an agent answer comes from.
 *
 * <p>Every line of a final answer carries exactly one of these labels so an operator can
 * tell a fact the tools returned from a conclusion the model drew from it, and both from
 * a guess. The model is asked to label its own statements; whatever it leaves unlabelled
 * is labelled here by rule — never promoted, only demoted:
 *
 * <ul>
 *   <li>a line that quotes an identifier a tool actually returned keeps {@code OBSERVED}
 *       if the model said so, otherwise it becomes {@code DERIVED};</li>
 *   <li>an unlabelled line in a run where tools returned data becomes {@code INFERRED};</li>
 *   <li>an unlabelled line in a run where nothing was retrieved becomes {@code UNKNOWN};</li>
 *   <li>a line the model marks {@code OBSERVED} that cites no evidence identifier the tools
 *       returned is demoted to {@code INFERRED} — the model does not get to declare
 *       observations the case did not supply.</li>
 * </ul>
 *
 * The labels are a controlled vocabulary, not translated text, and are stable across
 * languages (see {@code docs/LOCALIZATION_PREPARATION.md}).
 */
public enum Provenance {
    /** Read directly from a case record a tool returned. */
    OBSERVED,
    /** Computed or aggregated from observed records (a count, a comparison). */
    DERIVED,
    /** The model's conclusion, plausible but not read from a record. */
    INFERRED,
    /** Supplied by the operator in the question or the screen context. */
    USER_PROVIDED("USER-PROVIDED"),
    /** Not supported by anything the tools returned. */
    UNKNOWN;

    private final String label;

    Provenance() {
        this.label = name();
    }

    Provenance(String label) {
        this.label = label;
    }

    /** The bracketed form used in answers, e.g. {@code [OBSERVED]}. */
    public String tag() {
        return "[" + label + "]";
    }

    public String label() {
        return label;
    }

    /** Parses a leading tag such as {@code [DERIVED]} or {@code OBSERVED:}; null if none. */
    public static Provenance leading(String line) {
        String s = line.strip().toUpperCase(Locale.ROOT);
        for (Provenance p : values()) {
            if (s.startsWith(p.tag()) || s.startsWith(p.label + ":") || s.startsWith(p.label + " -")) {
                return p;
            }
        }
        return null;
    }

    /** Strips a leading tag from a line. */
    public static String strip(String line) {
        String s = line.strip();
        Provenance p = leading(s);
        if (p == null) {
            return s;
        }
        String upper = s.toUpperCase(Locale.ROOT);
        int cut;
        if (upper.startsWith(p.tag())) {
            cut = p.tag().length();
        } else if (upper.startsWith(p.label + ":")) {
            cut = p.label.length() + 1;
        } else {
            cut = p.label.length() + 2;
        }
        return s.substring(cut).strip();
    }

    /** The result of labelling one answer. */
    public record Labelled(String text, Map<Provenance, Integer> counts) {
        public int count(Provenance p) {
            return counts.getOrDefault(p, 0);
        }
    }

    /**
     * Labels every non-empty line of {@code answer}.
     *
     * @param answer        the model's prose
     * @param evidenceIds   identifiers the tools returned (e.g. {@code E-000001}, {@code 3});
     *                      a line quoting one may be OBSERVED
     * @param anythingRead  whether any tool returned data in this run
     * @param userTerms     words from the operator's question/context, for USER-PROVIDED
     */
    public static Labelled label(String answer, List<String> evidenceIds, boolean anythingRead,
                                 List<String> userTerms) {
        Map<Provenance, Integer> counts = new EnumMap<>(Provenance.class);
        List<String> out = new ArrayList<>();
        for (String raw : answer.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                out.add("");
                continue;
            }
            Provenance claimed = leading(line);
            String body = strip(line);
            boolean citesEvidence = cites(body, evidenceIds);
            Provenance p;
            if (claimed == OBSERVED) {
                p = citesEvidence ? OBSERVED : (anythingRead ? INFERRED : UNKNOWN);
            } else if (claimed == DERIVED) {
                p = anythingRead ? DERIVED : UNKNOWN;
            } else if (claimed == USER_PROVIDED || claimed == INFERRED || claimed == UNKNOWN) {
                p = claimed;
            } else if (citesEvidence) {
                p = DERIVED;
            } else if (!anythingRead) {
                p = UNKNOWN;
            } else {
                p = INFERRED;
            }
            counts.merge(p, 1, Integer::sum);
            out.add(p.tag() + " " + body);
        }
        return new Labelled(String.join("\n", out), counts);
    }

    private static boolean cites(String line, List<String> evidenceIds) {
        if (evidenceIds == null) {
            return false;
        }
        for (String id : evidenceIds) {
            if (id != null && id.length() >= 3 && line.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /** One-line summary for the trace: {@code OBSERVED 2 · DERIVED 1 · INFERRED 1}. */
    public static String summary(Map<Provenance, Integer> counts) {
        List<String> parts = new ArrayList<>();
        for (Provenance p : values()) {
            int n = counts.getOrDefault(p, 0);
            if (n > 0) {
                parts.add(p.label + " " + n);
            }
        }
        return parts.isEmpty() ? "no statements" : String.join(" · ", parts);
    }
}
