package id.homebase.chat.agentui

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

    fun draftPostCardBytes(): ByteArray = draftPostCardJson().encodeToByteArray()

    fun decodeDraftPostCard(): AgentUiTree =
        (AgentUiTree.decode(draftPostCardBytes(), imageKeys) as AgentUiTreeResult.Valid).tree

    fun tree(vararg nodes: String, root: String = "root"): ByteArray =
        """{"root":"$root","nodes":[${nodes.joinToString(",")}]}""".encodeToByteArray()
}
