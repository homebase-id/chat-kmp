package id.homebase.homebasekmppoc.lib.youauth

/**
 * Bitwise permission flags for drive access. Multiple permissions can be combined by summing their
 * values.
 */
enum class DrivePermissionType(val value: Int) {
    Read(1),
    Write(2),
    React(4),
    Comment(8);

    companion object {
        /** Parse a combined permission value into a list of permission types. */
        fun fromValue(combinedValue: Int): List<DrivePermissionType> {
            return entries.filter { (combinedValue and it.value) == it.value }
        }

        /** Combine multiple permissions into a single integer value. */
        fun combine(permissions: List<DrivePermissionType>): Int {
            return permissions.sumOf { it.value }
        }
    }
}
