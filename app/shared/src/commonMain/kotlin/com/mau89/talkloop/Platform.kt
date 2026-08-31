package com.mau89.talkloop

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform