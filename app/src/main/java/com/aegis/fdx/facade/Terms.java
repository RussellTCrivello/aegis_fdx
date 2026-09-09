package com.aegis.fdx.facade;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a keyword is, what a category is, and how either is compared.
 *
 * <p>These two words are used loosely almost everywhere, and loose vocabulary is how a
 * relationship model rots: a "keyword" that is really one word cannot be told apart from
 * a category, and a "category" that is really a phrase cannot be a single point in a
 * navigable graph. So the definitions are code, enforced at the facade boundary, rather
 * than a paragraph in a document that the next change ignores.
 *
 * <dl>
 *   <dt><strong>Keyword</strong></dt>
 *   <dd>A phrase of <strong>two or more words</strong>. {@code financial transaction
 *       report} and {@code financial transaction} are both keywords.</dd>
 *   <dt><strong>Category</strong></dt>
 *   <dd><strong>Exactly one word</strong>. {@code finance} is a category; {@code
 *       financial records} is not.</dd>
 *   <dt><strong>Category word</strong></dt>
 *   <dd>A single word attached to a category — the vocabulary that category is made of.
 *       A category word is a {@code word} row linked by {@code word_category}; the
 *       category's own name is itself a word row.</dd>
 * </dl>
 *
 * <p><strong>Agreement with the reference, stated plainly.</strong> The reference
 * project classifies a term by splitting it: one word becomes a category word, and
 * <em>two or more</em> becomes a keyword ({@code database/operations.py::process_term}).
 * This application follows the same rule, enforced at the facade boundary: a two-word
 * phrase is a keyword, not a rejection, and the interface accepts it as one.
 *
 * <p><strong>Normalisation.</strong> Comparison is case-insensitive, accent-insensitive
 * and whitespace-insensitive; the form the operator typed is what gets displayed.
 * {@code Financial Transaction Report}, {@code financial transaction report} and
 * {@code FINANCIAL  TRANSACTION  REPORT} are the same keyword and are stored once, under
 * the spelling first used.
 */
public final class Terms {

    /** A keyword must have at least this many words. */
    public static final int MIN_KEYWORD_WORDS = 2;

    /** A category is exactly this many words. */
    public static final int CATEGORY_WORDS = 1;

    private Terms() {
    }

    /**
     * The comparison form of a term: lower case, accents folded, punctuation that only
     * separates words turned into spaces, and runs of whitespace collapsed.
     *
     * <p>Deliberately not stemming or removing plurals: "records" and "record" are
     * different terms to an examiner, and quietly merging them would change what a file
     * count means.
     */
    public static String normalize(String term) {
        if (term == null) {
            return "";
        }
        String folded = Normalizer.normalize(term, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        StringBuilder sb = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            } else if (c == '\'' || c == '\u2019' || c == '-') {
                // Apostrophes and hyphens sit inside a word: "director's report" is two
                // words and "no-such-category" is one. Only whitespace and other
                // punctuation separate words.
                sb.append(c == '-' ? '-' : '\'');
            } else {
                sb.append(' ');
            }
        }
        // A hyphen or apostrophe that ended up at the edge of a word was punctuation,
        // not part of it.
        return sb.toString()
                .replaceAll("(?<=\\s|^)['-]+", "")
                .replaceAll("['-]+(?=\\s|$)", "")
                .trim()
                .replaceAll("\\s+", " ");
    }

    /** The words of a term, after normalisation. */
    public static List<String> words(String term) {
        String n = normalize(term);
        if (n.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String w : n.split(" ")) {
            if (!w.isBlank()) {
                out.add(w);
            }
        }
        return out;
    }

    public static int wordCount(String term) {
        return words(term).size();
    }

    /** True when {@code term} is a keyword: a phrase of two or more words. */
    public static boolean isKeyword(String term) {
        return wordCount(term) >= MIN_KEYWORD_WORDS;
    }

    /** True when {@code term} is a category: exactly one word. */
    public static boolean isCategory(String term) {
        return wordCount(term) == CATEGORY_WORDS;
    }

    /** True when {@code term} is a single word, and so can be a category word. */
    public static boolean isSingleWord(String term) {
        return wordCount(term) == 1;
    }

    /**
     * Checks a keyword and returns its display form, or refuses with a message an
     * operator can act on.
     */
    public static String requireKeyword(String phrase, String field) {
        String display = Validate.required(phrase, field).trim().replaceAll("\\s+", " ");
        int n = wordCount(display);
        if (n < MIN_KEYWORD_WORDS) {
            throw FacadeException.validation(field + " must be a phrase of at least "
                    + MIN_KEYWORD_WORDS + " words — \"" + display + "\" has "
                    + n + (n == 1 ? " word" : " words")
                    + ". A single word is a category word; a phrase of two or more is a keyword.");
        }
        return display;
    }

    /**
     * Checks a category and returns its display form, or refuses with a message an
     * operator can act on.
     */
    public static String requireCategory(String word, String field) {
        String display = Validate.required(word, field).trim().replaceAll("\\s+", " ");
        int n = wordCount(display);
        if (n != CATEGORY_WORDS) {
            throw FacadeException.validation(field + " must be a single word — \""
                    + display + "\" has " + n + " words."
                    + " A phrase of two or more words is a keyword, not a category.");
        }
        return display;
    }

    /** Checks a single word (a category word or a vocabulary entry). */
    public static String requireSingleWord(String word, String field) {
        String display = Validate.required(word, field).trim().replaceAll("\\s+", " ");
        int n = wordCount(display);
        if (n != 1) {
            throw FacadeException.validation(field + " must be a single word — \""
                    + display + "\" has " + n + " words.");
        }
        return display;
    }

    /**
     * How a term entered in one box should be classified, the way the reference's
     * {@code process_term} does.
     *
     * @return {@link Kind#CATEGORY_WORD} for one word, {@link Kind#KEYWORD} for two or
     *         more, and {@link Kind#REJECTED} for input with no words at all, which is
     *         neither
     */
    public static Kind classify(String term) {
        int n = wordCount(term);
        if (n == 1) {
            return Kind.CATEGORY_WORD;
        }
        if (n >= MIN_KEYWORD_WORDS) {
            return Kind.KEYWORD;
        }
        return Kind.REJECTED;
    }

    /** What a typed term turned out to be. */
    public enum Kind {
        CATEGORY_WORD,
        KEYWORD,
        REJECTED;

        public String explain(String term) {
            int n = wordCount(term);
            return switch (this) {
                case CATEGORY_WORD -> "\"" + term + "\" is a single word: it will be added as a category word.";
                case KEYWORD -> "\"" + term + "\" is a phrase of " + n + " words: it will be added as a keyword.";
                case REJECTED -> "\"" + term + "\" has no words. A category word is one word and a "
                        + "keyword is two or more, so empty text cannot be stored as either.";
            };
        }
    }

    /** Locale-independent lower case, for display-form comparison. */
    public static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
