package id.homebase.chat.dice

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import id.homebase.api.common.OdinId
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.services.content.MessageContentParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Reproduction tests for the "Ancient roll" bug seen in `homebase2.log`:
 *
 *   Warn: (MessageContentParser) DiceRoll descriptor failed validation; faces=4 chainSize=1
 *
 * The user sent a 1d4 from the dice composer; their own client logged this
 * warning seven times (optimistic write, WS push echo, ChatMessageStream
 * incremental batches) and the bubble rendered as "Ancient roll" (the
 * `chat_dice_unparseable` string). Existing tests cover faces=6/12/20, never 4.
 */
class Dice1d4RoundTripTest {

    @Test
    fun freshly_built_1d4_standard_is_valid() {
        val descriptor = build1d4(odinId = "alice.test", result = 3)
        assertTrue(descriptor.isValid(), "freshly built 1d4 standard must be valid")
    }

    @Test
    fun roundtrip_1d4_standard_validates() {
        val descriptor = build1d4(odinId = "alice.test", result = 3)
        val json = MessageContentParser.serialize(MessageContent.DiceRoll(descriptor))
        println("1d4 standard wire JSON: $json")

        val parsed = MessageContentParser.parse(
            ChatProtocol.ChatDiceRollMessageDataType,
            json,
        )
        val diceRoll = assertIs<MessageContent.DiceRoll>(parsed)
        val roundTripped = assertNotNull(
            diceRoll.descriptor,
            "round-tripped 1d4 descriptor must not be null (would render as Ancient roll)",
        )
        assertTrue(
            roundTripped.isValid(),
            "round-tripped 1d4 descriptor must validate (mode=${roundTripped.mode} faces=${roundTripped.faces})",
        )
        assertEquals(descriptor, roundTripped, "round-trip must preserve all fields")
    }

    @Test
    fun roundtrip_each_allowed_face_validates() {
        for (faces in DiceRollDescriptor.ALLOWED_FACES) {
            val descriptor = DiceRollDescriptor(
                faces = faces,
                mode = DiceRollMode.Standard,
                rolls = listOf(
                    ChainRoll(
                        messageId = Uuid.random(),
                        odinId = OdinId("alice.test"),
                        results = listOf(faces),
                        rolledAtUtcMs = 1_700_000_000_000L,
                        source = RollSource.LocalRandom,
                    ),
                ),
            )
            assertTrue(descriptor.isValid(), "freshly built 1d$faces must be valid")
            val json = MessageContentParser.serialize(MessageContent.DiceRoll(descriptor))
            val parsed = MessageContentParser.parse(
                ChatProtocol.ChatDiceRollMessageDataType,
                json,
            ) as MessageContent.DiceRoll
            assertNotNull(parsed.descriptor, "1d$faces round-trip must not produce null descriptor — wire JSON: $json")
            assertTrue(parsed.descriptor!!.isValid(), "1d$faces round-tripped must validate — wire JSON: $json")
        }
    }

    @Test
    fun roundtrip_1d4_with_user_odin_id_and_shake_seeded_validates() {
        val descriptor = DiceRollDescriptor(
            faces = 4,
            mode = DiceRollMode.Standard,
            rolls = listOf(
                ChainRoll(
                    messageId = Uuid.parse("7a8268c1-79bf-4e04-9158-4804d86335af"),
                    odinId = OdinId("michael.seifert.page"),
                    results = listOf(3),
                    rolledAtUtcMs = 1_778_765_750_409L,
                    source = RollSource.ShakeSeeded,
                ),
            ),
        )
        assertTrue(descriptor.isValid())

        val json = MessageContentParser.serialize(MessageContent.DiceRoll(descriptor))
        println("1d4 (production-shape) wire JSON: $json")

        val parsed = MessageContentParser.parse(
            ChatProtocol.ChatDiceRollMessageDataType,
            json,
        ) as MessageContent.DiceRoll
        val roundTripped = assertNotNull(parsed.descriptor)
        assertTrue(
            roundTripped.isValid(),
            "production-shape 1d4 must round-trip valid — wire JSON: $json",
        )
        assertEquals(RollSource.ShakeSeeded, roundTripped.rolls.first().source)
    }

    @Test
    fun roundtrip_oe_then_standard_preserves_mode_field() {
        val oe = DiceRollDescriptor(
            faces = 10,
            mode = DiceRollMode.OpenEndedD100,
            rolls = listOf(
                ChainRoll(
                    messageId = Uuid.random(),
                    odinId = OdinId("alice.test"),
                    results = listOf(7, 7),
                    rolledAtUtcMs = 1_700_000_000_000L,
                ),
            ),
        )
        val oeJson = MessageContentParser.serialize(MessageContent.DiceRoll(oe))
        println("OE wire JSON: $oeJson")
        val oeParsed =
            (MessageContentParser.parse(ChatProtocol.ChatDiceRollMessageDataType, oeJson) as MessageContent.DiceRoll)
                .descriptor
        assertNotNull(oeParsed)
        assertEquals(DiceRollMode.OpenEndedD100, oeParsed!!.mode, "OE mode must survive round-trip — JSON: $oeJson")

        val standard = build1d4(odinId = "alice.test", result = 3)
        val stdJson = MessageContentParser.serialize(MessageContent.DiceRoll(standard))
        println("Standard wire JSON: $stdJson")
        val stdParsed =
            (MessageContentParser.parse(ChatProtocol.ChatDiceRollMessageDataType, stdJson) as MessageContent.DiceRoll)
                .descriptor
        assertNotNull(stdParsed)
        assertEquals(DiceRollMode.Standard, stdParsed!!.mode, "Standard mode must survive round-trip — JSON: $stdJson")
    }

    @Test
    fun descriptor_with_oe_mode_and_faces_4_is_explicitly_invalid() {
        val descriptor = DiceRollDescriptor(
            faces = 4,
            mode = DiceRollMode.OpenEndedD100,
            rolls = listOf(
                ChainRoll(
                    messageId = Uuid.random(),
                    odinId = OdinId("alice.test"),
                    results = listOf(3, 3),
                    rolledAtUtcMs = 1_700_000_000_000L,
                ),
            ),
        )
        assertFalse(
            descriptor.isValid(),
            "OE mode requires faces=10 — a faces=4 OE descriptor would be the production failure shape",
        )
    }

    /**
     * Root-cause regression for the "Ancient roll" bug seen in `homebase2.log`.
     *
     * Build 1.3.1420's composer had:
     *   var mode by remember { mutableStateOf(initialMode) }          // state
     *   val faces = if (mode == OE) 10 else standardFaces             // plain val
     *
     * `mode` is a state delegate (read inside a lambda re-reads from the
     * snapshot). `faces` is a plain `val` (captured at the composition
     * where the lambda was created). A long-running lambda — the shake
     * handler's `LaunchedEffect(shakeDetector.isAvailable) { collect {
     * doRoll() } }` — held the FIRST composition's `doRoll`. When the user
     * toggled OE on, `mode` flipped in state but the captured `doRoll`
     * still owned `faces = 4`. The next shake invoked that stale `doRoll`,
     * which read `mode = OE` (fresh) and used `faces = 4` (stale), producing
     * a `DiceRollDescriptor(faces=4, mode=OpenEndedD100, …)` — invalid by
     * the OE branch's `if (faces != 10) return false`, rendered as
     * "Ancient roll".
     *
     * The previous-task fix (`rememberUpdatedState(doRoll)` in
     * `DiceRollComposerSheet.kt` and `BattleRollSheet.kt`) makes the shake
     * handler always invoke the LATEST `doRoll`, whose `faces` was captured
     * in the SAME composition as the current `mode` — so they can't
     * diverge.
     *
     * This test exercises both sides of the cure: a stale-captured closure
     * (the pre-fix shape) produces an invalid descriptor; a fresh-captured
     * closure (the post-fix shape) produces a valid one.
     */
    @Test
    fun staleClosure_buildsInvalidDescriptor_freshClosure_buildsValid() {
        val mode = mutableStateOf(DiceRollMode.Standard)
        val standardFaces = mutableIntStateOf(4)
        val standardCount = mutableIntStateOf(1)

        // ---- Composition 1: mode = Standard ----
        // The composer evaluates `faces` and `count` as plain `val`s here.
        // A lambda captured now closes over these values.
        val facesAtComposition1 =
            if (mode.value == DiceRollMode.OpenEndedD100) 10 else standardFaces.intValue
        val countAtComposition1 =
            if (mode.value == DiceRollMode.OpenEndedD100) 2 else standardCount.intValue
        val staleDoRoll: () -> DiceRollDescriptor = {
            val finalResults = if (mode.value == DiceRollMode.OpenEndedD100) {
                listOf(5, 7) // stand-in for rollOpenEndedD100
            } else {
                List(countAtComposition1) { 3 } // stand-in for roll(count, faces)
            }
            DiceRollDescriptor(
                faces = facesAtComposition1,
                mode = mode.value,
                rolls = listOf(
                    ChainRoll(
                        messageId = Uuid.random(),
                        odinId = OdinId("alice.test"),
                        results = finalResults,
                        rolledAtUtcMs = 1_700_000_000_000L,
                    ),
                ),
            )
        }

        // ---- User toggles OE on ----
        mode.value = DiceRollMode.OpenEndedD100

        // ---- Composition 2: mode = OE ----
        // The composer re-evaluates `faces`/`count` for the new mode.
        val facesAtComposition2 =
            if (mode.value == DiceRollMode.OpenEndedD100) 10 else standardFaces.intValue
        val countAtComposition2 =
            if (mode.value == DiceRollMode.OpenEndedD100) 2 else standardCount.intValue
        val freshDoRoll: () -> DiceRollDescriptor = {
            val finalResults = if (mode.value == DiceRollMode.OpenEndedD100) {
                listOf(5, 7)
            } else {
                List(countAtComposition2) { 3 }
            }
            DiceRollDescriptor(
                faces = facesAtComposition2,
                mode = mode.value,
                rolls = listOf(
                    ChainRoll(
                        messageId = Uuid.random(),
                        odinId = OdinId("alice.test"),
                        results = finalResults,
                        rolledAtUtcMs = 1_700_000_000_000L,
                    ),
                ),
            )
        }

        // ---- Pre-fix: shake's LaunchedEffect held the stale closure ----
        val stale = staleDoRoll()
        assertEquals(DiceRollMode.OpenEndedD100, stale.mode, "stale closure reads CURRENT mode")
        assertEquals(4, stale.faces, "stale closure uses CAPTURED faces (=4)")
        assertFalse(
            stale.isValid(),
            "stale closure builds INVALID descriptor (mode=OE, faces=4) — this is the Ancient roll bug from homebase2.log",
        )

        // ---- Post-fix: rememberUpdatedState invokes the fresh closure ----
        val fresh = freshDoRoll()
        assertEquals(DiceRollMode.OpenEndedD100, fresh.mode)
        assertEquals(10, fresh.faces, "fresh closure has consistent faces=10 for mode=OE")
        assertTrue(
            fresh.isValid(),
            "fresh closure builds VALID descriptor — what rememberUpdatedState gives us",
        )
    }

    private fun build1d4(odinId: String, result: Int): DiceRollDescriptor =
        DiceRollDescriptor(
            faces = 4,
            mode = DiceRollMode.Standard,
            rolls = listOf(
                ChainRoll(
                    messageId = Uuid.random(),
                    odinId = OdinId(odinId),
                    results = listOf(result),
                    rolledAtUtcMs = 1_700_000_000_000L,
                    source = RollSource.LocalRandom,
                ),
            ),
        )
}
