package id.homebase.api.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
// `Dispatchers.IO` is an `internal val` member on Native — referencing it
// directly fails with "Cannot access 'val IO: CoroutineDispatcher': it is
// internal in 'kotlinx.coroutines.Dispatchers'". The public way in is the
// top-level extension property `kotlinx.coroutines.IO`, which this import
// brings into scope so `Dispatchers.IO` resolves to it instead of the
// internal member.
import kotlinx.coroutines.IO

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
