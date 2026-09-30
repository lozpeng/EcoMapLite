

package com.combo.plugin.sample.home.di

import com.combo.plugin.sample.home.viewmodel.HomeViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val diModule =
    module {
        viewModel { HomeViewModel(get()) }
    }
