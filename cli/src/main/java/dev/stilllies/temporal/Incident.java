package dev.stilllies.temporal;

/**
 * One quality incident, as started by the Dash0 check rule (or by hand with `still-lies.jar temporal start`).
 *
 * @param id             workflow ID, e.g. eval-incident-billing-2026-11-12
 * @param from           how far back to export from Dash0, e.g. now-24h
 * @param reviewReminder how long to wait before nudging the reviewer again (ISO-8601, e.g. PT48H)
 * @param verifyAfter    how long to wait after the deploy before comparing (ISO-8601, e.g. PT24H)
 * @param attempt        1 for a new incident, higher when a failed verification reopens it
 */
public record Incident(String id, String from, String reviewReminder, String verifyAfter, int attempt) {

  public Incident reopened() {
    return new Incident(id, from, reviewReminder, verifyAfter, attempt + 1);
  }
}
