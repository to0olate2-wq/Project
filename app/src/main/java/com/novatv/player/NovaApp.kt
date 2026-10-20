package com.novatv.player

import android.app.Application
import android.content.Context
import com.novatv.player.data.Repository

class NovaApp : Application() {
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repository(this)
    }
}

val Context.repo: Repository
    get() = (applicationContext as NovaApp).repo
