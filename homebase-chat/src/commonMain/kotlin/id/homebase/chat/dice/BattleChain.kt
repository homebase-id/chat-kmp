package id.homebase.chat.dice

import id.homebase.api.common.OdinId
import kotlin.uuid.Uuid

/**
 * Pure helpers for the dice-roll battle chain. The chain is encoded inside
 * each descriptor's [DiceRollDescriptor.rolls] array, so bubble rendering needs
 * nothing here.
 *
 * Used at TWO non-render points:
 *  1. Long-press menu gate — [canBattle] checks chain cap + no-rebattle by
 *     looking at the in-memory descriptors (we want the latest known state of
 *     the chain, which may be newer than the long-pressed message).
 *  2. Battle-screen open — [findChainNewest] returns the newest chain member
 *     so the sheet can lock dice config and copy its full history forward.
 */

/** The root messageId of the chain a descriptor belongs to (its first entry). */
internal fun DiceRollDescriptor.chainRootMessageId(): Uuid = rolls.first().messageId

/**
 * The newest descriptor in the same chain as [target] from [allDescriptors].
 * Same chain := same root messageId. Falls back to [target] if no chain mate
 * is in memory.
 */
internal fun findChainNewest(
    target: DiceRollDescriptor,
    allDescriptors: List<DiceRollDescriptor>,
): DiceRollDescriptor {
    val rootId = target.chainRootMessageId()
    return allDescriptors
        .filter { it.rolls.isNotEmpty() && it.chainRootMessageId() == rootId }
        .maxByOrNull { it.latest.rolledAtUtcMs }
        ?: target
}

/** All odinIds that have rolled in [target]'s chain (read from newest). */
internal fun chainParticipants(
    target: DiceRollDescriptor,
    allDescriptors: List<DiceRollDescriptor>,
): Set<OdinId> = findChainNewest(target, allDescriptors).rolls.map { it.odinId }.toSet()

/** Number of rolls already in [target]'s chain (read from newest). */
internal fun chainSize(
    target: DiceRollDescriptor,
    allDescriptors: List<DiceRollDescriptor>,
): Int = findChainNewest(target, allDescriptors).rolls.size

internal fun canBattle(
    target: DiceRollDescriptor,
    currentOdinId: OdinId?,
    allDescriptors: List<DiceRollDescriptor>,
): Boolean {
    if (currentOdinId == null) return false
    if (chainSize(target, allDescriptors) >= DiceRollDescriptor.MAX_BATTLE_PARTICIPANTS) return false
    return currentOdinId !in chainParticipants(target, allDescriptors)
}
