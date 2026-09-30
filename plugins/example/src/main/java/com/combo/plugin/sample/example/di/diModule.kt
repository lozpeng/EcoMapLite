

package com.combo.plugin.sample.example.di

import com.combo.plugin.sample.example.viewmodel.PluginUpdateViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val diModule =
    module {
        viewModel { PluginUpdateViewModel(get(),get()) }
    }
