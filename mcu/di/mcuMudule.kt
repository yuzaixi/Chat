package com.huidiandian.meeting.mcu.di

import com.huidiandian.meeting.mcu.repository.IMcuMainRepository
import com.huidiandian.meeting.mcu.repository.IMcuMeetRepository
import com.huidiandian.meeting.mcu.repository.McuMainViewRepository
import com.huidiandian.meeting.mcu.repository.McuMeetRepositoryImpl
import com.huidiandian.meeting.mcu.viewmodel.MCUMeetViewModel
import com.huidiandian.meeting.mcu.viewmodel.McuMainViewModel
import com.huidiandian.meeting.mcu.viewmodel.ScreenShareViewModel
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val mcuModule = module {
    factory<IMcuMainRepository> { McuMainViewRepository() }
    factory<IMcuMeetRepository> { McuMeetRepositoryImpl() } // 每次重新生成

    viewModel { McuMainViewModel(get()) }
    viewModel { MCUMeetViewModel(get()) }
    viewModel { ScreenShareViewModel() }
}