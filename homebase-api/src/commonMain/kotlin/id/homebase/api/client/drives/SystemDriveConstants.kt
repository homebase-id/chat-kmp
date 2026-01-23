@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.homebasekmppoc.prototype.lib.drives

import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Built-in drives
 *
 * Ported from C# Odin.Services.Drives.SystemDriveConstants
 *
 * DO NOT CHANGE ANY VALUES
 */
object SystemDriveConstants {

    val shardRecoveryDrive =
        _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive(
            alias = Uuid.parse("46242d0d-6760-4b2a-a683-f05cd48d4aef"),
            type = Uuid.parse("43138ae9-0206-480b-9ff4-93580ca147ee")
        )

    val transientTempDrive =
        _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive(
            alias = Uuid.parse("90f5e74a-b7f9-efda-0ac2-98373a32ad8c"),
            type = Uuid.parse("90f5e74a-b7f9-efda-0ac2-98373a32ad8c")
        )

    val contactDrive =
        _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive(
            alias = Uuid.parse("2612429d-1c3f-0372-82b8-d42fb2cc0499"),
            type = Uuid.parse("70e92f0f-94d0-5f5c-7dcd-36466094f3a5")
        )

    val profileDrive =
        _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive(
            alias = Uuid.parse("8f12d8c4-9338-13d3-7848-8d91ed23b64c"),
            type = Uuid.parse("59724153-0e3e-f24b-28b9-a75ec3a5c45c")
        )

    val walletDrive =
        _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive(
            alias = Uuid.parse("a6f991e2-14b1-1c8c-9796-f664e1ec0cac"),
            type = Uuid.parse("59724153-0e3e-f24b-28b9-a75ec3a5c45c")
        )

    val chatDrive = _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive(
        alias = Uuid.parse("9ff813af-f2d6-1e2f-9b9d-b189e72d1a11"),
        type = Uuid.parse("66ea8355-ae41-55c3-9b5a-719166b510e3")
    )

    val mailDrive = _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive(
        alias = Uuid.parse("e69b5a48-a663-482f-bfd8-46f3b0b143b0"),
        type = Uuid.parse("2dfecc40-311e-41e5-a124-55e925144202")
    )

    val feedDrive = _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive(
        alias = Uuid.parse("4db49422-ebad-02e9-9ab9-6e9c477d1e08"),
        type = Uuid.parse("a3227ffb-a876-08be-eb24-fee9b70d92a6")
    )

    val homePageConfigDrive =
        _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive(
            alias = Uuid.parse("ec83345a-f6a7-47d4-404e-f8b0f8844caa"),
            type = Uuid.parse("59724153-0e3e-f24b-28b9-a75ec3a5c45c")
        )

    val publicPostChannelDrive =
        _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.drives.TargetDrive(
            alias = Uuid.parse("e8475dc4-6cb4-b665-1c2d-0dbd0f3aad5f"),
            type = Uuid.parse("8f448716-e34c-edf9-0141-45e043ca6612")
        )
}
