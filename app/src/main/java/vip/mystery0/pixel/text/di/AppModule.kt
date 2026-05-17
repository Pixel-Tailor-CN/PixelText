package vip.mystery0.pixel.text.di

import android.content.ContentResolver
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import vip.mystery0.pixel.text.data.db.ConversationArchiveDatabase
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.data.repository.MessageRepositoryImpl
import vip.mystery0.pixel.text.data.repository.SpamRepositoryImpl
import vip.mystery0.pixel.text.data.source.ContactDataSource
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.spam.SpamClassifier
import vip.mystery0.pixel.text.domain.spam.SpamClassifierFactory
import vip.mystery0.pixel.text.domain.spam.SpamRepository
import vip.mystery0.pixel.text.viewmodel.ArchivedConversationListViewModel
import vip.mystery0.pixel.text.viewmodel.ConversationDetailViewModel
import vip.mystery0.pixel.text.viewmodel.ConversationListViewModel
import vip.mystery0.pixel.text.viewmodel.MessageViewModel
import vip.mystery0.pixel.text.ui.message.search.SearchViewModel
import vip.mystery0.pixel.text.viewmodel.SpamConversationListViewModel

val appModule = module {
    single<ContentResolver> { androidContext().contentResolver }
    single { MessageParser(androidContext()) }
    single { SpamDatabase.create(androidContext()) }
    single { ConversationArchiveDatabase.create(androidContext()) }
    single { ContactDataSource(androidContext(), get()) }
    single { TelephonyDataSource(androidContext(), get()) }
    factory { SpamClassifier(androidContext()) }
    single<SpamClassifierFactory> { SpamClassifierFactory { SpamClassifier(androidContext()) } }
    single<SpamRepository> { SpamRepositoryImpl(get()) }
    single<MessageRepository> { MessageRepositoryImpl(get(), get(), get(), get(), get()) }
    viewModel { MessageViewModel(get()) }
    viewModel { ConversationListViewModel(get()) }
    viewModel { ArchivedConversationListViewModel(get()) }
    viewModel { SpamConversationListViewModel(get(), get(), get(), androidContext()) }
    viewModel { ConversationDetailViewModel(get(), get(), androidContext(), get(), get()) }
    viewModel { SearchViewModel(get()) }
}
