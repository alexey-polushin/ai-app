package me.lekseg.aiapp

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform