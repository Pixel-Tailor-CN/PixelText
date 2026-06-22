package vip.mystery0.pixel.text.di

import android.content.ContentResolver
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import vip.mystery0.pixel.text.data.db.ConversationArchiveDatabase
import vip.mystery0.pixel.text.data.db.ConversationCacheDatabase
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.data.repository.AppSettingsRepositoryImpl
import vip.mystery0.pixel.text.data.repository.ConversationCacheRepository
import vip.mystery0.pixel.text.data.repository.HubResourceRepository
import vip.mystery0.pixel.text.data.repository.MessageRepositoryImpl
import vip.mystery0.pixel.text.data.repository.SampleSubmissionRepository
import vip.mystery0.pixel.text.data.repository.SpamRepositoryImpl
import vip.mystery0.pixel.text.data.resource.BundledResourceVersionProvider
import vip.mystery0.pixel.text.data.resource.HubResourceStore
import vip.mystery0.pixel.text.data.source.ContactDataSource
import vip.mystery0.pixel.text.data.source.PixelTextHubClient
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.spam.SpamClassifier
import vip.mystery0.pixel.text.domain.spam.SpamClassifierFactory
import vip.mystery0.pixel.text.domain.spam.SpamRepository
import vip.mystery0.pixel.text.smartspacer.SmartspacerSmsRepository
import vip.mystery0.pixel.text.ui.message.search.SearchViewModel
import vip.mystery0.pixel.text.viewmodel.ArchivedConversationListViewModel
import vip.mystery0.pixel.text.viewmodel.ConversationDetailViewModel
import vip.mystery0.pixel.text.viewmodel.ConversationListViewModel
import vip.mystery0.pixel.text.viewmodel.MessageViewModel
import vip.mystery0.pixel.text.viewmodel.SampleSubmissionViewModel
import vip.mystery0.pixel.text.viewmodel.SettingsViewModel
import vip.mystery0.pixel.text.viewmodel.SpamConversationListViewModel

val appModule = module {
    single<ContentResolver> { androidContext().contentResolver }
    single<AppSettingsRepository> { AppSettingsRepositoryImpl(androidContext()) }
    single { BundledResourceVersionProvider(androidContext()) }
    single { HubResourceStore(androidContext()) }
    single { PixelTextHubClient("https://pixeltext.api.mystery0.vip") }
    single { MessageParser(androidContext(), get()) }
    single { HubResourceRepository(get(), get(), get(), get()) }
    single { SampleSubmissionRepository(androidContext(), get(), get()) }
    single { SpamDatabase.create(androidContext()) }
    single { ConversationArchiveDatabase.create(androidContext()) }
    single { ConversationCacheDatabase.create(androidContext()) }
    single { ContactDataSource(androidContext(), get()) }
    single { TelephonyDataSource(androidContext(), get()) }
    factory { SpamClassifier(androidContext(), get()) }
    single<SpamClassifierFactory> {
        SpamClassifierFactory { SpamClassifier(androidContext(), get()) }
    }
    single<SpamRepository> { SpamRepositoryImpl(get(), get()) }
    single { SmartspacerSmsRepository(get(), get()) }
    single {
        val db = get<ConversationCacheDatabase>()
        ConversationCacheRepository(androidContext(), db.cachedConversationDao(), get())
    }
    single<MessageRepository> {
        MessageRepositoryImpl(get(), get(), get(), get(), get(), get(), get(), androidContext())
    }
    viewModel { MessageViewModel(get()) }
    viewModel { ConversationListViewModel(get(), get()) }
    viewModel { ArchivedConversationListViewModel(get()) }
    viewModel { SpamConversationListViewModel(get(), get(), get(), androidContext()) }
    viewModel { ConversationDetailViewModel(get(), get(), androidContext(), get(), get()) }
    viewModel { SearchViewModel(get()) }
    viewModel { SettingsViewModel(get(), get(), get()) }
    viewModel { SampleSubmissionViewModel(get()) }
}
