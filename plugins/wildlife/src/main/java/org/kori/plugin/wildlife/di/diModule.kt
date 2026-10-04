package org.kori.plugin.wildlife.di

import org.cwcc.open.plugin.wildlife.viewmodel.WildLifeViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val diModule =
    module {
        viewModel { WildLifeViewModel(get()) }
    }
