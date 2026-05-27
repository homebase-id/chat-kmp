package id.homebase.core.image

// kCGImageAlphaPremultipliedFirst | kCGBitmapByteOrder32Little → BGRA in memory.
// Used by NativeImageDecoder and PdfRenderer when blitting into Skia via CGBitmapContext.
val CG_BGRA_PREMUL_BITMAP_INFO: UInt = 2u or 8192u
