package id.homebase.core.diagnostics

/** What a run of on-device checks found: [failures] is empty when every check passed; [measurements] are the numbers they took. */
class CheckReport(val failures: List<String>, val measurements: List<String>)
