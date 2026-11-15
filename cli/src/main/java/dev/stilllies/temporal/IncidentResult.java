package dev.stilllies.temporal;

/** How an incident workflow ended. */
public record IncidentResult(String incidentId, String status, String pullRequest, Comparison comparison) {}
