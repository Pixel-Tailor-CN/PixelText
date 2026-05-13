package vip.mystery0.pixel.text.di

import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.data.repository.MessageRepositoryImpl
import vip.mystery0.pixel.text.data.repository.SpamRepositoryImpl
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.spam.SpamClassifier
import vip.mystery0.pixel.text.domain.spam.SpamRepository
import vip.mystery0.pixel.text.ui.message.ConversationDetailViewModel
import vip.mystery0.pixel.text.ui.message.ConversationListViewModel
import vip.mystery0.pixel.text.ui.message.MessageViewModel
import vip.mystery0.pixel.text.ui.message.search.SearchViewModel

val appModule = module {
    single { MessageParser(androidContext()) }
    single { SpamDatabase(androidContext()) }
    single { SpamClassifier(androidContext()) }
    single<SpamRepository> { SpamRepositoryImpl(get()) }
    single<MessageRepository> { MessageRepositoryImpl(androidContext(), get(), get()) }
    viewModel { MessageViewModel(get()) }
    viewModel { ConversationListViewModel(get()) }
    viewModel { ConversationDetailViewModel(get(), androidContext()) }
    viewModel { SearchViewModel(get()) }
}
