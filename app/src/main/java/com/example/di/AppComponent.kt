package com.example.di

import android.content.Context
import com.example.MainActivity
import com.example.data.database.ChatRepository
import com.example.network.GeminiService
import com.example.ui.ChatViewModel
import dagger.BindsInstance
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(modules = [DatabaseModule::class])
interface AppComponent {
    fun chatRepository(): ChatRepository
    fun geminiService(): GeminiService
    fun chatViewModel(): ChatViewModel

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance @dagger.hilt.android.qualifiers.ApplicationContext context: Context): AppComponent
    }
}
