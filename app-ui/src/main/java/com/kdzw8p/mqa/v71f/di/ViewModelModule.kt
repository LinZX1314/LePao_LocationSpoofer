package com.kdzw8p.mqa.v71f.di

import com.kdzw8p.mqa.v71f.viewmodel.ManageDataViewModel
import com.kdzw8p.mqa.v71f.viewmodel.MainViewModel
import com.kdzw8p.mqa.v71f.viewmodel.UpdateViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val viewModelModule = module {
    viewModel { MainViewModel(get(), get(), get(), get(), get(), get(), get(), get(), androidContext()) }
    viewModel { UpdateViewModel(androidContext()) }
    viewModel { ManageDataViewModel(get()) }
}
