package io.github.tzhvh.scryernext.zvec

class ZvecException(val code: Int, message: String) : RuntimeException("zvec error $code: $message")
