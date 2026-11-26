package id.homebase.chat.agentui

import kotlin.test.assertIs

object AgentUiFixtures {
    val imageKeys = setOf("chat_web0", "chat_web1")

    fun draftPostCardJson(): String = """
        {"root":"card","nodes":[
          {"id":"card","type":"card","tone":"default","children":["head","pics","caption","chips","actions"]},
          {"id":"head","type":"text","style":"title","markdown":"Draft post"},
          {"id":"pics","type":"list","direction":"horizontal","children":["img0","img1"]},
          {"id":"img0","type":"image","payloadKey":"chat_web0","alt":"Slide one: the spring launch"},
          {"id":"img1","type":"image","payloadKey":"chat_web1","alt":"Slide two: the team"},
          {"id":"caption","type":"text","style":"body","markdown":"Spring is here. **Meet** the new release."},
          {"id":"chips","type":"row","spacing":"small","children":["ch_x","ch_li","ch_ig"]},
          {"id":"ch_x","type":"chip","label":"X","selected":true},
          {"id":"ch_li","type":"chip","label":"LinkedIn","selected":true},
          {"id":"ch_ig","type":"chip","label":"Instagram"},
          {"id":"actions","type":"row","spacing":"small","children":["approve","edit","discard"]},
          {"id":"approve","type":"button","label":"Approve","style":"filled","action":{"id":"approve"}},
          {"id":"edit","type":"button","label":"Edit","style":"tonal","action":{"id":"edit"}},
          {"id":"discard","type":"button","label":"Discard","style":"destructive","action":{"id":"discard"}}
        ]}
    """.trimIndent()

    /** The order-summary card in AGENT_UI_GUIDE.md. */
    fun orderSummaryCardJson(): String = """
        {"root":"card","nodes":[
          {"id":"card","type":"card","children":["head","o1","o2","sum","actions"]},
          {"id":"head","type":"text","style":"title","markdown":"Acme orders this week"},
          {"id":"o1","type":"listItem","title":"Order #1042","subtitle":"2 items · $42.00","caption":"Delivered Tuesday","trailing":"o1s","fallback":"Order #1042: delivered"},
          {"id":"o1s","type":"badge","label":"Delivered","tone":"success","fallback":"Delivered"},
          {"id":"o2","type":"listItem","title":"Order #1043","subtitle":"1 item · $18.50","caption":"Payment failed","trailing":"o2s","fallback":"Order #1043: payment failed"},
          {"id":"o2s","type":"badge","label":"Payment failed","tone":"error","fallback":"Payment failed"},
          {"id":"sum","type":"facts","items":[{"label":"Orders","value":"2"},{"label":"Total","value":"$60.50"},{"label":"Next delivery","value":"Friday"}],"fallback":"2 orders, $60.50 in all"},
          {"id":"actions","type":"row","children":["retry","track"]},
          {"id":"retry","type":"button","label":"Retry payment","style":"filled","action":{"id":"retry_1043"}},
          {"id":"track","type":"button","label":"View orders","style":"outlined","url":"https://acme.example/orders"}
        ]}
    """.trimIndent()

    fun draftPostCardBytes(): ByteArray = draftPostCardJson().encodeToByteArray()

    fun decodeDraftPostCard(): AgentUiTree = valid(draftPostCardBytes())

    fun valid(bytes: ByteArray, keys: Set<String> = imageKeys): AgentUiTree {
        val r = AgentUiTree.decode(bytes, keys)
        assertIs<AgentUiTreeResult.Valid>(r, (r as? AgentUiTreeResult.Invalid)?.reason)
        return r.tree
    }

    fun tree(vararg nodes: String, root: String = "root"): ByteArray =
        """{"root":"$root","nodes":[${nodes.joinToString(",")}]}""".encodeToByteArray()
}
