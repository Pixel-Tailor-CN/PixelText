package vip.mystery0.pixel.text.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import vip.mystery0.pixel.text.domain.settings.NavigationTransitionStyle

internal fun navigationEnterTransition(style: NavigationTransitionStyle): EnterTransition =
    when (style) {
        NavigationTransitionStyle.DEFAULT -> activityLikeEnterTransition()
        NavigationTransitionStyle.SLIDE -> slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
        )
        NavigationTransitionStyle.FADE -> fadeIn(tween(FADE_DURATION_MS, easing = LinearEasing))
        NavigationTransitionStyle.NONE -> EnterTransition.None
    }

internal fun navigationExitTransition(style: NavigationTransitionStyle): ExitTransition =
    when (style) {
        NavigationTransitionStyle.DEFAULT -> activityLikeExitTransition()
        NavigationTransitionStyle.SLIDE -> slideOutHorizontally(
            targetOffsetX = { -it },
            animationSpec = tween(SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
        )
        NavigationTransitionStyle.FADE -> fadeOut(tween(FADE_DURATION_MS, easing = LinearEasing))
        NavigationTransitionStyle.NONE -> ExitTransition.None
    }

internal fun navigationPopEnterTransition(style: NavigationTransitionStyle): EnterTransition =
    when (style) {
        NavigationTransitionStyle.DEFAULT -> activityLikePopEnterTransition()
        NavigationTransitionStyle.SLIDE -> slideInHorizontally(
            initialOffsetX = { -it },
            animationSpec = tween(SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
        )
        NavigationTransitionStyle.FADE -> fadeIn(tween(FADE_DURATION_MS, easing = LinearEasing))
        NavigationTransitionStyle.NONE -> EnterTransition.None
    }

internal fun navigationPopExitTransition(style: NavigationTransitionStyle): ExitTransition =
    when (style) {
        NavigationTransitionStyle.DEFAULT -> activityLikePopExitTransition()
        NavigationTransitionStyle.SLIDE -> slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(SLIDE_DURATION_MS, easing = FastOutSlowInEasing),
        )
        NavigationTransitionStyle.FADE -> fadeOut(tween(FADE_DURATION_MS, easing = LinearEasing))
        NavigationTransitionStyle.NONE -> ExitTransition.None
    }

private fun activityLikeEnterTransition(): EnterTransition {
    return slideInHorizontally(
        initialOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(
            durationMillis = ACTIVITY_FOREGROUND_DURATION_MS,
            easing = FastOutSlowInEasing
        )
    ) +
            fadeIn(animationSpec = tween(durationMillis = NAV_ENTER_FADE_DURATION_MS)) +
            scaleIn(
                initialScale = ACTIVITY_FOREGROUND_INITIAL_SCALE,
                animationSpec = tween(
                    durationMillis = ACTIVITY_FOREGROUND_DURATION_MS,
                    easing = FastOutSlowInEasing
                )
            )
}

private fun activityLikeExitTransition(): ExitTransition {
    return slideOutHorizontally(
        targetOffsetX = { fullWidth -> -fullWidth / ACTIVITY_BACKGROUND_OFFSET_DIVISOR },
        animationSpec = tween(
            durationMillis = ACTIVITY_BACKGROUND_DURATION_MS,
            easing = FastOutSlowInEasing
        )
    ) +
            activityBackgroundFadeOut() +
            scaleOut(
                targetScale = ACTIVITY_BACKGROUND_TARGET_SCALE,
                animationSpec = tween(
                    durationMillis = ACTIVITY_BACKGROUND_DURATION_MS,
                    easing = FastOutSlowInEasing
                )
            )
}

private fun activityLikePopEnterTransition(): EnterTransition {
    return slideInHorizontally(
        initialOffsetX = { fullWidth -> -fullWidth / ACTIVITY_BACKGROUND_OFFSET_DIVISOR },
        animationSpec = tween(
            durationMillis = ACTIVITY_BACKGROUND_DURATION_MS,
            easing = FastOutSlowInEasing
        )
    ) +
            fadeIn(animationSpec = tween(durationMillis = NAV_ENTER_FADE_DURATION_MS)) +
            scaleIn(
                initialScale = ACTIVITY_BACKGROUND_TARGET_SCALE,
                animationSpec = tween(
                    durationMillis = ACTIVITY_BACKGROUND_DURATION_MS,
                    easing = FastOutSlowInEasing
                )
            )
}

private fun activityLikePopExitTransition(): ExitTransition {
    return slideOutHorizontally(
        targetOffsetX = { fullWidth -> fullWidth },
        animationSpec = tween(
            durationMillis = ACTIVITY_FOREGROUND_DURATION_MS,
            easing = FastOutSlowInEasing
        )
    ) +
            activityForegroundFadeOut() +
            scaleOut(
                targetScale = ACTIVITY_FOREGROUND_POP_EXIT_SCALE,
                animationSpec = tween(
                    durationMillis = ACTIVITY_FOREGROUND_DURATION_MS,
                    easing = FastOutSlowInEasing
                )
            )
}

private const val SLIDE_DURATION_MS = 300
private const val FADE_DURATION_MS = 200
private const val ACTIVITY_FOREGROUND_DURATION_MS = 300
private const val ACTIVITY_BACKGROUND_DURATION_MS = 260
private const val ACTIVITY_BACKGROUND_OFFSET_DIVISOR = 4
private const val ACTIVITY_FOREGROUND_INITIAL_SCALE = 0.96f
private const val ACTIVITY_FOREGROUND_POP_EXIT_SCALE = 0.90f
private const val ACTIVITY_BACKGROUND_TARGET_SCALE = 0.92f
private const val NAV_ENTER_FADE_DURATION_MS = 90
private const val NAV_EXIT_FADE_DURATION_MS = 90
private const val NAV_EXIT_FADE_DELAY_MS = 120
private const val NAV_FOREGROUND_EXIT_FADE_DELAY_MS = 210

private fun activityBackgroundFadeOut(): ExitTransition {
    return fadeOut(
        animationSpec = tween(
            durationMillis = NAV_EXIT_FADE_DURATION_MS,
            delayMillis = NAV_EXIT_FADE_DELAY_MS
        )
    )
}

private fun activityForegroundFadeOut(): ExitTransition {
    return fadeOut(
        animationSpec = tween(
            durationMillis = NAV_EXIT_FADE_DURATION_MS,
            delayMillis = NAV_FOREGROUND_EXIT_FADE_DELAY_MS
        )
    )
}
