package com.example.boxpandora.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry

object PandoraMotion {
    const val Quick = 140
    const val Standard = 200
    const val Emphasis = 240
    const val Navigation = 260

    val fadeInTween = tween<Float>(durationMillis = Standard, easing = LinearOutSlowInEasing)
    val fadeOutTween = tween<Float>(durationMillis = Quick, easing = FastOutSlowInEasing)

    fun <T> enterSpec(): FiniteAnimationSpec<T> =
        tween(durationMillis = Emphasis, easing = FastOutSlowInEasing)

    fun <T> exitSpec(): FiniteAnimationSpec<T> =
        tween(durationMillis = Standard, easing = FastOutSlowInEasing)
}

fun panelEnterTransition(): EnterTransition =
    fadeIn(animationSpec = PandoraMotion.fadeInTween) +
        slideInVertically(animationSpec = PandoraMotion.enterSpec()) { it / 10 }

fun panelExitTransition(): ExitTransition =
    fadeOut(animationSpec = PandoraMotion.fadeOutTween) +
        slideOutVertically(animationSpec = PandoraMotion.exitSpec()) { it / 12 }

fun inlineRevealEnter(): EnterTransition =
    fadeIn(animationSpec = PandoraMotion.fadeInTween) +
        expandVertically(animationSpec = PandoraMotion.enterSpec())

fun inlineRevealExit(): ExitTransition =
    fadeOut(animationSpec = PandoraMotion.fadeOutTween) +
        shrinkVertically(animationSpec = PandoraMotion.exitSpec())

fun overlayFadeEnter(): EnterTransition = fadeIn(animationSpec = PandoraMotion.fadeInTween)

fun overlayFadeExit(): ExitTransition = fadeOut(animationSpec = PandoraMotion.fadeOutTween)

fun drawerEnterTransition(): EnterTransition =
    slideInHorizontally(animationSpec = PandoraMotion.enterSpec()) { -it / 2 } +
        fadeIn(animationSpec = PandoraMotion.fadeInTween)

fun drawerExitTransition(): ExitTransition =
    slideOutHorizontally(animationSpec = PandoraMotion.exitSpec()) { -it / 2 } +
        fadeOut(animationSpec = PandoraMotion.fadeOutTween)

fun modalScreenEnter(): EnterTransition =
    fadeIn(animationSpec = PandoraMotion.fadeInTween) +
        scaleIn(initialScale = 0.96f, animationSpec = PandoraMotion.enterSpec())

fun modalScreenExit(): ExitTransition =
    fadeOut(animationSpec = PandoraMotion.fadeOutTween) +
        scaleOut(targetScale = 0.98f, animationSpec = PandoraMotion.exitSpec())

fun detailForwardEnter(scope: AnimatedContentTransitionScope<NavBackStackEntry>): EnterTransition =
    scope.slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = PandoraMotion.enterSpec()
    ) + fadeIn(animationSpec = PandoraMotion.fadeInTween)

fun detailForwardExit(): ExitTransition = fadeOut(animationSpec = PandoraMotion.fadeOutTween)

fun detailBackEnter(): EnterTransition = fadeIn(animationSpec = PandoraMotion.fadeInTween)

fun detailBackExit(scope: AnimatedContentTransitionScope<NavBackStackEntry>): ExitTransition =
    scope.slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.End,
        animationSpec = PandoraMotion.exitSpec()
    ) + fadeOut(animationSpec = PandoraMotion.fadeOutTween)
