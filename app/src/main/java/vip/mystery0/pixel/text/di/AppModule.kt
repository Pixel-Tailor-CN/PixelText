package vip.mystery0.pixel.text.di

import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module
import vip.mystery0.pixel.text.data.repository.MessageRepositoryImpl
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.ui.message.ConversationDetailViewModel
import vip.mystery0.pixel.text.ui.message.ConversationListViewModel
import vip.mystery0.pixel.text.ui.message.MessageViewModel

val appModule = module {
    single { MessageParser(androidContext()) }
    single<MessageRepository> { MessageRepositoryImpl(androidContext(), get()) }
    viewModel { MessageViewModel(get()) }
    viewModel { ConversationListViewModel(get()) }
    viewModel { ConversationDetailViewModel(get()) }
}
