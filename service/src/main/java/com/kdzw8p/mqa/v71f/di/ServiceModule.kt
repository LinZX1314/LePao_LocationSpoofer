package com.kdzw8p.mqa.v71f.di

import com.kdzw8p.mqa.v71f.data.repository.SpoofingServiceController
import com.kdzw8p.mqa.v71f.service.SpoofingServiceControllerImpl
import org.koin.dsl.module

val serviceModule = module {
    single<SpoofingServiceController> { SpoofingServiceControllerImpl() }
}
