package id.homebase.api.client.drives

/**
 * Three-valued filter on the `DriveMainIndex.fileState` column for
 * `QueryBatch` queries. Distinct from [FileState] (which is the file's
 * actual state on each row) — this is what the query *asks for*.
 *
 * Default for chat / contacts / vault reads is [Active]; the [All]
 * variant is the explicit opt-out used by audit / diagnostic paths
 * that need soft-deleted rows in the result.
 */
enum class FileStateFilter {
    Active,
    Deleted,
    All,
}
