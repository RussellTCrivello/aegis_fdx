package com.aegis.fdx.facade;

import java.time.LocalDate;

/**
 * The details needed to create a source.
 *
 * <p>Only name, country, job and importance are required; the rest describe provenance
 * and may be left unset. Using a draft keeps {@link SourceFacade#createSource} to one
 * argument instead of thirteen positional ones.
 *
 * <pre>{@code
 * int id = sources.createSource(new SourceDraft("Acme", "NL", "custodian", 0.8)
 *         .city("Amsterdam")
 *         .accessStatus("Full access"));
 * }</pre>
 */
public final class SourceDraft {

    private final String name;
    private final String country;
    private final String job;
    private final double importance;

    private String city = "";
    private String description = "";
    private String accounts = "";
    private String note = "";
    private String attachments = "";
    private String ownership = "";
    private String accessStatus = "";
    private LocalDate entryDate;
    private Integer categoryId;

    public SourceDraft(String name, String country, String job, double importance) {
        this.name = name;
        this.country = country;
        this.job = job;
        this.importance = importance;
    }

    public SourceDraft city(String v) { this.city = v; return this; }

    public SourceDraft description(String v) { this.description = v; return this; }

    public SourceDraft accounts(String v) { this.accounts = v; return this; }

    public SourceDraft note(String v) { this.note = v; return this; }

    public SourceDraft attachments(String v) { this.attachments = v; return this; }

    public SourceDraft ownership(String v) { this.ownership = v; return this; }

    public SourceDraft accessStatus(String v) { this.accessStatus = v; return this; }

    /** Defaults to today when left unset. */
    public SourceDraft entryDate(LocalDate v) { this.entryDate = v; return this; }

    /** Optional category attribution. */
    public SourceDraft category(Integer v) { this.categoryId = v; return this; }

    public String name() { return name; }

    public String country() { return country; }

    public String job() { return job; }

    public double importance() { return importance; }

    public String city() { return city; }

    public String description() { return description; }

    public String accounts() { return accounts; }

    public String note() { return note; }

    public String attachments() { return attachments; }

    public String ownership() { return ownership; }

    public String accessStatus() { return accessStatus; }

    public LocalDate entryDate() { return entryDate; }

    public Integer categoryId() { return categoryId; }
}
