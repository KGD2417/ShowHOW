package com.showhow.ui

/**
 * Navigation is a sealed class and a back stack of one, on purpose. Navigation
 * Compose buys nothing for five screens and costs an argument about routes at
 * 2am.
 */
sealed interface Screen {
    data object Library : Screen
    data object Show : Screen
    data class Review(val guideId: String) : Screen
    data class Player(val guideId: String) : Screen
    data object Debug : Screen
}
