package id.homebase.api.client.drives

import id.homebase.api.youauth.ZkKeyStore

internal class MemoryZkStorage(val values: MutableMap<String, String> = mutableMapOf()) : ZkKeyStore.Storage {
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}
