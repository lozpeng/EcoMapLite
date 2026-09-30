

package com.combo.plugin.sample.setting.di

import com.combo.plugin.sample.setting.viewmodel.SettingViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val diModule =
    module {
        viewModel { SettingViewModel(get()) }
    }
