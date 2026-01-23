package id.homebase.homebasekmppoc.prototype.lib.drives.files

import kotlin.uuid.Uuid

object ValidationUtil {

    fun requireValidUuid(value: Uuid?, name: String): Uuid {
        val nonNull = requireNotNull(value) {
            "$name is required"
        }

        require(nonNull != Uuid.NIL) {
            "$name must not be all zeros"
        }

        return nonNull
    }

    fun requireValidUuidList(values: List<Uuid>?, name: String): List<Uuid> {
        val nonNull = requireNotNull(values) {
            "$name is required"
        }

        require(nonNull.isNotEmpty()) {
            "$name must not be empty"
        }

        require(nonNull.none { it == Uuid.NIL }) {
            "$name must not contain zero UUIDs"
        }

        return nonNull
    }
}
