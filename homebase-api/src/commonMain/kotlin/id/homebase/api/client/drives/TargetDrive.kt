package id.homebase.homebasekmppoc.prototype.lib.drives

import id.homebase.homebasekmppoc.prototype.lib.serialization.UuidSerializer
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.core.toByteArray
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * A drive specifier for incoming requests to perform actions on a drive.
 * (essentially, this hides the internal DriveId).
 *
 */

// TODO: probably remove "type" and rename alias to driveId
@Serializable
data class GlobalTransitIdFileIdentifier(

    @Serializable(with = UuidSerializer::class)
    val globalTransitId: Uuid,

    val targetDrive: id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive

)

@Serializable
data class TargetDrive(
    @Serializable(with = UuidSerializer::class)
    val alias: Uuid,
    @Serializable(with = UuidSerializer::class)
    val type: Uuid
) {

    fun toKey(): ByteArray {
        // Combine type and alias as bytes
        val typeBytes = type.toString().toByteArray(Charsets.UTF_8)
        val aliasBytes = alias.toString().toByteArray(Charsets.UTF_8)
        return typeBytes + aliasBytes
    }

    fun isValid(): Boolean {
        return alias != Uuid.NIL && type != Uuid.NIL
    }

    override fun toString(): String {
        return "Alias=$alias Type=$type"
    }

    companion object {
        fun newTargetDrive(): id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive {
            return _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive(
                alias = Uuid.random(),
                type = Uuid.random()
            )
        }

        fun newTargetDrive(type: Uuid): id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive {
            return _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive(
                alias = Uuid.random(),
                type = type
            )
        }
    }
}