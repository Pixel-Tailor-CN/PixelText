package vip.mystery0.pixel.text.di

import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import vip.mystery0.pixel.text.data.source.mms.MmsMediaMetadataReader
import vip.mystery0.pixel.text.ui.message.mms.MmsPlaybackController
import android.content.ContentResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import vip.mystery0.pixel.text.data.db.mirror.MessageMirrorDatabase
import vip.mystery0.pixel.text.data.source.mirror.TelephonyMirrorSource
import vip.mystery0.pixel.text.data.source.mirror.MirrorAttachmentStore
import vip.mystery0.pixel.text.data.repository.mirror.MirrorAttachmentCopier
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorSynchronizer
import vip.mystery0.pixel.text.data.repository.mirror.MirrorChangeObserver
import vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorRepositoryImpl
import vip.mystery0.pixel.text.domain.repository.MessageMirrorRepository
import vip.mystery0.pixel.text.data.repository.mms.MmsContentRepositoryImpl
import vip.mystery0.pixel.text.data.source.mms.MmsAttachmentExporter
import vip.mystery0.pixel.text.data.source.mms.MmsPartReader
import vip.mystery0.pixel.text.domain.parser.mms.MmsHtmlParser
import vip.mystery0.pixel.text.domain.parser.mms.MmsContactParser
import vip.mystery0.pixel.text.domain.parser.mms.MmsCalendarParser
import vip.mystery0.pixel.text.domain.repository.MmsContentRepository
import vip.mystery0.pixel.text.domain.model.mirror.MessageTransport
import vip.mystery0.pixel.text.mms.MmsDownloadCoordinator
import vip.mystery0.pixel.text.worker.MessageMirrorScheduler
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidApplication
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import vip.mystery0.pixel.text.data.db.ConversationArchiveDatabase
import vip.mystery0.pixel.text.data.db.ConversationCacheDatabase
import vip.mystery0.pixel.text.data.db.SpamDatabase
import vip.mystery0.pixel.text.data.db.VerificationCodeIndexDatabase
import vip.mystery0.pixel.text.data.repository.AppSettingsRepositoryImpl
import vip.mystery0.pixel.text.data.repository.ConversationCacheRepository
import vip.mystery0.pixel.text.data.repository.HubResourceRepository
import vip.mystery0.pixel.text.data.repository.KeywordSpamRepositoryImpl
import vip.mystery0.pixel.text.data.repository.MessageRepositoryImpl
import vip.mystery0.pixel.text.data.repository.SampleSubmissionRepository
import vip.mystery0.pixel.text.data.repository.SpamRepositoryImpl
import vip.mystery0.pixel.text.data.repository.SenderProfileRepository
import vip.mystery0.pixel.text.data.repository.ThemeAssetRepositoryImpl
import vip.mystery0.pixel.text.data.repository.ThemeConfigurationRepositoryImpl
import vip.mystery0.pixel.text.data.repository.VerificationCodeRepositoryImpl
import vip.mystery0.pixel.text.data.repository.UnreadSmsCounter
import vip.mystery0.pixel.text.data.resource.BundledResourceVersionProvider
import vip.mystery0.pixel.text.data.resource.HubResourceStore
import vip.mystery0.pixel.text.data.resource.SenderProfileStore
import vip.mystery0.pixel.text.data.source.ContactDataSource
import vip.mystery0.pixel.text.data.source.PickedPhoneSource
import vip.mystery0.pixel.text.data.source.PickedPhoneSourceImpl
import vip.mystery0.pixel.text.data.source.PixelTextHubClient
import vip.mystery0.pixel.text.data.source.TelephonyDataSource
import vip.mystery0.pixel.text.domain.parser.MessageParser
import vip.mystery0.pixel.text.domain.repository.MessageRepository
import vip.mystery0.pixel.text.domain.repository.VerificationCodeRepository
import vip.mystery0.pixel.text.domain.settings.AppSettingsRepository
import vip.mystery0.pixel.text.domain.spam.SpamClassifier
import vip.mystery0.pixel.text.domain.spam.SpamClassifierFactory
import vip.mystery0.pixel.text.domain.spam.KeywordSpamRepository
import vip.mystery0.pixel.text.domain.spam.SpamRepository
import vip.mystery0.pixel.text.domain.theme.ThemeAssetRepository
import vip.mystery0.pixel.text.domain.theme.ThemeConfigurationRepository
import vip.mystery0.pixel.text.smartspacer.SmartspacerSmsRepository
import vip.mystery0.pixel.text.smartspacer.UnreadSmsComplicationSettingsRepository
import vip.mystery0.pixel.text.ui.message.search.SearchViewModel
import vip.mystery0.pixel.text.ui.screen.mock.MockMessageFactory
import vip.mystery0.pixel.text.ui.theme.HighTextContrastMonitor
import vip.mystery0.pixel.text.viewmodel.ArchivedConversationListViewModel
import vip.mystery0.pixel.text.viewmodel.ConversationDetailCustomizationViewModel
import vip.mystery0.pixel.text.viewmodel.ConversationDetailViewModel
import vip.mystery0.pixel.text.viewmodel.ConversationListViewModel
import vip.mystery0.pixel.text.viewmodel.MessageViewModel
import vip.mystery0.pixel.text.viewmodel.KeywordSpamViewModel
import vip.mystery0.pixel.text.viewmodel.SampleSubmissionViewModel
import vip.mystery0.pixel.text.viewmodel.SettingsViewModel
import vip.mystery0.pixel.text.viewmodel.SpamConversationListViewModel
import vip.mystery0.pixel.text.viewmodel.VerificationCodeViewModel
import vip.mystery0.pixel.text.viewmodel.UnreadBadgeViewModel
import vip.mystery0.pixel.text.worker.ResourceUpdateScheduler
import vip.mystery0.pixel.text.worker.KeywordSpamRebuildScheduler
import vip.mystery0.pixel.text.worker.VerificationCodeIndexScheduler
import vip.mystery0.pixel.text.worker.VerificationCodeCleanupScheduler

val appModule = module {
    single<ContentResolver> { androidContext().contentResolver }
    single { AppSettingsRepositoryImpl(androidContext()) }
    single<AppSettingsRepository> { get<AppSettingsRepositoryImpl>() }
    single { vip.mystery0.pixel.text.data.backup.RestoreSafetyCoordinator(androidContext()) }
    single { vip.mystery0.pixel.text.data.backup.AppDatabaseSnapshotter(get(), get(), get()) }
    single { vip.mystery0.pixel.text.data.backup.BackupDatabaseReader(get()) }
    single { vip.mystery0.pixel.text.data.backup.BackupArchiveCodec(androidContext()) }
    single { vip.mystery0.pixel.text.data.backup.BackupSettingsMapper(androidContext(), get(), get(), get(), get()) }
    single { vip.mystery0.pixel.text.data.backup.BackupRuleStore(get<vip.mystery0.pixel.text.domain.spam.SenderWhitelistRepository>() as vip.mystery0.pixel.text.data.repository.SenderWhitelistRepositoryImpl) }
    single { vip.mystery0.pixel.text.data.backup.SmsRestoreDataSource(androidContext(), get(), get(), get(), get()) }
    single<vip.mystery0.pixel.text.domain.backup.BackupRepository> {
        vip.mystery0.pixel.text.data.repository.BackupRepositoryImpl(
            androidContext(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
    viewModel { vip.mystery0.pixel.text.viewmodel.BackupViewModel(get()) }
    single<ThemeConfigurationRepository> {
        ThemeConfigurationRepositoryImpl(androidContext())
    }
    single<ThemeAssetRepository> { ThemeAssetRepositoryImpl(androidContext()) }
    single { HighTextContrastMonitor(androidContext()) }
    single { BundledResourceVersionProvider(androidContext()) }
    single { HubResourceStore(androidContext()) }
    single { SenderProfileStore(androidContext()) }
    single { PixelTextHubClient("https://pixeltext.api.mystery0.vip") }
    single { MessageParser(androidContext(), get()) }
    single { HubResourceRepository(get(), get(), get(), get(), get(), get()) }
    single { ResourceUpdateScheduler(androidContext(), get()) }
    single { KeywordSpamRebuildScheduler(androidContext()) }
    single { VerificationCodeIndexScheduler(androidContext()) }
    single { VerificationCodeCleanupScheduler(androidContext(), get()) }
    single { SampleSubmissionRepository(androidContext(), get(), get()) }
    single { SpamDatabase.create(androidContext()) }
    single { ConversationArchiveDatabase.create(androidContext()) }
    single { ConversationCacheDatabase.create(androidContext()) }
    single { SenderProfileRepository(get(), get(), get()) }
    single { VerificationCodeIndexDatabase.create(androidContext()) }
    single { ContactDataSource(androidContext(), get()) }
    single<PickedPhoneSource> { PickedPhoneSourceImpl(androidContext()) }
    single { TelephonyDataSource(androidContext(), get()) }
    single { MessageMirrorDatabase.create(androidContext()) }
    single { vip.mystery0.pixel.text.data.repository.initialization.DataInitializationRepository(get()) }
    single {
        vip.mystery0.pixel.text.data.repository.initialization.DataInitializationGuard(
            get(),
            get()
        )
    }
    single {
        vip.mystery0.pixel.text.data.repository.initialization.DataInitializationCoordinator(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
    single { vip.mystery0.pixel.text.worker.DataInitializationScheduler(androidContext(), get()) }
    single {
        vip.mystery0.pixel.text.data.repository.initialization.InitializationSpamScanner(
            get(),
            get(),
            get(),
            get()
        )
    }
    single { TelephonyMirrorSource(androidContext()) }
    single { MirrorAttachmentStore(androidContext()) }
    single { MirrorAttachmentCopier(get(), get()) }
    single { MessageMirrorScheduler(androidContext()) }
    single { vip.mystery0.pixel.text.mms.MmsReceptionResponseSender(androidContext()) }
    single { vip.mystery0.pixel.text.mms.MmsReceptionNotifications(androidContext(), get(), get(), get()) }
    single { MmsDownloadCoordinator(androidContext(), get(), get()) }
    single { vip.mystery0.pixel.text.mms.MmsIncomingPduHandler(androidContext(), get(), get(), get()) }
    single {
        MessageMirrorSynchronizer(get(), get(), get()).apply {
            onMessageDeletionCommitted = { key ->
                get<MmsContentRepositoryImpl>().invalidate(key)
                get<MmsPlaybackController>().onMessageDeleted(key)
            }
            onMessageDeleted = { key ->
                get<MmsContentRepositoryImpl>().invalidate(key)
                val id = if (key.transport == MessageTransport.SMS) key.sourceId else -key.sourceId
                get<SpamRepository>().delete(setOf(id))
                if (key.transport == MessageTransport.SMS) {
                    get<VerificationCodeRepository>().deleteMessageIds(listOf(id))
                }
            }
        }
    }
    single { vip.mystery0.pixel.text.data.repository.mirror.MessageMirrorIncrementalSynchronizer(get(), get()) }
    single<MessageMirrorRepository> { MessageMirrorRepositoryImpl(get(), get()) }
    single { vip.mystery0.pixel.text.data.repository.mirror.LocalConversationSpamFilter(get()) }
    single { MmsPartReader() }
    single { MmsHtmlParser() }
    single { MmsContactParser() }
    single { MmsCalendarParser() }
    single { ImageLoader.Builder(androidContext()).components { add(AnimatedImageDecoder.Factory()) }.build() }
    single { MmsMediaMetadataReader(androidContext()) }
    single { MmsPlaybackController(androidContext(), get()) }
    single { MmsAttachmentExporter(androidContext(), get()) }
    single { MmsContentRepositoryImpl(get(), get(), get(), get(), get()) }
    single {
        vip.mystery0.pixel.text.data.repository.mms.MmsTextIndexer(
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
    single<MmsContentRepository> { get<MmsContentRepositoryImpl>() }
    single {
        MirrorChangeObserver(androidContext(), get(), get(), CoroutineScope(SupervisorJob() + Dispatchers.IO)).apply {
            onDirty = { get<MessageMirrorScheduler>().schedule() }
        }
    }
    factory { SpamClassifier(androidContext(), get()) }
    single<SpamClassifierFactory> {
        SpamClassifierFactory { SpamClassifier(androidContext(), get()) }
    }
    single { vip.mystery0.pixel.text.data.source.WhitelistMessageSource(get()) }
    single<vip.mystery0.pixel.text.domain.spam.SenderWhitelistRepository> {
        vip.mystery0.pixel.text.data.repository.SenderWhitelistRepositoryImpl(get(), get())
    }
    single<SpamRepository> { SpamRepositoryImpl(get(), get(), get()) }
    single<KeywordSpamRepository> { KeywordSpamRepositoryImpl(get(), get()) }
    single { UnreadSmsCounter(get(), get(), get()) }
    single<VerificationCodeRepository> {
        VerificationCodeRepositoryImpl(get(), get(), get(), get(), get())
    }
    single { UnreadSmsComplicationSettingsRepository(androidContext()) }
    factory { MockMessageFactory(get()) }
    single { SmartspacerSmsRepository(get(), get(), get(), get(), get()) }
    single {
        ConversationCacheRepository(androidContext(), get(), get(), get(), get())
    }
    single<MessageRepository> {
        MessageRepositoryImpl(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            androidContext()
        )
    }
    viewModel { MessageViewModel(get()) }
    viewModel { vip.mystery0.pixel.text.viewmodel.MirrorMessageDetailViewModel(get()) }
    viewModel { vip.mystery0.pixel.text.viewmodel.MmsContentViewModel(get()) }
    viewModel { KeywordSpamViewModel(get(), get()) }
    viewModel { vip.mystery0.pixel.text.viewmodel.SenderWhitelistViewModel(get(), androidApplication()) }
    viewModel { ConversationListViewModel(get(), get(), get(), get()) }
    viewModel { ArchivedConversationListViewModel(get()) }
    viewModel { SpamConversationListViewModel(get(), get(), get(), androidContext()) }
    viewModel { ConversationDetailViewModel(get(), get(), get(), androidContext(), get(), get(), get(), get(), get()) }
    viewModel {
        ConversationDetailCustomizationViewModel(get(), get(), get())
    }
    viewModel { SearchViewModel(get(), get()) }
    viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
    viewModel { SampleSubmissionViewModel(get()) }
    viewModel { VerificationCodeViewModel(get(), get(), get(), get()) }
    viewModel { UnreadBadgeViewModel(androidContext(), get(), get()) }
}
